-dontobfuscate
-dontoptimize
-keep public class com.lightningkite.lightningserver.demo.MainKt {
    public static void main(java.lang.String[]);
}
-keep public class org.slf4j.** { *; }
-keep public class ch.** { *; }
-keep class kotlin.reflect.**
-keep class kotlin.Metadata { *; }
-keep class kotlin.reflect.jvm.internal.**
-keep class com.lightningkite.kotlinercli.** { *; }
-keep class org.litote.kmongo.serialization.SerializationClassMappingTypeService { *; }
-keep class io.ktor.** { *; }
-keep class kotlinx.coroutines.** { *; }

-keepnames com.lightningkite.lightningserver.demo.**
-keepnames com.lightningkite.lightningserver.demo.AwsHandler

-dontwarn jakarta.**
-dontwarn org.osgi.**
-dontwarn javax.**
-dontwarn org.codehaus.mojo.animal_sniffer.**
-dontwarn android.**

-dontwarn com.sun.**
-dontwarn io.lettuce.**
-dontwarn net.rubyeye.xmemcached.**
-dontwarn org.apache.**
-dontwarn reactor.**
-dontwarn software.amazon.**
-dontwarn com.azure.core.**
-dontwarn com.ctc.**
-dontwarn com.mongodb.**
-dontwarn de.flapdoodle.**
-dontwarn ch.qos.**
-dontwarn io.netty.**
-dontwarn io.ktor.**
-dontwarn org.joda.**
-dontwarn org.litote.**
-dontwarn org.postgresql.**
-dontwarn nl.adaptivity.**
-dontwarn kotlinx.coroutines.**

# Keep `Companion` object fields of serializable classes.
# This avoids serializer lookup through `getDeclaredClasses` as done for named companion objects.
-if @kotlinx.serialization.Serializable class **
-keepclassmembers class <1> {
    static <1>$Companion Companion;
}

# Keep `serializer()` on companion objects (both default and named) of serializable classes.
-if @kotlinx.serialization.Serializable class ** {
    static **$* *;
}
-keepclassmembers class <2>$<3> {
    kotlinx.serialization.KSerializer serializer(...);
}

# Keep `INSTANCE.serializer()` of serializable objects.
-if @kotlinx.serialization.Serializable class ** {
    public static ** INSTANCE;
}
-keepclassmembers class <1> {
    public static <1> INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
}

# @Serializable and @Polymorphic are used at runtime for polymorphic serialization.
-keepattributes RuntimeVisibleAnnotations,AnnotationDefault

# Don't print notes about potential mistakes or omissions in the configuration for kotlinx-serialization classes
# See also https://github.com/Kotlin/kotlinx.serialization/issues/1900
-dontnote kotlinx.serialization.**

# Serialization core uses `java.lang.ClassValue` for caching inside these specified classes.
# If there is no `java.lang.ClassValue` (for example, in Android), then R8/ProGuard will print a warning.
# However, since in this case they will not be used, we can disable these warnings
-dontwarn kotlinx.serialization.internal.ClassValueReferences

-keep public class * implements kotlinx.serialization.KSerializer {
 *;
 }
