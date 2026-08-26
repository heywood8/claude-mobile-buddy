# Rules for the release build. Everything here is a thing R8 cannot see for itself.

# --- Wire model -------------------------------------------------------------
# kotlinx.serialization generates a $$serializer for every @Serializable class and reaches it
# through the class's Companion. Both are unreferenced from Kotlin source, so shrinking removes
# them and every snapshot arrives as a decode failure — a phone that connects, says nothing,
# and blames nobody. The library ships consumer rules that cover this; these repeat the part
# that matters for our own package, because the failure mode is silent and the cost is a few
# kilobytes.
-keepclassmembers class dev.heywood8.claudebuddy.** {
    *** Companion;
}
-keepclasseswithmembers class dev.heywood8.claudebuddy.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class dev.heywood8.claudebuddy.**$$serializer { *; }

# --- QR decoding ------------------------------------------------------------
# ZXing is reached through MultiFormatReader, which picks a concrete reader at runtime, and
# through enum constants used as decode hints. R8 can follow most of that, and "most" is the
# problem: the only way to find out it guessed wrong is a camera that looks at a valid code
# and does nothing, on a screen that has no other way forward. Pairing happens once and has to
# work; the decoder keeps its whole surface.
-keep class com.google.zxing.** { *; }

# --- Diagnostics ------------------------------------------------------------
# Everything live is verified by hand on a device, from logcat. An obfuscated stack trace with
# no mapping file to hand costs more than the names save.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
