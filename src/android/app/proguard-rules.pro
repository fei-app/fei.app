# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile


# ============================================================
# OpenFEI — Regras R8/ProGuard (release com minify + shrink)
# ============================================================

# ---------- Atributos essenciais ----------
# Signature: genéricos do Gson/TypeToken.
# InnerClasses/EnclosingMethod: TypeTokens anônimas do Kotlin.
# SourceFile/LineNumberTable: stack traces legíveis no Play Console
# (o mapping.txt vai embutido no AAB — o Play desofusca sozinho).
-keepattributes Signature, InnerClasses, EnclosingMethod
-keepattributes *Annotation*
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# ---------- JNI (CRÍTICO) ----------
# Os símbolos C são Java_com_marinov_openfei_core_OpenFeiCore_native*:
# renomear a classe ou os métodos native quebra o binding com o Rust.
-keep class com.marinov.openfei.core.OpenFeiCore { *; }
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}

# ---------- Modelos Gson (reflection sobre campos/construtores) ----------
-keep class com.marinov.openfei.core.Core* { *; }
-keep class com.marinov.openfei.data.Disciplina { *; }
-keep class com.marinov.openfei.data.Nota { *; }
-keep class com.marinov.openfei.data.Perfil { *; }
-keep class com.marinov.openfei.data.Aula { *; }
-keep class com.marinov.openfei.data.ProvaCalendario { *; }
-keep class com.marinov.openfei.data.AnexoMoodle { *; }
-keep class com.marinov.openfei.data.Boleto { *; }
-keep class com.marinov.openfei.data.CarouselItem { *; }

# ---------- Gson: TypeToken ----------
# O app usa "object : TypeToken<List<T>>() {}" em vários pontos.
# Keep forte nas subclasses evita o bug clássico do R8 full mode
# (apagar a assinatura genérica e parsear tudo como Object/Double).
-keep class com.google.gson.reflect.TypeToken { *; }
-keep class * extends com.google.gson.reflect.TypeToken { *; }
-keepclassmembers,allowobfuscation class * {
    @com.google.gson.annotations.SerializedName <fields>;
}

# ---------- WorkManager (Worker instanciado por reflection) ----------
-keep class com.marinov.openfei.background.PeriodicSyncWorker {
    <init>(android.content.Context, androidx.work.WorkerParameters);
}

# ---------- Navigation ----------
# nav_graph.xml referencia HomeFragment PELO NOME (reflection do
# FragmentManager). Nome da classe + construtor vazio preservados.
-keep class com.marinov.openfei.ui.home.HomeFragment { <init>(); }

# ---------- Kotlin coroutines ----------
-keepclassmembers class kotlinx.coroutines.** { volatile <fields>; }
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-dontwarn kotlinx.coroutines.**

# ---------- Glide ----------
-keep public class * implements com.bumptech.glide.module.GlideModule
-keep class * extends com.bumptech.glide.module.AppGlideModule { <init>(...); }
-keep public enum com.bumptech.glide.load.resource.bitmap.ImageHeaderParser$** {
    **[] $VALUES;
    public *;
}

# ---------- Tink / EncryptedSharedPreferences ----------
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.annotation.**
-dontwarn org.checkerframework.**
-dontwarn edu.umd.cs.findbugs.annotations.**
-dontwarn java.awt.**
-dontwarn org.joda.time.**
-dontwarn com.google.api.client.http.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# ---------- OTIMIZAÇÃO DEX: remove Log.d/Log.v em release ----------
# O app tem MUITOS logs de diagnóstico com strings longas. O R8 remove
# as chamadas E as strings constantes concatenadas (grande redução de
# DEX). Log.i/w/e são mantidos para diagnóstico em produção.
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
}