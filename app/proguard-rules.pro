# Minify is disabled for release for now. These keeps matter if it's ever enabled:
# libbox + the gomobile `go` runtime are called via JNI and must not be stripped/renamed.
-keep class go.** { *; }
-keep class io.nekohasekai.libbox.** { *; }
