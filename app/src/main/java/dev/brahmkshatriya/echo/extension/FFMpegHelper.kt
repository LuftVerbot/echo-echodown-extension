package dev.brahmkshatriya.echo.extension

import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegSession
import com.arthenica.ffmpegkit.FFprobeKit
import com.arthenica.ffmpegkit.FFprobeSession
import com.arthenica.ffmpegkit.Statistics
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

object FFMpegHelper {

    suspend fun execute(
        command: String,
        onLog: suspend (String?) -> Unit = {},
        onStats: suspend (Statistics) -> Unit = {}
    ): FFmpegSession = suspendCancellableCoroutine { cont ->
        val session = FFmpegKit.executeAsync(command, {
            println("FFmpeg Complete: $it")
            if (it.returnCode.isValueSuccess) cont.resume(it)
            else cont.resumeWithException(Exception(it.output))
        }, { runBlocking { onLog(it.message) } }, { runBlocking { onStats(it) } })
        cont.invokeOnCancellation { session.cancel() }
    }

    private suspend fun executeProbe(
        command: String,
        onLog: suspend (String?) -> Unit = {}
    ): FFprobeSession = suspendCancellableCoroutine { cont ->
        val session = FFprobeKit.executeAsync(command, {
            if (it.returnCode.isValueSuccess) cont.resume(it)
            else cont.resumeWithException(Exception(it.output))
        }, { runBlocking { onLog(it.message) } })
        cont.invokeOnCancellation { session.cancel() }
    }

    suspend fun probeFileFormat(file: File): String {
        val ffprobeCommand =
            "-v error -show_entries format=format_name -of default=noprint_wrappers=1:nokey=1 \"${file.absolutePath}\""

        val session = runCatching { executeProbe(ffprobeCommand) }.getOrElse {
            return "mp3"
        }

        return when (session.output?.trim()?.split(",")?.firstOrNull()) {
            "mov", "mp4", "m4a" -> "m4a"
            "flac" -> "flac"
            else -> "mp3"
        }
    }
}