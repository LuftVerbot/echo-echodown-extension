package dev.brahmkshatriya.echo.extension

import dev.brahmkshatriya.echo.common.helpers.ContinuationCallback.Companion.await
import dev.brahmkshatriya.echo.common.models.Progress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import okhttp3.Headers.Companion.toHeaders
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import dev.brahmkshatriya.echo.common.models.Request as EchoRequest

object Downloader {

    suspend fun download(
        file: File,
        stream: InputStream,
        totalBytes: Long,
        append: Boolean,
        progressFlow: MutableSharedFlow<Progress>? = null,
        receiveFlow: MutableStateFlow<Long>? = null,
    ) = withContext(Dispatchers.IO) {
        progressFlow?.emit(Progress(totalBytes))

        stream.buffered().use { bis ->
            val fos = FileOutputStream(file, append)
            fos.buffered().use { out ->
                val buffer = ByteArray(256 * 1024)
                var received = 0L

                var lastTime = System.currentTimeMillis()
                var lastBytes = 0L

                while (true) {
                    val bytesRead = bis.read(buffer).takeIf { it >= 0 } ?: break
                    out.write(buffer, 0, bytesRead)
                    received += bytesRead
                    receiveFlow?.value = received

                    val now = System.currentTimeMillis()
                    if (now - lastTime >= 1_000) {
                        val speed = received - lastBytes
                        lastBytes = received
                        lastTime = now
                        progressFlow?.emit(Progress(totalBytes, received, speed))
                    }
                }
            }
        }
        file
    }

    val client by lazy { OkHttpClient() }

    suspend fun okHttpDownload(
        file: File,
        req: EchoRequest,
        append: Boolean,
        progressFlow: MutableSharedFlow<Progress>? = null,
        receiveFlow: MutableStateFlow<Long>? = null,
    ): File {
        val fileLength = file.length()
        receiveFlow?.value = fileLength
        val headers = (req.headers + mapOf("Range" to "bytes=${fileLength}-")).toHeaders()
        val request = Request.Builder().url(req.url).headers(headers).build()
        val response = client.newCall(request).await()

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