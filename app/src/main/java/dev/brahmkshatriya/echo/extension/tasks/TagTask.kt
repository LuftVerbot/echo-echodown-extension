package dev.brahmkshatriya.echo.extension.tasks

import android.annotation.SuppressLint
import dev.brahmkshatriya.echo.common.Extension
import dev.brahmkshatriya.echo.common.LyricsExtension
import dev.brahmkshatriya.echo.common.MusicExtension
import dev.brahmkshatriya.echo.common.clients.LyricsClient
import dev.brahmkshatriya.echo.common.helpers.FileProgress
import dev.brahmkshatriya.echo.common.helpers.FileTask
import dev.brahmkshatriya.echo.common.helpers.PagedData
import dev.brahmkshatriya.echo.common.helpers.Progress
import dev.brahmkshatriya.echo.common.helpers.SuspendedFunction
import dev.brahmkshatriya.echo.common.models.DownloadContext
import dev.brahmkshatriya.echo.common.models.ImageHolder
import dev.brahmkshatriya.echo.common.models.Lyrics
import dev.brahmkshatriya.echo.common.models.Track
import dev.brahmkshatriya.echo.common.settings.Settings
import dev.brahmkshatriya.echo.extension.Downloader
import dev.brahmkshatriya.echo.extension.EDExtension.Companion.get
import dev.brahmkshatriya.echo.extension.EDExtension.Companion.getExtension
import dev.brahmkshatriya.echo.extension.FFMpegHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import org.jaudiotagger.tag.TagOptionSingleton
import org.jaudiotagger.tag.images.ArtworkFactory
import java.io.File

class TagTask(
    private val context: DownloadContext,
    private val file: File,
    private val settings: Settings,
    private val musicExtensions: List<MusicExtension>,
    private val lyricsExtensionList: List<LyricsExtension>
) : FileTask {

    override val progressFlow = MutableSharedFlow<FileProgress>()

    private var job: Job? = null
    override val start = SuspendedFunction {
        progressFlow.emit(Progress.Initialized(3))
        progressFlow.emit(Progress.InProgress(0, null))
        job = launch(Dispatchers.IO) {
            val lyrics = getActualLyrics()
            progressFlow.emit(Progress.InProgress(1, null))

            val coverFile = runCatching { saveCoverBitmap(file, context.track) }.getOrNull()
            progressFlow.emit(Progress.InProgress(2, null))

            writeTags(file, context.track, coverFile, lyrics)
            progressFlow.emit(Progress.Final.Completed(3, file))
        }
    }

    private val illegalChars = "[/\\\\:*?\"<>|]".toRegex()

    @SuppressLint("DefaultLocale")
    private suspend fun writeTags(
        file: File, track: Track, coverFile: File?, lyrics: Lyrics?, isVideo: Boolean = false
    ) {
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
        runCatching {
            if (fileExtension == "mp3" || fileExtension == "m4a" && !isVideo) {
                jAudioTagger(file, track, coverFile, lyricsText)
            } else {
                ffmpegTag(file, track, coverFile, lyricsText, fileExtension, isVideo)
            }
        }.getOrElse { e ->
            when ("Video file") {
                in e.toString() -> {
                    writeTags(file, track, coverFile, lyrics, true)
                }

                else -> {
                    coverFile?.delete()
                    throw e
                }
            }
        }
    }

    private fun jAudioTagger(
        file: File,
        track: Track,
        coverFile: File?,
        lyricsText: String
    ) {
        TagOptionSingleton.getInstance().isAndroid = true

        val audioFile = AudioFileIO.read(file)
        val tag = audioFile.tagOrCreateAndSetDefault

        tag.setField(FieldKey.TRACK, (context.sortOrder ?: 0).toString())
        tag.setField(FieldKey.TITLE, illegalChars.replace(track.title, "_"))
        tag.setField(FieldKey.ARTIST, track.artists.joinToString(", ") { it.name })
        tag.setField(
            FieldKey.ALBUM,
            illegalChars.replace(track.album?.title.orEmpty(), "_")
        )
        tag.setField(FieldKey.LYRICS, lyricsText)

        coverFile?.let {
            val artwork = ArtworkFactory.createArtworkFromFile(it)
            tag.deleteArtworkField()
            tag.addField(artwork)
        }

        AudioFileIO.write(audioFile)
        coverFile?.delete()
    }

    private suspend fun ffmpegTag(
        file: File,
        track: Track,
        coverFile: File?,
        lyricsText: String,
        fileExtension: String,
        isVideo: Boolean
    ) {
        val mp4File = if(fileExtension == "m4a" && isVideo) {
            MergeTask.getUniqueFile(File(file.parent.orEmpty()), file.name.substringBefore("."), "mp4", file)
        } else {
            null
        }

        val outputFile = if(mp4File != null) File(mp4File.parent, "temp_${mp4File.name}")
           else File(file.parent, "temp_${file.name}")

        val metadataOrder = "track=\"${context.sortOrder ?: 0}\""
        val metadataTitle = "title=\"${illegalChars.replace(track.title, "_")}\""
        val metadataArtist = "artist=\"${track.artists.joinToString(", ") { it.name }}\""
        val metadataAlbum =
            "album=\"${illegalChars.replace(track.album?.title.orEmpty(), "_")}\""

        val metadataCoverTitle = "title=\"Album cover\""
        val metadataCoverComment = "comment=\"Cover (front)\""

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
            append("-metadata $metadataOrder ")
            append("-metadata $metadataTitle ")
            append("-metadata $metadataArtist ")
            append("-metadata $metadataAlbum ")
            append("-metadata lyrics=\"${lyricsText.replace("\"", "'")}\" ")
            if (isVideo) {
                append("-metadata:s:v:1 $metadataCoverTitle ")
                append("-metadata:s:v:1 $metadataCoverComment ")
                append("-f mp4 ")
            } else {
                append("-metadata:s:v $metadataCoverTitle ")
                append("-metadata:s:v $metadataCoverComment ")
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
    }

    private suspend fun saveCoverBitmap(file: File, track: Track): File? {
        val coverFile = File(file.parent, "cover_temp.jpeg")
        if (coverFile.exists() && !coverFile.delete()) return null
        return runCatching {
            val holder = track.cover as? ImageHolder.UrlRequestImageHolder
                ?: throw IllegalArgumentException("Invalid ImageHolder type")
            Downloader.okHttpDownload(coverFile, holder.request, false)
        }.getOrElse {
            it.printStackTrace()
            coverFile.delete()
            null
        }
    }

    private suspend fun getActualLyrics() : Lyrics? {
        if (settings.getBoolean(DOWNLOAD_LYRICS) == false) return null
        val extension = musicExtensions.getExtension(context.extensionId) ?: return null
        val extensionLyrics = getLyrics(extension, context.track, context.extensionId)
        if (extensionLyrics != null &&
            (extensionLyrics.lyrics is Lyrics.Timed || extensionLyrics.lyrics is Lyrics.Simple)
            && settings.getBoolean(SYNC_LYRICS) == false) return extensionLyrics
        val lyricsExtension = lyricsExtensionList.getExtension(settings.getString(FALLBACK_LYRICS_EXT))
            ?: return null
        val lyrics = getLyrics(lyricsExtension, context.track, context.extensionId)
        if (lyrics != null && lyrics.lyrics is Lyrics.Timed) return lyrics
        return extensionLyrics
    }

    private suspend fun getLyrics(
        extension: Extension<*>,
        track: Track,
        clientId: String
    ): Lyrics? {
        val data = extension.get<LyricsClient, PagedData<Lyrics>> {
            searchTrackLyrics(clientId, track)
        }
        val value = data.getOrNull()?.loadFirst()?.firstOrNull()
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

    override val cancel = SuspendedFunction {
        job?.cancel()
        job = null
        progressFlow.emit(Progress.Final.Cancelled())
    }
    override val pause: SuspendedFunction? = null
    override val resume: SuspendedFunction? = null

    companion object {
        const val FALLBACK_LYRICS_EXT = "fallback_lyrics_ext"
        const val DOWNLOAD_LYRICS = "download_lyrics"
        const val SYNC_LYRICS = "synced_lyrics"
    }
}