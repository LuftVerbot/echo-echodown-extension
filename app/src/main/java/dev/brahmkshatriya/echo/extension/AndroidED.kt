package dev.brahmkshatriya.echo.extension

import android.os.Environment
import dev.brahmkshatriya.echo.common.LyricsExtension
import dev.brahmkshatriya.echo.common.models.DownloadContext
import dev.brahmkshatriya.echo.common.models.Streamable
import dev.brahmkshatriya.echo.common.providers.LyricsExtensionsProvider
import dev.brahmkshatriya.echo.common.settings.Setting
import dev.brahmkshatriya.echo.common.settings.SettingCategory
import dev.brahmkshatriya.echo.common.settings.SettingList
import dev.brahmkshatriya.echo.common.settings.SettingSlider
import dev.brahmkshatriya.echo.common.settings.SettingSwitch
import dev.brahmkshatriya.echo.common.settings.Settings
import dev.brahmkshatriya.echo.extension.tasks.HttpDownloadTask
import dev.brahmkshatriya.echo.extension.tasks.InputStreamTask
import dev.brahmkshatriya.echo.extension.tasks.MergeTask
import dev.brahmkshatriya.echo.extension.tasks.TagTask
import dev.brahmkshatriya.echo.extension.tasks.TagTask.Companion.DOWNLOAD_LYRICS
import dev.brahmkshatriya.echo.extension.tasks.TagTask.Companion.FALLBACK_LYRICS_EXT
import dev.brahmkshatriya.echo.extension.tasks.TagTask.Companion.SYNC_LYRICS
import java.io.File

@Suppress("unused")
class AndroidED : EDExtension(), LyricsExtensionsProvider {

    override val requiredLyricsExtensions: List<String> = emptyList()
    private var lyricsExtensionList: List<LyricsExtension> = emptyList()
    override fun setLyricsExtensions(extensions: List<LyricsExtension>) {
        lyricsExtensionList = extensions
    }

    private val illegalChars = "[/\\\\:*?\"<>|]".toRegex()
    override suspend fun getDownloadDir(context: DownloadContext): File {
        val parentFolderName = context.context?.title
        val sanitizedParent = illegalChars.replace(parentFolderName.orEmpty(), "_")
        val folder = if (sanitizedParent.isNotBlank()) "Echo/$sanitizedParent" else "Echo"
        val downloadsDir =
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val targetDirectory = File(downloadsDir, folder).apply { mkdirs() }
        return targetDirectory
    }

    override suspend fun selectServer(context: DownloadContext): Streamable {
        return context.track.servers.select(setting)
    }

    override suspend fun selectSources(
        context: DownloadContext, server: Streamable.Media.Server
    ): List<Streamable.Source> {
        val sources = mutableListOf<Streamable.Source>()
        sources.add(server.sources.select(setting))
        return sources
    }

    override suspend fun download(
        context: DownloadContext, source: Streamable.Source, file: File
    ) = when (source) {
        is Streamable.Source.ByteStream -> {
            val preFile = File(file.parent, "${source.hashCode()}.mp3")
            InputStreamTask(
                preFile,
                source.stream,
                source.totalBytes
            )
        }

        is Streamable.Source.Http -> {
            when (val decryption = source.decryption) {
                null -> {
                    val preFile = File(file.parent, "${source.request.hashCode()}.mp4")
                    HttpDownloadTask(preFile, source)
                }
                is Streamable.Decryption.Widevine -> {
                    TODO("Not shown for this repos & my safety")
                }
            }
        }
    }

    override suspend fun merge(context: DownloadContext, files: List<File>, dir: File) =
        MergeTask(context, files, dir)

    override suspend fun tag(context: DownloadContext, file: File) =
        TagTask(context, file, setting, musicExtensionList, lyricsExtensionList)

    private lateinit var setting: Settings
    override fun setSettings(settings: Settings) {
        setting = settings
    }

    override val settingItems: List<Setting>
        get() = mutableListOf(
            SettingSlider(
                "Concurrent Downloads",
                "download_num",
                "Number of concurrent downloads",
                2,
                1,
                10,
                1
            ),
            SettingList(
                "Download Quality",
                DOWN_QUALITY,
                "Quality of your downloads",
                mutableListOf("Highest", "Medium", "Lowest"),
                streamQualities,
                1
            ),
            SettingCategory(
                "Lyrics",
                "lyrics",
                mutableListOf<Setting>(
                    SettingSwitch(
                        "Download Lyrics",
                        DOWNLOAD_LYRICS,
                        "Whether to download the lyrics for downloaded track or not",
                        true
                    )
                ).apply {
                    if (lyricsExtensionList.isNotEmpty()) {
                        addAll(
                            listOf(
                                SettingSwitch(
                                    "Synced Lyrics",
                                    SYNC_LYRICS,
                                    "Use lyrics extension to get synced lyrics. Regardless of the music extension having synced lyrics",
                                    true
                                ),
                                SettingList(
                                    "Fallback Lyrics Extension",
                                    FALLBACK_LYRICS_EXT,
                                    "The lyrics extension to use, when no lyrics are found",
                                    lyricsExtensionList.map { it.name },
                                    lyricsExtensionList.map { it.id },
                                    0
                                )
                            )
                        )
                    }
                }
            )
        )

    override val concurrentDownloads: Int
        get() = setting.getInt("download_num") ?: 2

    //For Shivam: Copy my homework but don't make it obvious
    companion object {
        const val DOWN_QUALITY = "quality"
        val streamQualities = mutableListOf("0", "1", "2")

        private fun <E> List<E>.select(settings: Settings?, quality: (E) -> Int) =
            when (settings?.getString(DOWN_QUALITY)) {
                "0" -> maxBy { quality(it) }
                "1" -> sortedBy { quality(it) }[size / 2]
                "2" -> minBy { quality(it) }
                else -> first()
            }

        private fun List<Streamable>.select(settings: Settings?) =
            select(settings) { it.quality }

        private fun List<Streamable.Source>.select(settings: Settings?) =
            select(settings) { it.quality }
    }
}