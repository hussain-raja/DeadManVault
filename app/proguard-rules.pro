# Tink (used by androidx.security.crypto) ships protobuf-lite classes that are
# accessed reflectively.
-keep class com.google.crypto.tink.** { *; }
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.annotation.**
-dontwarn org.checkerframework.**
