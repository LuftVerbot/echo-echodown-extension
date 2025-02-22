# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile

-keep class dev.brahmkshatriya.echo.** { *; }
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