package dev.brahmkshatriya.echo.extension

import dev.brahmkshatriya.echo.common.helpers.FileProgress
import dev.brahmkshatriya.echo.common.helpers.Progress
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Headers.Companion.toHeaders
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import kotlin.coroutines.resume
import dev.brahmkshatriya.echo.common.models.Request as EchoRequest

object Downloader {

    suspend fun download(
        file: File,
        stream: InputStream,
        totalBytes: Long,
        append: Boolean,
        progressFlow: MutableSharedFlow<FileProgress>? = null,
        receiveFlow: MutableStateFlow<Long>? = null,
    ) = coroutineScope {
        progressFlow?.emit(Progress.Initialized(totalBytes.takeIf { it > 0 }))
        suspendCancellableCoroutine { cont ->
            var last = 0L to System.currentTimeMillis()
            var speed = 0L
            val bis = stream.buffered()
            val fos = FileOutputStream(file, append)

            var cancelled = false
            cont.invokeOnCancellation {
                cancelled = true
                bis.close()
                fos.close()
            }

            var received: Long = 0
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var bytes = bis.read(buffer)
            while (bytes >= 0 && !cancelled) {
                fos.write(buffer, 0, bytes)
                received += bytes
                receiveFlow?.value = received
                if (System.currentTimeMillis() - last.second > 1000) {
                    speed = received - last.first
                    last = received to System.currentTimeMillis()
                }
                runBlocking {
                    progressFlow?.emit(Progress.InProgress(received, speed))
                }
                bytes = bis.read(buffer)
            }

            cont.resume(file)
        }
    }

    val client = OkHttpClient.Builder().build()

    suspend fun okHttpDownload(
        file: File,
        req: EchoRequest,
        append: Boolean,
        progressFlow: MutableSharedFlow<FileProgress>? = null,
        receiveFlow: MutableStateFlow<Long>? = null,
    ): File {
        val fileLength = file.length()
        receiveFlow?.value = fileLength
        val headers = (req.headers + mapOf("Range" to "bytes=${fileLength}-")).toHeaders()
        val request = Request.Builder().url(req.url).headers(headers).build()
        val response = client.newCall(request).execute()

        val totalBytes = fileLength + response.body.contentLength()
        return download(
            file,
            response.body.byteStream(),
            totalBytes,
            append,
            progressFlow,
            receiveFlow
        )
    }
}