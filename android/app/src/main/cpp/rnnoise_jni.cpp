#include <jni.h>
#include <cstdint>
#include "rnnoise/rnnoise.h"

extern "C"
JNIEXPORT jlong JNICALL
Java_com_karaoke_app_audio_NativeRnnoise_createState(JNIEnv *, jobject) {
    DenoiseState *state = rnnoise_create(nullptr);
    return reinterpret_cast<jlong>(state);
}

extern "C"
JNIEXPORT void JNICALL
Java_com_karaoke_app_audio_NativeRnnoise_destroyState(JNIEnv *, jobject, jlong handle) {
    if (handle == 0) return;
    auto *state = reinterpret_cast<DenoiseState *>(handle);
    rnnoise_destroy(state);
}

extern "C"
JNIEXPORT jfloat JNICALL
Java_com_karaoke_app_audio_NativeRnnoise_processFrame(JNIEnv *env, jobject, jlong handle,
                                                       jfloatArray input, jfloatArray output) {
    if (handle == 0 || input == nullptr || output == nullptr) return 0.0f;

    constexpr int frame = 480;
    if (env->GetArrayLength(input) < frame || env->GetArrayLength(output) < frame) return 0.0f;

    jfloat inBuf[frame];
    env->GetFloatArrayRegion(input, 0, frame, inBuf);

    jfloat outBuf[frame];
    auto *state = reinterpret_cast<DenoiseState *>(handle);
    float vad = rnnoise_process_frame(state, outBuf, inBuf);

    env->SetFloatArrayRegion(output, 0, frame, outBuf);
    return vad;
}
