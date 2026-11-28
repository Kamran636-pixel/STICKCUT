#include <jni.h>
#include "whisper.h"

// Step 90: only proves that the whisper.cpp library is inside the app and loads.
extern "C" JNIEXPORT jstring JNICALL
Java_com_kamran_stickcut_WhisperNative_systemInfo(JNIEnv *env, jobject /* this */) {
    const char *info = whisper_print_system_info();
    return env->NewStringUTF(info != nullptr ? info : "(no info)");
}
