package dev.brahmkshatriya.echo.extension

import android.annotation.SuppressLint
import android.app.Application
import android.os.Environment
import dev.brahmkshatriya.echo.common.models.DownloadContext
import dev.brahmkshatriya.echo.common.models.Progress
import dev.brahmkshatriya.echo.common.models.Streamable
import dev.brahmkshatriya.echo.common.settings.Setting
import dev.brahmkshatriya.echo.common.settings.SettingCategory
import dev.brahmkshatriya.echo.common.settings.SettingList
import dev.brahmkshatriya.echo.common.settings.SettingSlider
import dev.brahmkshatriya.echo.common.settings.SettingSwitch
import dev.brahmkshatriya.echo.common.settings.SettingTextInput
import dev.brahmkshatriya.echo.common.settings.Settings
import dev.brahmkshatriya.echo.extension.downloaders.HttpDownload
import dev.brahmkshatriya.echo.extension.downloaders.InputStreamDownload
import dev.brahmkshatriya.echo.extension.tasks.Merge
import dev.brahmkshatriya.echo.extension.tasks.Tag
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File

@SuppressLint("PrivateApi")
@Suppress("unused")
class AndroidED : EDExtension() {

    private val context1 by lazy {
        Class.forName("android.app.ActivityThread").getMethod("currentApplication")
            .invoke(null) as Application
    }

    private fun getDownloadDir(context: DownloadContext): File {
        val parentFolderName = context.context?.title
        val sanitizedParent = illegalChars.replace(parentFolderName.orEmpty(), "_")
        val folder = if (sanitizedParent.isNotBlank()) "Echo/$sanitizedParent" else "Echo"
        val targetDirectory = File(context1.cacheDir, folder).apply { mkdirs() }
        return targetDirectory
    }
    override suspend fun selectServer(context: DownloadContext): Streamable {
        return context.track.servers.select(setQuality)
    }

    override suspend fun selectSources(
        context: DownloadContext, server: Streamable.Media.Server
    ): List<Streamable.Source> {
        val sources = mutableListOf<Streamable.Source>()
        sources.add(server.sources.select(setQuality))
        return sources
    }

    private val inputStreamDownload by lazy { InputStreamDownload() }
    private val httpDownload by lazy { HttpDownload() }

    override suspend fun download(
        progressFlow: MutableStateFlow<Progress>,
        context: DownloadContext,
        source: Streamable.Source
    ): File {
        val file = getDownloadDir(context)
        return when (source) {
            is Streamable.Source.ByteStream -> {
                val preFile = File(file.parent, "${source.hashCode()}.mp3")
                inputStreamDownload.inputStreamDownload(
                    preFile,
                    progressFlow,
                    source.stream,
                    source.totalBytes
                )
            }

            is Streamable.Source.Http -> {
                when (val decryption = source.decryption) {
                    null -> {
                        val preFile = File(file.parent, "${source.request.hashCode()}.mp4")
                        httpDownload.httpDownload(
                            preFile,
                            progressFlow,
                            source
                        )
                    }

                    is Streamable.Decryption.Widevine -> {
                        TODO("Not shown for this repos & my safety")
                    }
                }
            }
        }
    }

    private val merge = Merge()
    private val tag = Tag(this)

    override suspend fun merge(
        progressFlow: MutableStateFlow<Progress>,
        context: DownloadContext,
        files: List<File>
    ): File = merge.merge(progressFlow, context, files, trackNum)

    override suspend fun tag(
        progressFlow: MutableStateFlow<Progress>,
        context: DownloadContext,
        file: File
    ): File {
        val downloadsDir =
            Environment.getExternalStoragePublicDirectory(
                when (downFolder) {
                    in "download" -> {
                        Environment.DIRECTORY_DOWNLOADS
                    }

                    in "music" -> {
                        Environment.DIRECTORY_MUSIC
                    }

                    in "podcasts" -> {
                        Environment.DIRECTORY_PODCASTS
                    }

                    else -> {
                        Environment.DIRECTORY_DOWNLOADS
                    }
                }
            )
        return tag.tag(
            progressFlow,
            context,
            file,
            downloadsDir
        )
    }

    private var _settings: Settings? = null
    private val setting: Settings
        get() = _settings ?: throw IllegalStateException("Settings have not been loaded.")
    override fun setSettings(settings: Settings) {
        _settings = settings
    }

    override val settingItems: List<Setting>
        get() = mutableListOf(
            SettingCategory(
                "General",
                "general",
                mutableListOf(
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
                        mutableListOf("0", "1", "2"),
                        1
                    ),
                    SettingList(
                        "Download Main-Folder",
                        "mfolder",
                        "Select the main folder for downloaded music (e.g. Download, Music etc.)",
                        mutableListOf("Download", "Music", "Podcasts"),
                        mutableListOf("download", "music", "podcasts"),
                        0
                    ),
                    SettingTextInput(
                        "Download Subfolder",
                        "sfolder",
                        "Set your preferred sub folder for downloaded music (Use \"/\" for more folders e.g. \"Echo/Your folder name\")",
                        "Echo/"
                    )
                )
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
            ),
            SettingCategory(
                "Customization",
                "customization",
                mutableListOf<Setting>(
                    SettingSwitch(
                        "Track Number In Title",
                        "tracknum",
                        "Tracks have the order number in their title when downloading Playlists/Albums",
                        false
                    )
                )
            )
        )

    override val concurrentDownloads: Int
        get() = setting.getInt(CONCURRENT_DOWNLOADS) ?: 2

    private val setQuality: String
        get() = setting.getString(DOWN_QUALITY) ?: "1"

    private val trackNum: Boolean
        get() = setting.getBoolean(TRACK_NUM) ?: false

    private val downFolder: String
        get() =  setting.getString(M_FOLDER) ?: "download"

    val folderStructure: String
        get() =  setting.getString(S_FOLDER) ?: "Echo/"

    val downLyrics: Boolean
        get() = setting.getBoolean(DOWNLOAD_LYRICS) ?: true

    val downFallbackLyrics: String
        get() = setting.getString(FALLBACK_LYRICS_EXT) ?: ""

    val syncLyrics: Boolean
        get() = setting.getBoolean(SYNC_LYRICS) ?: true


    //For Shivam: Copy my homework but don't make it obvious
    companion object {
        const val DOWN_QUALITY = "quality"
        const val DOWNLOAD_LYRICS = "download_lyrics"
        const val FALLBACK_LYRICS_EXT = "fallback_lyrics_ext"
        const val SYNC_LYRICS = "synced_lyrics"
        const val S_FOLDER = "sfolder"
        const val M_FOLDER = "mfolder"
        const val TRACK_NUM = "tracknum"
        const val CONCURRENT_DOWNLOADS = "download_num"

        val illegalChars = "[/\\\\:*?\"<>|]".toRegex()

        private fun <E> List<E>.select(setQuality: String, quality: (E) -> Int) =
            when (setQuality) {
                "0" -> this.maxByOrNull { quality(it) } ?: first()
                "1" -> sortedBy { quality(it) }[size / 2]
                "2" -> this.minByOrNull { quality(it) } ?: first()
                else -> first()
            }

        private fun List<Streamable>.select(setQuality: String) =
            select(setQuality) { it.quality }

        private fun List<Streamable.Source>.select(setQuality: String) =
            select(setQuality) { it.quality }
    }
}