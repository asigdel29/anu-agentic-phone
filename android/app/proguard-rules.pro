# proguard-rules.pro: what R8 may not rename.
#
# History
#   2026-08-09  A. Sigdel  Created.
#
# One rule, and it is a restatement rather than a discovery. Read the note
# under it before deleting it as redundant, because it is redundant.

# router/src/jni.rs exports symbols that encode the package, the class and the
# method: Java_com_getlora_wattrouter_Core_nativeNew, and the same shape for
# Memory and Repository. Renamed, they are an UnsatisfiedLinkError at
# Startup.begin, after the app has compiled, installed and launched.
#
# AGP's proguard-android-optimize.txt ALREADY KEEPS THESE. It is line 30 of
# build/outputs/mapping/release/configuration.txt, which is the merged config
# R8 actually ran, and removing this file's copy was tried: the release APK
# still installed, launched and called into the library.
#
# It is here anyway, and only for this reason: what keeps the symbols is a
# default file named in build.gradle.kts, and neither that line nor its
# contents mention JNI. Somebody swapping proguard-android-optimize.txt for
# proguard-android.txt, or AGP changing what its defaults contain, breaks this
# app at first call with nothing in either file to explain why. One line here
# costs nothing and puts the dependency where the person who breaks it is
# looking.
-keepclasseswithmembernames class * {
    native <methods>;
}

# Nothing for kotlinx.serialization: the encoding here is hand-written with
# buildJsonObject rather than @Serializable, so there are no generated
# serializers to keep and no reflection to preserve. Conversation.kt's header
# gives the reasoning, and this is a consequence of it nobody planned.
#
# Nothing for Compose: its compiler plugin emits ordinary code and the
# artefacts ship their own rules.
#
# Nothing for the accessibility service, the foreground service or the
# activity: components named in AndroidManifest.xml are kept by the rules AGP
# generates from it.
