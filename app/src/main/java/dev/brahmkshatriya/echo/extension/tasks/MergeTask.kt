package dev.brahmkshatriya.echo.extension.tasks

import dev.brahmkshatriya.echo.common.helpers.FileProgress
import dev.brahmkshatriya.echo.common.helpers.FileTask
import dev.brahmkshatriya.echo.common.helpers.Progress
import dev.brahmkshatriya.echo.common.helpers.SuspendedFunction
import dev.brahmkshatriya.echo.common.models.DownloadContext
import dev.brahmkshatriya.echo.extension.FFMpegHelper
import kotlinx.coroutines.flow.MutableSharedFlow
import java.io.File

class MergeTask(
    context: DownloadContext,
    private val files: List<File>,
    private val dir: File
) : FileTask {

    private val illegalChars = "[/\\\\:*?\"<>|]".toRegex()

    override val progressFlow = MutableSharedFlow<FileProgress>()

    override val start = SuspendedFunction {
        runCatching {
            val file = files.first()
            progressFlow.emit(Progress.InProgress(0, null))
            val detectedExtension = FFMpegHelper.probeFileFormat(file)
            val sanitizedTitle = illegalChars.replace(context.track.title, "_")
            val finalFile = getUniqueFile(dir, sanitizedTitle, detectedExtension, file)
            progressFlow.emit(Progress.Final.Completed(finalFile.length(), finalFile))
        }.getOrElse { e ->
            progressFlow.emit(Progress.Final.Failed(e))
        }
    }

    companion object {
        fun getUniqueFile(directory: File, baseName: String, extension: String, f: File): File {
            val file = if(f.setWritable(true)) f else {
                File(directory, "$baseName-${f.hashCode()}.$extension").also {
                    f.copyTo(it, true)
                    f.delete()
                }
            }
            var uniqueName = "$baseName.$extension"
            var uniqueFile = File(directory, uniqueName)
            var counter = 1

            while (!file.renameTo(uniqueFile)) {
                uniqueName = "$baseName ($counter).$extension"
                uniqueFile = File(directory, uniqueName)
                counter++
            }
            return uniqueFile
        }
    }

    override val cancel = SuspendedFunction {
        progressFlow.emit(Progress.Final.Cancelled())
    }
    override val pause: SuspendedFunction? = null
    override val resume: SuspendedFunction? = null
}



