# R8 is disabled for release (see build.gradle.kts). These rules apply if it is switched on.

-keepattributes SourceFile,LineNumberTable,*Annotation*,Signature,InnerClasses,EnclosingMethod
-renamesourcefileattribute SourceFile

# kotlinx.serialization: keep generated serializers' names and INSTANCE accessors for retained
# @Serializable classes (the library supplies the rest through consumer rules).
-dontnote kotlinx.serialization.**
-if @kotlinx.serialization.Serializable class dev.jasonpearson.motiondots.**
-keepnames class <1>$$serializer {
    static <1>$$serializer INSTANCE;
}

# OkHttp ships consumer rules; silence optional TLS provider references.
-dontwarn org.bouncycastle.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**
