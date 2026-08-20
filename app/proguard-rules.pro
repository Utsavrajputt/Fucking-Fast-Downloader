# OkHttp / Okio use reflection for platform detection; keep them intact.
-dontwarn okhttp3.**
-dontwarn okio.**
-keepnames class okhttp3.internal.publicsuffix.PublicSuffixDatabase
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }

# Jsoup does HTML parsing reflectively in places.
-keep class org.jsoup.** { *; }
-dontwarn org.jsoup.**

# Room entities/DAOs are annotation-processed at compile time, but keep the
# model classes themselves since QueueRepository/DownloadService serialize
# them by field access.
-keep class com.invictus.xmd.core.** { *; }

# Kotlin coroutines internals occasionally trip up R8 without this.
-dontwarn kotlinx.coroutines.**
