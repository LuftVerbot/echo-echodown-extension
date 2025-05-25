package dev.brahmkshatriya.echo.extension.tasks

import android.annotation.SuppressLint
import android.util.LruCache
import dev.brahmkshatriya.echo.common.Extension
import dev.brahmkshatriya.echo.common.clients.AlbumClient
import dev.brahmkshatriya.echo.common.clients.LyricsClient
import dev.brahmkshatriya.echo.common.helpers.PagedData
import dev.brahmkshatriya.echo.common.models.Album
import dev.brahmkshatriya.echo.common.models.DownloadContext
import dev.brahmkshatriya.echo.common.models.ImageHolder
import dev.brahmkshatriya.echo.common.models.Lyrics
import dev.brahmkshatriya.echo.common.models.Progress
import dev.brahmkshatriya.echo.common.models.Track
import dev.brahmkshatriya.echo.extension.AndroidED
import dev.brahmkshatriya.echo.extension.AndroidED.Companion.illegalChars
import dev.brahmkshatriya.echo.extension.Downloader.okHttpDownload
import dev.brahmkshatriya.echo.extension.EDExtension.Companion.get
import dev.brahmkshatriya.echo.extension.EDExtension.Companion.getExtension
import dev.brahmkshatriya.echo.extension.FFMpegHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.withContext
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import org.jaudiotagger.tag.TagOptionSingleton
import org.jaudiotagger.tag.images.ArtworkFactory
import java.io.File

class Tag(
    private val androidED: AndroidED
) {
    private val musicExtensions
        get() = androidED.musicExtensionList

    private val lyricsExtensions
        get() = androidED.lyricsExtensionList

    suspend fun tag(
        progressFlow: MutableSharedFlow<Progress>,
        context: DownloadContext,
        file: File,
        downloadsDir: File
    ): File = withContext(Dispatchers.IO) {
        val finalFile = runCatching {
            val track = context.track

            val albumKey = "${context.extensionId}:${track.album?.id}"
            val cachedAlbum = albumCache.get(albumKey)
            val album = cachedAlbum ?: run {
                val albumExt = musicExtensions.getExtension(context.extensionId)
                val loaded = async { loadAlbum(albumExt, track) }.await()
                if (loaded != null) albumCache.put(albumKey, loaded)
                loaded
            }
            progressFlow.emit(Progress(4, progress = 1))

            val coverFile = async { saveCoverBitmap(file, context.track) }.await()
            progressFlow.emit(Progress(4, progress = 2))

            val lyrics = async {
                androidED.run {
                    getActualLyrics(context, downLyrics, syncLyrics, downFallbackLyrics)
                }
            }.await()
            progressFlow.emit(Progress(4, progress = 3))

            writeTags(
                file,
                context,
                track,
                coverFile,
                lyrics,
                downloadsDir,
                androidED.folderStructure,
                album
            )
        }.getOrElse {
            throw it
        }
        progressFlow.emit(Progress(4, progress = 4))
        finalFile
    }

    @SuppressLint("DefaultLocale")
    private suspend fun writeTags(
        file: File,
        context: DownloadContext,
        track: Track,
        coverFile: File?,
        lyrics: Lyrics?,
        downloadsDir: File,
        folderStructure: String,
        album: Album?,
        isVideo: Boolean = false
    ): File = withContext(Dispatchers.IO) {
        fun formatTime(millis: Long): String {
            val mm = millis / 60000
            val remainder = millis % 60000
            val ss = remainder / 1000
            val ms = remainder % 1000

            val hundredths = (ms / 10)
            return String.format("[%02d:%02d.%02d]", mm, ss, hundredths)
        }

        val lyricsText = when (val lyric = lyrics?.lyrics) {
            is Lyrics.Timed -> {
                lyric.list.joinToString("\n") { item ->
                    "${formatTime(item.startTime)}${item.text}"
                }
            }

            is Lyrics.Simple -> {
                lyric.text
            }

            null -> {
                ""
            }
        }

        val fileExtension = file.extension.lowercase()
        println("File extension: $fileExtension")

        val finalFile = runCatching {
           val preFile = if (fileExtension == "mp3" || fileExtension == "m4a" && !isVideo) {
                jAudioTagger(file, context, track, coverFile, lyricsText, album)
            } else {
                ffmpegTag(file, context, track, coverFile, lyricsText, fileExtension, album, isVideo)
            }
            rename(context, folderStructure, downloadsDir, preFile)
        }.getOrElse { e ->
            when ("Video file") {
                in e.toString() -> {
                    writeTags(file, context, track, coverFile, lyrics, downloadsDir, folderStructure, album,true)
                }

                else -> {
                    coverFile?.delete()
                    throw e
                }
            }
        }
        finalFile
    }

    private fun rename(
        context: DownloadContext,
        folderStructure: String,
        downloadsDir: File,
        preFile: File
    ): File {
        val parentFolderName = context.context?.title
        val sanitizedParent = illegalChars.replace(parentFolderName.orEmpty(), "_")
        val folder = if (sanitizedParent.isNotBlank()) "$folderStructure$sanitizedParent" else "Echo/"
        val targetDirectory = File(downloadsDir, folder).apply { mkdirs() }
        val targetFile = File(targetDirectory, preFile.name)
        preFile.copyTo(targetFile)
        preFile.delete()
        return targetFile
    }

    private val tagOptionSingleton by lazy { TagOptionSingleton.getInstance().isAndroid = true }

    private suspend fun jAudioTagger(
        file: File,
        context: DownloadContext,
        track: Track,
        coverFile: File?,
        lyricsText: String,
        album: Album?
    ): File = withContext(Dispatchers.IO) {
        tagOptionSingleton

        val audioFile = AudioFileIO.read(file)
        val tag = audioFile.tagOrCreateAndSetDefault

        tag.setField(FieldKey.TRACK, (context.sortOrder ?: 0).toString())
        tag.setField(FieldKey.TITLE, illegalChars.replace(track.title, "_"))
        tag.setField(FieldKey.ARTIST, track.artists.joinToString(", ") { it.name })
        tag.setField(FieldKey.ALBUM_ARTIST, album?.artists.orEmpty().joinToString(", ") { it.name })
        tag.setField(FieldKey.ALBUM, illegalChars.replace(track.album?.title.orEmpty(), "_"))
        tag.setField(FieldKey.YEAR, track.releaseDate.toString())
        tag.setField(FieldKey.LYRICS, lyricsText)

        coverFile?.let {
            val artwork = ArtworkFactory.createArtworkFromFile(it)
            tag.deleteArtworkField()
            tag.addField(artwork)
        }

        AudioFileIO.write(audioFile)
        coverFile?.delete()
        file
    }

    private suspend fun ffmpegTag(
        file: File,
        context: DownloadContext,
        track: Track,
        coverFile: File?,
        lyricsText: String,
        fileExtension: String,
        album: Album?,
        isVideo: Boolean
    ): File {
        val mp4File = if (fileExtension == "m4a" && isVideo) {
            Merge.getUniqueFile(file.parentFile!!, file.name.substringBefore("."), "mp4", file)
        } else {
            null
        }

        val outputFile = if(mp4File != null) File(mp4File.parent, "temp_${mp4File.name}")
           else File(file.parent, "temp_${file.name}")

        val mdOrder = "track=\"${context.sortOrder ?: 0}\""
        val mdTitle = "title=\"${illegalChars.replace(track.title, "_")}\""
        val mdArtist = "artist=\"${track.artists.joinToString(", ") { it.name }}\""
        val mdAlbumArtist = "albumartist=\"${album?.artists.orEmpty().joinToString(", ") { it.name }}\""
        val mdAlbumYear = "year=\"${track.releaseDate}\""
        val mdAlbum = "album=\"${illegalChars.replace(track.album?.title.orEmpty(), "_")}\""

        val mdCoverTitle = "title=\"Album cover\""
        val medCoverComment = "comment=\"Cover (front)\""

        val cmd = buildString {
            if (mp4File != null) {
                append("-i \"${mp4File.absolutePath}\" ")
            } else {
                append("-i \"${file.absolutePath}\" ")
            }
            append("-i \"${coverFile?.absolutePath}\" ")
            if (isVideo) {
                append("-map 0 ")
                append("-map 1 ")
                append("-c copy ")
                append("-c:v:1 mjpeg ")
            } else {
                append("-c copy ")
                append("-c:v mjpeg ")
            }
            append("-metadata $mdOrder ")
            append("-metadata $mdTitle ")
            append("-metadata $mdArtist ")
            append("-metadata $mdAlbum ")
            append("-metadata $mdAlbumYear ")
            append("-metadata $mdAlbumArtist ")
            append("-metadata lyrics=\"${lyricsText.replace("\"", "'")}\" ")
            if (isVideo) {
                append("-metadata:s:v:1 $mdCoverTitle ")
                append("-metadata:s:v:1 $medCoverComment ")
                append("-f mp4 ")
            } else {
                append("-metadata:s:v $mdCoverTitle ")
                append("-metadata:s:v $medCoverComment ")
                append("-disposition:v attached_pic ")
            }
            append("\"${outputFile.absolutePath}\"")
        }

        FFMpegHelper.execute(cmd)
        if (mp4File != null) {
            if (mp4File.delete()) outputFile.renameTo(mp4File)
        } else {
            if (file.delete()) outputFile.renameTo(file)
        }

        coverFile?.delete()
        return mp4File ?: file
    }

    private suspend fun loadAlbum(
        extension: Extension<*>?,
        track: Track
    ): Album? {
        return extension?.get<AlbumClient, Album?> {
            track.album?.let { loadAlbum(it) }
        }?.getOrNull() ?: track.album
    }

    private suspend fun saveCoverBitmap(file: File, track: Track): File? {
        val coverFile = File(file.parent, "cover_temp_${track.hashCode()}.jpeg")
        if (coverFile.exists() && !coverFile.delete()) return null
        return runCatching {
            val holder = track.cover as? ImageHolder.UrlRequestImageHolder
                ?: throw IllegalArgumentException("Invalid ImageHolder type")
            okHttpDownload(coverFile, holder.request, false)
        }.getOrElse {
            it.printStackTrace()
            coverFile.delete()
            null
        }
    }

    private suspend fun getActualLyrics(
        context: DownloadContext,
        downLyrics: Boolean,
        syncLyrics: Boolean,
        downFallbackLyrics: String
    ) : Lyrics? {
        try {
            if (!downLyrics) return null
            val extension = musicExtensions.getExtension(context.extensionId) ?: return null
            val extensionLyrics = getLyrics(extension, context.track, context.extensionId)
            if (extensionLyrics != null &&
                (extensionLyrics.lyrics is Lyrics.Timed || extensionLyrics.lyrics is Lyrics.Simple)
                && (!syncLyrics || lyricsExtensions.isEmpty())
            ) return extensionLyrics
            val lyricsExtension =
                lyricsExtensions.getExtension(downFallbackLyrics)
                    ?: return null
            val lyrics = getLyrics(lyricsExtension, context.track, context.extensionId)
            if (lyrics != null && lyrics.lyrics is Lyrics.Timed) return lyrics
            return extensionLyrics
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        }
    }

    private suspend fun getLyrics(
        extension: Extension<*>,
        track: Track,
        clientId: String
    ): Lyrics? {
        val data = extension.get<LyricsClient, PagedData<Lyrics>> {
            searchTrackLyrics(clientId, track)
        }
        val value = data.getOrNull()?.loadAll()?.firstOrNull()
        return if (value != null) {
            extension.get<LyricsClient, Lyrics> {
                loadLyrics(value)
            }.getOrNull()?.fillGaps()
        } else {
            null
        }
    }

    private fun Lyrics.fillGaps(): Lyrics {
        val lyrics = this.lyrics as? Lyrics.Timed
        return if (lyrics != null && lyrics.fillTimeGaps) {
            val new = mutableListOf<Lyrics.Item>()
            var last = 0L
            lyrics.list.forEach {
                if (it.startTime > last) {
                    new.add(Lyrics.Item("", last, it.startTime))
                }
                new.add(it)
                last = it.endTime
            }
            this.copy(lyrics = Lyrics.Timed(new))
        } else this
    }

    companion object {
        private val albumCache = LruCache<String, Album>(50)
    }
}