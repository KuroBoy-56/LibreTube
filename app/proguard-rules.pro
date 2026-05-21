# =========================================================
# REGLAS DE SEGURIDAD EXTRA (Ofuscación agresiva)
# =========================================================
# Renombrar clases y métodos a letras aleatorias
-repackageclasses ''
-allowaccessmodification

# Eliminar logs en producción (Evita que vean datos por consola)
-assumenosideeffects class android.util.Log {
    public static boolean isLoggable(java.lang.String, int);
    public static int v(...);
    public static int i(...);
    public static int w(...);
    public static int d(...);
    public static int e(...);
}

# =========================================================
# REGLAS ORIGINALES DE LIBRETUBE
# =========================================================
# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.kts.

# Uncomment this to preserve the line number information for
# debugging stack traces.
-keepattributes SourceFile,LineNumberTable

# ¡CORREGIDO! Esta línea estaba matando la seguridad. La comentamos.
# -dontobfuscate

# optimise protobuf classes
-shrinkunusedprotofields

# Keep data classes used for Retrofit
-keep class com.github.libretube.obj.** { *; }
-keep class com.github.libretube.api.obj.** { *; }
-keep class com.github.libretube.obj.update.** { *; }

# Keep rules required by Kotlinx Serialization
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

-if @kotlinx.serialization.Serializable class ** {
    public static ** INSTANCE;
}
-keepclassmembers class <1> {
    public static <1> INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
}

-keepattributes RuntimeVisibleAnnotations,AnnotationDefault

# -- Retrofit keep rules, obtained from https://github.com/square/retrofit/blob/master/retrofit/src/main/resources/META-INF/proguard/retrofit2.pro

# Retrofit does reflection on generic parameters. InnerClasses is required to use Signature and
# EnclosingMethod is required to use InnerClasses.
-keepattributes Signature, InnerClasses, EnclosingMethod

# Retrofit does reflection on method and parameter annotations.
-keepattributes RuntimeVisibleAnnotations, RuntimeVisibleParameterAnnotations

# Keep annotation default values (e.g., retrofit2.http.Field.encoded).
-keepattributes AnnotationDefault

# Retain service method parameters when optimizing.
-keepclassmembers,allowshrinking,allowobfuscation interface * {
    @retrofit2.http.* <methods>;
}

# Ignore annotation used for build tooling.
-dontwarn org.codehaus.mojo.animal_sniffer.IgnoreJRERequirement

# Ignore JSR 305 annotations for embedding nullability information.
-dontwarn javax.annotation.**

# Guarded by a NoClassDefFoundError try/catch and only used when on the classpath.
-dontwarn kotlin.Unit

# Top-level functions that can only be used by Kotlin.
-dontwarn retrofit2.KotlinExtensions
-dontwarn retrofit2.KotlinExtensions$*

# With R8 full mode, it sees no subtypes of Retrofit interfaces since they are created with a Proxy
# and replaces all potential values with null. Explicitly keeping the interfaces prevents this.
-if interface * { @retrofit2.http.* <methods>; }
-keep,allowobfuscation interface <1>

# Keep inherited services.
-if interface * { @retrofit2.http.* <methods>; }
-keep,allowobfuscation interface * extends <1>

# Keep generic signature of Call, Response (R8 full mode strips signatures from non-kept items).
-keep,allowobfuscation,allowshrinking interface retrofit2.Call
-keep,allowobfuscation,allowshrinking class retrofit2.Response

# With R8 full mode generic signatures are stripped for classes that are not
# kept. Suspend functions are wrapped in continuations where the type argument
# is used.
-keep,allowobfuscation,allowshrinking class kotlin.coroutines.Continuation

# -- End of Retrofit keep rules

-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# Fix for miniplayer placing issue in release build
-keep class androidx.constraintlayout.motion.widget.** { *; }
-keepclassmembers class androidx.constraintlayout.motion.widget.** { *; }

# Settings fragments are loaded through reflection
-keep class com.github.libretube.ui.preferences.** { *; }

## Rules for NewPipeExtractor
-keep class org.schabi.newpipe.extractor.timeago.patterns.** { *; }
-keep class org.mozilla.javascript.** { *; }
-keep class org.mozilla.javascript.engine.** { *; }
-dontwarn org.mozilla.javascript.JavaToJSONConverters
-dontwarn org.mozilla.javascript.tools.**
-keep class javax.script.** { *; }
-dontwarn javax.script.**
-keep class jdk.dynalink.** { *; }
-dontwarn jdk.dynalink.**
-dontwarn com.google.re2j.**

# This is generated automatically by the Android Gradle plugin.
-dontwarn java.beans.BeanDescriptor
-dontwarn java.beans.BeanInfo
-dontwarn java.beans.IntrospectionException
-dontwarn java.beans.Introspector
-dontwarn java.beans.PropertyDescriptor

# =========================================================
# PROTECCIÓN DE TUS MODIFICACIONES (Login, Animación y Panel)
# =========================================================

# Proteger la inicialización del Splash nativo y la animación
-keep class androidx.core.splashscreen.** { *; }

# Proteger las funciones de red y seguridad de tu Login
-keepclassmembers class com.github.libretube.helpers.CoreInitActivity {
    *** performLogin(...);
    *** getCustomMacAddress(...);
}

# Proteger el validador de actualizaciones de tu Main
-keepclassmembers class com.github.libretube.ui.activities.MainActivity {
    *** seguridad(...);
}