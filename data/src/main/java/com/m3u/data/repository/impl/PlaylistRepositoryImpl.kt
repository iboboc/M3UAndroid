package com.m3u.data.repository.impl

import android.content.Context
import android.net.Uri
import androidx.core.net.toUri
import com.m3u.core.annotation.PlaylistStrategy
import com.m3u.core.architecture.Logger
import com.m3u.core.architecture.execute
import com.m3u.core.architecture.sandBox
import com.m3u.core.util.basic.startsWithAny
import com.m3u.core.util.belong
import com.m3u.core.util.readFileContent
import com.m3u.core.util.readFileName
import com.m3u.core.wrapper.Process
import com.m3u.core.wrapper.emitException
import com.m3u.core.wrapper.emitMessage
import com.m3u.core.wrapper.emitResource
import com.m3u.core.wrapper.processFlow
import com.m3u.core.wrapper.pt
import com.m3u.data.database.dao.PlaylistDao
import com.m3u.data.database.dao.StreamDao
import com.m3u.data.database.entity.Playlist
import com.m3u.data.database.entity.PlaylistWithStreams
import com.m3u.data.database.entity.Stream
import com.m3u.data.parser.M3UPlaylistParser
import com.m3u.data.parser.model.toStream
import com.m3u.data.repository.PlaylistRepository
import com.m3u.i18n.R.string
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileNotFoundException
import java.io.InputStream
import javax.inject.Inject

class PlaylistRepositoryImpl @Inject constructor(
    private val playlistDao: PlaylistDao,
    private val streamDao: StreamDao,
    @Logger.Ui private val logger: Logger,
    private val client: OkHttpClient,
    @M3UPlaylistParser.BjoernPetersen private val parser: M3UPlaylistParser,
    @ApplicationContext private val context: Context
) : PlaylistRepository {

    override fun subscribe(
        title: String,
        url: String,
        strategy: Int
    ): Flow<Process<Unit>> = processFlow {
        try {
            val actualUrl = url.actualUrl()
            if (actualUrl == null) {
                emitMessage("wrong url")
                return@processFlow
            }
            val streams = when {
                url.isNetworkUrl -> acquireNetwork(actualUrl)
                url.isAndroidUrl -> acquireAndroid(actualUrl)
                else -> emptyList()
            }

            val playlist = Playlist(title, actualUrl)
            playlistDao.insert(playlist)

            merge(
                prev = streamDao.getByPlaylistUrl(url),
                streams = streams,
                strategy = strategy
            ) { value ->
                emit(Process.Loading(value.pt))
            }

            emitResource(Unit)
        } catch (e: FileNotFoundException) {
            error(context.getString(string.data_error_file_not_found))
        } catch (e: Exception) {
            logger.log(e)
            emitException(e)
        }
    }

    private suspend inline fun merge(
        prev: List<Stream>,
        streams: List<Stream>,
        @PlaylistStrategy strategy: Int,
        onProcess: (Int) -> Unit
    ) {
        val skippedUrls = mutableListOf<String>()
        val grouped by lazy {
            prev.groupBy { it.favourite }.withDefault { emptyList() }
        }
        val invalidate = when (strategy) {
            PlaylistStrategy.ALL -> prev
            PlaylistStrategy.SKIP_FAVORITE -> grouped.getValue(false)
            else -> emptyList()
        }
        invalidate.forEach { stream ->
            if (stream belong streams) {
                skippedUrls += stream.url
            } else {
                streamDao.deleteByUrl(stream.url)
            }
        }
        val existedUrls = when (strategy) {
            PlaylistStrategy.ALL -> skippedUrls
            PlaylistStrategy.SKIP_FAVORITE -> grouped
                .getValue(true)
                .map { it.url } + skippedUrls

            else -> emptyList()
        }
        var count = 0

        val needToBeInsertedstreams = streams.filterNot { it.url in existedUrls }
        val total = needToBeInsertedstreams.size

        needToBeInsertedstreams.forEach { stream ->
            streamDao.insert(stream)
            count++
            onProcess(count / total * 100)
        }
    }

    private suspend fun acquireNetwork(url: String): List<Stream> {
        val request = Request.Builder()
            .url(url)
            .build()
        val response = client.newCall(request).execute()
        if (!response.isSuccessful) return emptyList()
        val input = response.body?.byteStream()
        return input?.use { parse(url, it) } ?: emptyList()
    }

    private suspend fun acquireAndroid(url: String): List<Stream> {
        val uri = Uri.parse(url)
        val input = context.contentResolver.openInputStream(uri)
        return input?.use { parse(url, it) } ?: emptyList()
    }

    override fun observeAll(): Flow<List<Playlist>> = logger.execute {
        playlistDao.observeAll()
    } ?: flow { }

    override fun observe(url: String): Flow<Playlist?> = logger.execute {
        playlistDao.observeByUrl(url)
    } ?: flow { }

    override fun observeWithStreams(url: String): Flow<PlaylistWithStreams?> = logger.execute {
        playlistDao.observeByUrlWithStreams(url)
    } ?: flow {  }

    override suspend fun get(url: String): Playlist? = logger.execute {
        playlistDao.getByUrl(url)
    }

    override suspend fun unsubscribe(url: String): Playlist? = logger.execute {
        val playlist = playlistDao.getByUrl(url)
        streamDao.deleteByPlaylistUrl(url)
        playlist?.also {
            playlistDao.delete(it)
        }
    }

    private suspend fun parse(uri: String, input: InputStream): List<Stream> = logger.execute {
        parser.execute(input).map { it.toStream(uri) }
    } ?: emptyList()

    override suspend fun rename(url: String, target: String) = logger.sandBox {
        playlistDao.rename(url, target)
    }

    private val String.isNetworkUrl: Boolean get() = this.startsWithAny("http://", "https://")
    private val String.isAndroidUrl: Boolean get() = this.startsWithAny("file://", "content://")

    private suspend fun String.actualUrl(): String? {
        return if (isNetworkUrl) this
        else if (isAndroidUrl) {
            val uri = Uri.parse(this) ?: return null
            withContext(Dispatchers.IO) {
                val resolver = context.contentResolver
                val filename = uri.readFileName(resolver) ?: filenameWithTimezone
                val content = uri.readFileContent(resolver).orEmpty()
                val file = File(context.filesDir, filename)
                file.writeText(content)
                Uri.decode(file.toUri().toString())
            }
        } else null
    }

    private val filenameWithTimezone: String get() = "File_${System.currentTimeMillis()}"
}