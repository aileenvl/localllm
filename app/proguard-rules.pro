# LocalLLM ProGuard / R8 rules
#
# This app uses heavy reflection through LiteRT-LM natives, Ktor, Netty and
# Gson. Without these keeps, an R8-minified release build will silently break
# at request time — long after the build succeeded.

# --- LiteRT-LM (Google AI Edge) ---------------------------------------------
# LiteRT-LM bridges into native code via JNI and performs reflective class
# lookups for engine/runtime wiring. R8 would otherwise strip or rename the
# JNI-visible classes/members, breaking inference at first call.
-keep class com.google.ai.edge.litertlm.** { *; }
-dontwarn com.google.ai.edge.litertlm.**

# --- Ktor server + Netty ----------------------------------------------------
-keep class io.ktor.** { *; }
-dontwarn io.ktor.**
-keep class io.netty.** { *; }
-dontwarn io.netty.**
# Netty uses reflective access to internal sun.misc.* in some optimization paths
-dontwarn sun.misc.**
-dontwarn org.slf4j.**

# --- Gson -------------------------------------------------------------------
# Gson uses reflection on the @SerializedName-annotated fields in our data
# classes. Keep everything in our package so request/response types survive.
-keep class com.localllm.app.** { *; }
-keepattributes Signature, *Annotation*, SourceFile, LineNumberTable, EnclosingMethod
-keepclassmembers,allowobfuscation class * {
    @com.google.gson.annotations.SerializedName <fields>;
}

# --- Kotlin metadata --------------------------------------------------------
-keep class kotlin.Metadata { *; }
-keepclassmembers class **$Companion { *; }
-keep class kotlin.coroutines.Continuation
-dontwarn kotlinx.coroutines.flow.**
-dontwarn kotlinx.atomicfu.**

# --- Compose ----------------------------------------------------------------
# Compose compiler emits reflection-friendly metadata; default rules handle most
# but keep our composable host classes anyway.
-keep class androidx.compose.** { *; }
-dontwarn androidx.compose.**
