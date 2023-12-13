@file:Suppress("unused")

package com.m3u.data.di

import com.m3u.data.parser.M3UPlaylistParser
import com.m3u.data.parser.VersionCatalogParser
import com.m3u.data.parser.impl.BjoernPetersenM3UPlaylistParser
import com.m3u.data.parser.impl.DefaultM3UPlaylistParser
import com.m3u.data.parser.impl.VersionCatalogParserImpl
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
interface ParserModule {
    @Binds
    @M3UPlaylistParser.Default
    fun bindM3UPlaylistParser(parser: DefaultM3UPlaylistParser): M3UPlaylistParser

    @Binds
    @M3UPlaylistParser.BjoernPetersen
    fun bindBjoernPetersenM3UPlaylistParser(parser: BjoernPetersenM3UPlaylistParser): M3UPlaylistParser

    @Binds
    fun bindVersionCatalogParser(parser: VersionCatalogParserImpl): VersionCatalogParser
}
