-keepattributes LineNumberTable,SourceFile
-renamesourcefileattribute SourceFile
-dontobfuscate

# some classes are required by the native code, keep them all for now
-keep class com.adbye.filter.** { *; }
-keep class com.pcapdroid.mitm.** { *; }
-keep class com.maxmind.db.** { *; }