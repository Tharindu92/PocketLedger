# R8 rules for the release build. Room, ML Kit, Compose and Play services ship their own rules.

# PdfBox-Android loads fonts, encodings and filters by name at runtime: keep it whole.
-keep class com.tom_roush.pdfbox.** { *; }
-keep class com.tom_roush.fontbox.** { *; }
-keep class com.tom_roush.harmony.** { *; }
# Optional JPEG 2000 support that PdfBox references but we don't include
-dontwarn com.gemalto.jp2.**

# Readable crash stack traces in Play Console (upload the mapping file with each release)
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
