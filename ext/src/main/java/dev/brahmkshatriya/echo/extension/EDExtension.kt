package dev.brahmkshatriya.echo.extension

import dev.brahmkshatriya.echo.common.Extension
import dev.brahmkshatriya.echo.common.MusicExtension
import dev.brahmkshatriya.echo.common.clients.AlbumClient
import dev.brahmkshatriya.echo.common.clients.DownloadClient
import dev.brahmkshatriya.echo.common.clients.PlaylistClient
import dev.brahmkshatriya.echo.common.clients.RadioClient
import dev.brahmkshatriya.echo.common.helpers.ClientException
import dev.brahmkshatriya.echo.common.models.DownloadContext
import dev.brahmkshatriya.echo.common.models.EchoMediaItem
import dev.brahmkshatriya.echo.common.models.Track
import dev.brahmkshatriya.echo.common.providers.MusicExtensionsProvider

abstract class EDExtension : DownloadClient, MusicExtensionsProvider {
    override val requiredMusicExtensions = listOf<String>()

    var musicExtensionList: List<MusicExtension> = emptyList()
    override fun setMusicExtensions(extensions: List<MusicExtension>) {
        musicExtensionList = extensions
    }

    companion object {
        fun List<Extension<*>>.getExtension(id: String?) = firstOrNull { it.id == id }

        suspend inline fun <reified T, R> Extension<*>.get(block: T.() -> R) = runCatching {
            val instance = instance.value().getOrThrow()
            if (instance !is T) throw ClientException.NotSupported("$name Extension: ${T::class.simpleName}")
            block.invoke(instance)
        }
    }

    override suspend fun getDownloadTracks(
        extensionId: String, item: EchoMediaItem
    ) = when (item) {
        is EchoMediaItem.TrackItem -> listOf(DownloadContext(extensionId, item.track))
        is EchoMediaItem.Lists -> {
            val ext = musicExtensionList.getExtension(extensionId)!!
            val tracks = when (item) {
                is EchoMediaItem.Lists.AlbumItem -> ext.get<AlbumClient, List<Track>> {
                    val album = loadAlbum(item.album)
                    loadTracks(album).loadAll()
                }

                is EchoMediaItem.Lists.PlaylistItem -> ext.get<PlaylistClient, List<Track>> {
                    val album = loadPlaylist(item.playlist)
                    loadTracks(album).loadAll()
                }

                is EchoMediaItem.Lists.RadioItem -> ext.get<RadioClient, List<Track>> {
                    loadTracks(item.radio).loadAll()
                }
            }.getOrThrow()
            tracks.mapIndexed { index, track ->
                DownloadContext(extensionId, track, index, item)
            }
        }

        else -> listOf()
    }
}