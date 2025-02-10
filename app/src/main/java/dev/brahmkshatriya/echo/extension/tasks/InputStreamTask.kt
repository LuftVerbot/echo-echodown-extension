package dev.brahmkshatriya.echo.extension.tasks

import dev.brahmkshatriya.echo.common.helpers.FileProgress
import dev.brahmkshatriya.echo.common.helpers.FileTask
import dev.brahmkshatriya.echo.common.helpers.Progress
import dev.brahmkshatriya.echo.common.helpers.SuspendedFunction
import dev.brahmkshatriya.echo.extension.Downloader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.io.InputStream

class InputStreamTask(
    private val file: File,
    private val stream: InputStream,
    private val totalBytes: Long
) : FileTask {

    override val progressFlow = MutableSharedFlow<FileProgress>()

    private var job: Job? = null
    private val receiveFlow = MutableStateFlow(0L)
    private var append = false

    override val start = SuspendedFunction {
        job?.cancel()
        job = launch(Dispatchers.IO) {
            val file = runCatching {
                if (file.exists()) {
                    val length = file.length()
                    receiveFlow.value = length
                    stream.skip(length)
                }
                Downloader.download(file, stream, totalBytes, append, progressFlow, receiveFlow)
            }.getOrElse {
                file.delete()
                job?.cancel()
                job = null
                return@launch progressFlow.emit(Progress.Final.Failed(it))
            }
            progressFlow.emit(Progress.Final.Completed(file.length(), file))
        }
    }

    override val cancel = SuspendedFunction {
        job?.cancel()
        job = null
        file.delete()
        progressFlow.emit(Progress.Final.Cancelled())
    }

    override val pause = SuspendedFunction {
        job?.cancel()
        job = null
        append = true
        progressFlow.emit(Progress.Paused(receiveFlow.value))
    }
    override val resume = start
}