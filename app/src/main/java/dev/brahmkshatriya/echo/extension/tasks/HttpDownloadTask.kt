package dev.brahmkshatriya.echo.extension.tasks

import dev.brahmkshatriya.echo.common.helpers.FileProgress
import dev.brahmkshatriya.echo.common.helpers.FileTask
import dev.brahmkshatriya.echo.common.helpers.Progress
import dev.brahmkshatriya.echo.common.helpers.SuspendedFunction
import dev.brahmkshatriya.echo.common.models.Streamable
import dev.brahmkshatriya.echo.extension.Downloader
import dev.brahmkshatriya.echo.extension.FFMpegHelper
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.io.File

class HttpDownloadTask(
    private val file: File,
    private val source: Streamable.Source.Http
) : FileTask {

    private val isFFmpeg = source.type != Streamable.SourceType.Progressive

    override val progressFlow = MutableSharedFlow<FileProgress>()

    private var job: Job? = null
    private var append = false

    override val start = SuspendedFunction {
        job?.cancel()
        job = launch {
            val result = if (isFFmpeg) ffmpegDownload() else okHttpDownload()
            val file = result.getOrElse {
                file.delete()
                progressFlow.emit(Progress.Final.Failed(it))
                return@launch
            }
            progressFlow.emit(Progress.Final.Completed(file.length(), file))
        }
    }

    private suspend fun ffmpegDownload(): Result<File> = runCatching {
        if (file.exists()) file.delete()

        val headers = source.request.headers.entries
            .joinToString("\r\n") { "${it.key}: ${it.value}" }

        val ffmpegCommand = buildString {
            if (headers.isNotEmpty()) append("-headers \"$headers\" ")
            append("-i \"${source.request.url}\" ")
            append("-c copy ")
            append("\"${file.absolutePath}\"")
        }
        progressFlow.emit(Progress.Initialized(null))
        var nextIsDuration = false
        FFMpegHelper.execute(ffmpegCommand, {
            it ?: return@execute
            if (nextIsDuration) {
                val duration = durationToTime(it)
                progressFlow.emit(Progress.Initialized(duration))
            }
            nextIsDuration = it.contains("Duration:")
        }) {
            progressFlow.emit(Progress.InProgress(it.time.toLong(), it.bitrate.toLong()))
        }
        file
    }

    private val durationRegex = Regex("(\\d{2}):(\\d{2}):(\\d{2}).(\\d{2})")
    private fun durationToTime(it: String): Long? {
        val match = durationRegex.find(it) ?: return null
        val (hours, minutes, seconds, millis) = match.destructured
        return (hours.toLong() * 3600 + minutes.toLong() * 60 + seconds.toLong()) * 1000 + millis.toLong()
    }

    override val cancel = SuspendedFunction {
        job?.cancel()
        job = null
        progressFlow.emit(Progress.Final.Cancelled())
    }

    private val receiveFlow = MutableStateFlow(0L)
    private suspend fun okHttpDownload() = runCatching {
        Downloader.okHttpDownload(file, source.request, append, progressFlow, receiveFlow)
    }


    override val pause
        get() = if (!isFFmpeg) SuspendedFunction {
            job?.cancel()
            job = null
            append = true
            progressFlow.emit(Progress.Paused(receiveFlow.value))
        } else null

    override val resume
        get() = if (!isFFmpeg) SuspendedFunction {
            start()
        } else null
}