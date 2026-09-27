#include <jni.h>
#include <string>
#include "openfei_core.h"

static std::string jstring_to_string(JNIEnv* env, jstring js) {
    if (!js) return "";
    const char* chars = env->GetStringUTFChars(js, nullptr);
    std::string result(chars);
    env->ReleaseStringUTFChars(js, chars);
    return result;
}

static jstring char_to_jstring(JNIEnv* env, const char* s) {
    return env->NewStringUTF(s);
}

static jstring call_rust_no_args(JNIEnv* env, char* (*fn)()) {
    char* raw = fn();
    jstring result = char_to_jstring(env, raw);
    openfei_free_string(raw);
    return result;
}

static jstring call_rust_one_arg(JNIEnv* env, jstring a, char* (*fn)(const char*)) {
    std::string arg = jstring_to_string(env, a);
    char* raw = fn(arg.c_str());
    jstring result = char_to_jstring(env, raw);
    openfei_free_string(raw);
    return result;
}

static jstring call_rust_two_args(
        JNIEnv* env,
        jstring a,
        jstring b,
        char* (*fn)(const char*, const char*)
) {
    std::string arg1 = jstring_to_string(env, a);
    std::string arg2 = jstring_to_string(env, b);

    char* raw = fn(arg1.c_str(), arg2.c_str());
    jstring result = char_to_jstring(env, raw);
    openfei_free_string(raw);
    return result;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_marinov_openfei_core_OpenFeiCore_nativeIsOnline(JNIEnv* env, jobject) {
    return call_rust_no_args(env, openfei_is_online);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_marinov_openfei_core_OpenFeiCore_nativeLogin(
        JNIEnv* env,
        jobject,
        jstring user,
        jstring pass
) {
    return call_rust_two_args(env, user, pass, openfei_login);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_marinov_openfei_core_OpenFeiCore_nativeMoodleLogin(
        JNIEnv* env,
        jobject,
        jstring user,
        jstring pass
) {
    return call_rust_two_args(env, user, pass, openfei_moodle_login);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_marinov_openfei_core_OpenFeiCore_nativeMoodleToken(
        JNIEnv* env,
        jobject,
        jstring user,
        jstring pass
) {
    return call_rust_two_args(env, user, pass, openfei_moodle_token);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_marinov_openfei_core_OpenFeiCore_nativeFetchDisciplinas(JNIEnv* env, jobject) {
    return call_rust_no_args(env, openfei_fetch_disciplinas);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_marinov_openfei_core_OpenFeiCore_nativeFetchNotas(JNIEnv* env, jobject) {
    return call_rust_no_args(env, openfei_fetch_notas);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_marinov_openfei_core_OpenFeiCore_nativeFetchMedias(JNIEnv* env, jobject) {
    return call_rust_no_args(env, openfei_fetch_medias);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_marinov_openfei_core_OpenFeiCore_nativeFetchAulas(JNIEnv* env, jobject) {
    return call_rust_no_args(env, openfei_fetch_aulas);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_marinov_openfei_core_OpenFeiCore_nativeFetchPerfil(JNIEnv* env, jobject) {
    return call_rust_no_args(env, openfei_fetch_perfil);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_marinov_openfei_core_OpenFeiCore_nativeFetchProvasFei(JNIEnv* env, jobject) {
    return call_rust_no_args(env, openfei_fetch_provas_fei);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_marinov_openfei_core_OpenFeiCore_nativeFetchBoletos(JNIEnv* env, jobject) {
    return call_rust_no_args(env, openfei_fetch_boletos);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_marinov_openfei_core_OpenFeiCore_nativeFetchCarousel(JNIEnv* env, jobject) {
    return call_rust_no_args(env, openfei_fetch_carousel);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_marinov_openfei_core_OpenFeiCore_nativeDownloadBoleto(
        JNIEnv* env,
        jobject,
        jstring tituloId,
        jstring vencimento,
        jstring outDir
) {
    std::string arg1 = jstring_to_string(env, tituloId);
    std::string arg2 = jstring_to_string(env, vencimento);
    std::string arg3 = jstring_to_string(env, outDir);

    char* raw = openfei_download_boleto(arg1.c_str(), arg2.c_str(), arg3.c_str());
    jstring result = char_to_jstring(env, raw);
    openfei_free_string(raw);

    return result;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_marinov_openfei_core_OpenFeiCore_nativeFetchEventosMoodle(
        JNIEnv* env,
        jobject,
        jstring user,
        jstring pass
) {
    return call_rust_two_args(env, user, pass, openfei_fetch_eventos_moodle);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_marinov_openfei_core_OpenFeiCore_nativeFetchEventosMoodleWithToken(
        JNIEnv* env,
        jobject,
        jstring token
) {
    return call_rust_one_arg(env, token, openfei_fetch_eventos_moodle_with_token);
}

extern "C" JNIEXPORT void JNICALL
Java_com_marinov_openfei_core_OpenFeiCore_nativeClearSession(JNIEnv*, jobject) {
    openfei_clear_session();
}