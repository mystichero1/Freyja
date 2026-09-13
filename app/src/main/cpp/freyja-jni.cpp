#include <jni.h>
#include <string>

extern "C" JNIEXPORT jstring JNICALL
Java_com_mystic_freyja_MainActivity_stringFromJNI(
        JNIEnv* env,
        jobject /* this */) {
    std::string hello = "Freyja Native Engine Active";
    return env->NewStringUTF(hello.c_str());
}
