package dev.brahmkshatriya.echo.extension.downloaders

import dev.brahmkshatriya.echo.common.models.Progress
import dev.brahmkshatriya.echo.extension.Downloader.download
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File
import java.io.InputStream

class InputStreamDownload {
    private val receiveFlow = MutableStateFlow(0L)
    private var append = false

    suspend fun inputStreamDownload(
        file: File,
        progressFlow: MutableStateFlow<Progress>,
        stream: InputStream,
        totalBytes: Long
    ): File {
        runCatching {
            download(file, stream, totalBytes, append, progressFlow, receiveFlow)
        }.getOrElse {
            file.delete()
        }
        return file
    }
}