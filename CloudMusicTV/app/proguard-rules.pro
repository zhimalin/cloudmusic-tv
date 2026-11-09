# Crash reports stay readable (and deobfuscation against mapping.txt works).
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# ---------------------------------------------------------------------------
# kotlinx.serialization
#
# The compiler already generated every serializer as direct code, but R8 still
# has to keep the entry points the library uses at runtime:
#   - <Model>$Companion + Companion.serializer()
#   - <Model>$$serializer.INSTANCE
#   - the serializer() method on the generated companion object
#
# These are the official conditional rules: they only touch classes marked
# @kotlinx.serialization.Serializable and their generated serializers. No
# blanket "-keep class ** { *; }".
# ---------------------------------------------------------------------------
-keepattributes RuntimeVisibleAnnotations,AnnotationDefault

-if @kotlinx.serialization.Serializable class **
-keepclassmembers class <1> {
    static <1>$Companion Companion;
}

-if @kotlinx.serialization.Serializable class ** {
    static **$* *;
}
-keepclassmembers class <2>$<3> {
    kotlinx.serialization.KSerializer serializer(...);
}

-if @kotlinx.serialization.Serializable class **
-keepclassmembers class <1> {
    public static <1>$$serializer INSTANCE;
}
