package dev.brahmkshatriya.echo.extension.downloaders

import dev.brahmkshatriya.echo.common.models.Progress
import dev.brahmkshatriya.echo.common.models.Streamable
import dev.brahmkshatriya.echo.extension.Downloader.okHttpDownload
import dev.brahmkshatriya.echo.extension.FFMpegHelper
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File

class HttpDownload {
    private val receiveFlow = MutableStateFlow(0L)
    private var append = false

    suspend fun httpDownload(
        file: File,
        progressFlow: MutableStateFlow<Progress>,
        source: Streamable.Source.Http,
    ): File {
        val isFFmpeg = source.type != Streamable.SourceType.Progressive
        val result = if (isFFmpeg) ffmpegDownload(file, source, progressFlow) else okHttpDownload(file, source, progressFlow)
        result.getOrElse {
            file.delete()
        }
        return file
    }

    private suspend fun ffmpegDownload(
        file: File,
        source: Streamable.Source.Http,
        progressFlow: MutableStateFlow<Progress>
    ): Result<File> = runCatching {
        if (file.exists()) file.delete()

        val headers = source.request.headers.entries.takeIf { it.isNotEmpty() }
            ?.joinToString("\r\n") { "${it.key}: ${it.value}" }.orEmpty()

        val ffmpegCommand = buildString {
            if (headers.isNotEmpty()) append("-headers \"$headers\" ")
            append("-i \"${source.request.url}\" ")
            append("-c copy ")
            append("\"${file.absolutePath}\"")
        }
        progressFlow.emit(Progress())
        var nextIsDuration = false
        FFMpegHelper.execute(ffmpegCommand, {
            it ?: return@execute
            if (nextIsDuration) {
                val duration = durationToTime(it) ?: 0L
                progressFlow.emit(Progress(progress =  duration))
            }
            nextIsDuration = it.contains("Duration:")
        }) {
            progressFlow.emit(Progress(progress =  it.time.toLong(), speed = it.bitrate.toLong()))
        }
        file
    }

    private fun durationToTime(it: String): Long? {
        val match = DURATION_REGEX.find(it) ?: return null
        val (hours, minutes, seconds, millis) = match.destructured
        return (hours.toLong() * 3600 + minutes.toLong() * 60 + seconds.toLong()) * 1000 + millis.toLong()
    }

    private suspend fun okHttpDownload(
        file: File,
        source: Streamable.Source.Http,
        progressFlow: MutableStateFlow<Progress>
    ) = runCatching {
        okHttpDownload(file, source.request, append, progressFlow, receiveFlow)
    }

    companion object {
        private val DURATION_REGEX = Regex("(\\d{2}):(\\d{2}):(\\d{2})\\.(\\d{2})")
    }
}