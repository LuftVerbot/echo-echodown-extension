plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

dependencies {
    implementation(project(":ext"))
    val libVersion: String by project
    compileOnly("com.github.brahmkshatriya:echo:$libVersion")
    compileOnly("org.jetbrains.kotlin:kotlin-stdlib:2.1.0")

    implementation(files("libs/ffmpeg-kit.aar"))
    implementation("net.jthink:jaudiotagger:3.0.1")
    implementation("com.arthenica:smart-exception-java:0.2.1")
}

val extType: String by project
val extId: String by project
val extClass: String = "AndroidED"

val extIconUrl: String? by project
val extName: String by project
val extDescription: String? by project

val extAuthor: String by project
val extAuthorUrl: String? by project

val extRepoUrl: String? by project
val extUpdateUrl: String? by project

val gitHash = execute("git", "rev-parse", "HEAD").take(7)
val gitCount = execute("git", "rev-list", "--count", "HEAD").toInt()
val verCode = gitCount
val verName = "v$gitHash"

val outputDir = file("${layout.buildDirectory.asFile.get()}/generated/proguard")
val generatedProguard = file("${outputDir}/generated-rules.pro")

tasks.register("generateProguardRules") {
    doLast {
        outputDir.mkdirs()
        generatedProguard.writeText(
            """
            -dontobfuscate
            -keep,allowoptimization class dev.brahmkshatriya.echo.extension.$extClass
            -keep class org.jaudiotagger.** { *; }
            -dontwarn java.awt.image.BufferedImage
            -dontwarn javax.imageio.ImageIO
            -dontwarn javax.imageio.stream.ImageInputStream
            -dontwarn com.arthenica.smartexception.java.Exceptions
            -dontwarn java.awt.Graphics2D
            -dontwarn java.awt.Image
            -dontwarn java.awt.geom.AffineTransform
            -dontwarn java.awt.image.ImageObserver
            -dontwarn java.awt.image.RenderedImage
            -dontwarn javax.imageio.ImageWriter
            -dontwarn javax.imageio.stream.ImageOutputStream
            -dontwarn javax.swing.filechooser.FileFilter
            """.trimIndent()
        )
    }
}

tasks.named("preBuild") {
    dependsOn("generateProguardRules")
}

tasks.register("uninstall") {
    android.run {
        execute(
            adbExecutable.absolutePath, "shell", "pm", "uninstall", defaultConfig.applicationId!!
        )
    }
}

android {
    namespace = "dev.brahmkshatriya.echo.extension"
    compileSdk = 35
    defaultConfig {
        applicationId = "dev.brahmkshatriya.echo.extension.$extId"
        minSdk = 24
        targetSdk = 35

        manifestPlaceholders.apply {
            put("type", "dev.brahmkshatriya.echo.${extType}")
            put("id", extId)
            put("class_path", "dev.brahmkshatriya.echo.extension.${extClass}")
            put("preserved_packages", "com.arthenica.ffmpegkit")
            put("version", verName)
            put("version_code", verCode.toString())
            put("icon_url", extIconUrl ?: "")
            put("app_name", "Echo : $extName Extension")
            put("name", extName)
            put("description", extDescription ?: "")
            put("author", extAuthor)
            put("author_url", extAuthorUrl ?: "")
            put("repo_url", extRepoUrl ?: "")
            put("update_url", extUpdateUrl ?: "")
        }
    }

    splits {
        abi {
            isEnable = true
            reset()
            include("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
            isUniversalApk = false
        }
    }

    buildTypes {
        all {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                generatedProguard.absolutePath
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = JavaVersion.VERSION_17.toString()
    }
}

fun execute(vararg command: String): String {
    val processBuilder = ProcessBuilder(*command)
    val hashCode = command.joinToString().hashCode().toString()
    val output = File.createTempFile(hashCode, "")
    processBuilder.redirectOutput(output)
    val process = processBuilder.start()
    process.waitFor()
    return output.readText().dropLast(1)
}