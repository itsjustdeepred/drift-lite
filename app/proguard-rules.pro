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

-keepattributes SourceFile, LineNumberTable
-keep public class * extends java.lang.Exception
-keep class retrofit2.** { *; }

-keep class **.reflect.TypeToken { *; }
-keep class * extends **.reflect.TypeToken
# Drift Lite: NewPipeExtractor (YouTube Music search and streams) uses reflection and Rhino
-keep class org.schabi.newpipe.extractor.** { *; }
-keep class org.mozilla.javascript.** { *; }
-keep class org.mozilla.classfile.ClassFileWriter
-dontwarn org.mozilla.javascript.**
-dontwarn org.mozilla.classfile.**
-dontwarn java.beans.**
-dontwarn javax.script.**
-dontwarn jdk.dynalink.**
-dontwarn com.google.re2j.**

# Drift Lite: data saved as JSON on the phone (Gson). Keep field names and generic types,
# or lists come back as LinkedTreeMap and crash (ClassCastException) when read back.
-keepattributes Signature, InnerClasses, EnclosingMethod, *Annotation*
-keep class com.eddyizm.tempus.lite.** { <fields>; <init>(...); }
-keep class com.eddyizm.tempus.helper.DriftSpotify$* { <fields>; <init>(...); }
-keep class com.eddyizm.tempus.helper.DriftMixes$* { <fields>; <init>(...); }
-keep class * extends com.google.gson.reflect.TypeToken
-keep,allowobfuscation,allowshrinking class com.google.gson.reflect.TypeToken
