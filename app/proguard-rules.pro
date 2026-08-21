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

# libtorrent4j: its SWIG-generated JNI glue is loaded/called by name from
# native code, so R8 can't see those references -- without this keep rule a
# release build crashes at runtime the moment TorrentEngine touches the
# session (works fine in debug, which is why this is easy to miss).
-keep class org.libtorrent4j.** { *; }
-keep class org.libtorrent4j.swig.** { *; }
-dontwarn org.libtorrent4j.**
