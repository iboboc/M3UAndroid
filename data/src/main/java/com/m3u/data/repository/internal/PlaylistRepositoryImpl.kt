package com.m3u.data.repository.internal

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import androidx.core.net.toUri
import com.m3u.core.architecture.logger.Logger
import com.m3u.core.architecture.logger.execute
import com.m3u.core.architecture.logger.sandBox
import com.m3u.core.architecture.pref.annotation.PlaylistStrategy
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
import com.m3u.data.database.model.Playlist
import com.m3u.data.database.model.PlaylistWithStreams
import com.m3u.data.database.model.Stream
import com.m3u.data.repository.PlaylistRepository
import com.m3u.data.repository.parser.M3UPlaylistParser
import com.m3u.data.repository.parser.model.toStream
import com.m3u.data.work.BackupContracts
import com.m3u.i18n.R.string
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileNotFoundException
import java.io.InputStream
import java.io.Reader
import javax.inject.Inject

class PlaylistRepositoryImpl @Inject constructor(
    private val playlistDao: PlaylistDao,
    private val streamDao: StreamDao,
    @Logger.Message private val logger: Logger,
    private val client: OkHttpClient,
    @M3UPlaylistParser.Default private val parser: M3UPlaylistParser,
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
            val seen = Clock.System.now().toEpochMilliseconds()
            val streams = when {
                url.isNetworkUrl -> acquireNetwork(actualUrl, seen)
                url.isAndroidUrl -> acquireAndroid(actualUrl, seen)
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
        .flowOn(Dispatchers.IO)

    override suspend fun backup(uri: Uri): Unit = withContext(Dispatchers.IO) {
        val json = Json {
            prettyPrint = false
        }
        val all = playlistDao.getAllWithStreams()
        context.contentResolver.openOutputStream(uri)?.use {
            val writer = it.writer()
            writer.write("")
            all.forEach { (playlist, streams) ->
                val encodedPlaylist = json.encodeToString(playlist)
                val wrappedPlaylist = BackupContracts.wrapPlaylist(encodedPlaylist)
                writer.appendLine(wrappedPlaylist)
                streams.forEach { stream ->
                    val encodedStream = json.encodeToString(stream)
                    val wrappedStream = BackupContracts.wrapStream(encodedStream)
                    writer.appendLine(wrappedStream)
                }
            }
        }
    }

    override suspend fun restore(uri: Uri): Unit = withContext(Dispatchers.IO) {
        val json = Json {
            ignoreUnknownKeys = true
        }
        context.contentResolver.openInputStream(uri)?.use {
            val reader = it.reader()

            reader.forEachLine { line ->
                if (line.isBlank()) return@forEachLine
                val encodedPlaylist = BackupContracts.unwrapPlaylist(line)
                val encodedStream = BackupContracts.unwrapStream(line)
                when {
                    encodedPlaylist != null -> {
                        val playlist = json.decodeFromString<Playlist>(encodedPlaylist)
                        playlistDao.insert(playlist)
                    }
                    encodedStream != null -> {
                        val stream = json.decodeFromString<Stream>(encodedStream)
                        streamDao.insert(stream)
                    }
                    else -> {}
                }
            }
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

        val needToBeInsertedStreams = streams.filterNot { it.url in existedUrls }
        val total = needToBeInsertedStreams.size

        needToBeInsertedStreams.forEach { stream ->
            streamDao.insert(stream)
            count++
            onProcess(count / total * 100)
        }
    }

    private suspend fun acquireNetwork(url: String, seen: Long): List<Stream> {
        val request = Request.Builder()
            .url(url)
            .build()
        val response = withContext(Dispatchers.IO) {
            client.newCall(request).execute()
        }
        if (!response.isSuccessful) return emptyList()
        val input = response.body?.byteStream()
        return input?.use { parse(url, seen, it) } ?: emptyList()
    }

    private suspend fun acquireAndroid(url: String, seen: Long): List<Stream> {
        val uri = Uri.parse(url)
        val input = context.contentResolver.openInputStream(uri)
        return input?.use { parse(url, seen, it) } ?: emptyList()
    }

    override fun observeAll(): Flow<List<Playlist>> = logger.execute {
        playlistDao.observeAll()
    } ?: flow { }

    override fun observe(url: String): Flow<Playlist?> = logger.execute {
        playlistDao.observeByUrl(url)
    } ?: flow { }

    override fun observeWithStreams(url: String): Flow<PlaylistWithStreams?> = logger.execute {
        playlistDao.observeByUrlWithStreams(url)
    } ?: flow { }

    override suspend fun getWithStreams(url: String): PlaylistWithStreams? = logger.execute {
        playlistDao.getByUrlWithStreams(url)
    }

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

    private suspend fun parse(
        playlistUrl: String,
        seen: Long,
        input: InputStream
    ): List<Stream> = logger.execute {
        parser.execute(input).map { it.toStream(playlistUrl, 0L) }
    } ?: emptyList()

    override suspend fun rename(url: String, target: String) = logger.sandBox {
        playlistDao.rename(url, target)
    }

    private val String.isNetworkUrl: Boolean
        get() = this.startsWithAny(
            "http://",
            "https://",
            ignoreCase = true
        )
    private val String.isAndroidUrl: Boolean
        get() = this.startsWithAny(
            ContentResolver.SCHEME_FILE,
            ContentResolver.SCHEME_CONTENT,
            ignoreCase = true
        )

    private suspend fun String.actualUrl(): String? {
        return if (isNetworkUrl) this
        else if (isAndroidUrl) {
            val uri = Uri.parse(this) ?: return null
            if (uri.scheme == ContentResolver.SCHEME_FILE) {
                return uri.toString()
            }
            withContext(Dispatchers.IO) {
                val resolver = context.contentResolver
                val filename = uri.readFileName(resolver) ?: filenameWithTimezone
                val content = uri.readFileContent(resolver).orEmpty()
                val file = File(context.filesDir, filename)
                file.writeText(content)

                val newUrl = Uri.decode(file.toUri().toString())
                playlistDao.updateUrl(this@actualUrl, newUrl)
                newUrl
            }
        } else null
    }

    private val filenameWithTimezone: String get() = "File_${System.currentTimeMillis()}"

    // Modified with `inline`
    private inline fun Reader.forEachLine(action: (String) -> Unit): Unit = useLines { it.forEach(action) }
}