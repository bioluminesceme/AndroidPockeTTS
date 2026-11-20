#include <jni.h>
#include <pocket_ffi.h>
#include <exception>
#include <string>
#include <thread>
#include <vector>

namespace {
void fail(JNIEnv* env, const char* message) {
    if (env->ExceptionCheck()) return;
    jclass string_class = env->FindClass("java/lang/String");
    if (!string_class) return;
    jmethodID constructor = env->GetMethodID(string_class, "<init>", "([BLjava/lang/String;)V");
    const std::string text(message);
    jbyteArray bytes = env->NewByteArray(static_cast<jsize>(text.size()));
    if (!bytes || !constructor) return;
    env->SetByteArrayRegion(bytes, 0, static_cast<jsize>(text.size()), reinterpret_cast<const jbyte*>(text.data()));
    jstring encoding = env->NewStringUTF("UTF-8");
    jobject description = env->NewObject(string_class, constructor, bytes, encoding);
    jclass error_class = env->FindClass("java/lang/IllegalStateException");
    if (!description || !error_class) return;
    jmethodID error_constructor = env->GetMethodID(error_class, "<init>", "(Ljava/lang/String;)V");
    if (!error_constructor) return;
    jobject error = env->NewObject(error_class, error_constructor, description);
    if (error) env->Throw(static_cast<jthrowable>(error));
}

bool utf8(JNIEnv* env, jbyteArray input, std::string& output) {
    if (!input) { fail(env, "UTF-8 input is null"); return false; }
    const jsize count = env->GetArrayLength(input);
    output.resize(static_cast<size_t>(count));
    env->GetByteArrayRegion(input, 0, count, reinterpret_cast<jbyte*>(output.data()));
    if (env->ExceptionCheck()) return false;
    if (output.find('\0') != std::string::npos) { fail(env, "UTF-8 input contains NUL"); return false; }
    return true;
}

struct Callback {
    JavaVM* vm = nullptr;
    jobject sink = nullptr;
    jmethodID method = nullptr;
    jthrowable error = nullptr;
    bool attach_failed = false;
};

int32_t audio(const float* samples, size_t count, uint64_t first, void* user) {
    auto& callback = *static_cast<Callback*>(user);
    if (count > POCKET_MAX_CALLBACK_SAMPLES) return 2;
    JNIEnv* env = nullptr;
    const jint state = callback.vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6);
    bool attached = false;
    if (state == JNI_EDETACHED) {
        if (callback.vm->AttachCurrentThread(&env, nullptr) != JNI_OK) {
            callback.attach_failed = true;
            return 2;
        }
        attached = true;
    } else if (state != JNI_OK) { callback.attach_failed = true; return 2; }
    jfloatArray block = env->NewFloatArray(static_cast<jsize>(count));
    jint result = 2;
    if (block) {
        env->SetFloatArrayRegion(block, 0, static_cast<jsize>(count), samples);
        if (!env->ExceptionCheck()) result = env->CallIntMethod(callback.sink, callback.method, block, static_cast<jlong>(first));
        env->DeleteLocalRef(block);
    }
    if (env->ExceptionCheck()) {
        jthrowable error = env->ExceptionOccurred();
        env->ExceptionClear();
        callback.error = static_cast<jthrowable>(env->NewGlobalRef(error));
        env->DeleteLocalRef(error);
        result = 2;
    }
    if (attached) callback.vm->DetachCurrentThread();
    return result;
}

bool begin(JNIEnv* env, jobject sink, Callback& callback) {
    if (!sink) { fail(env, "Audio callback is null"); return false; }
    if (env->GetJavaVM(&callback.vm) != JNI_OK) { fail(env, "Cannot get Java VM"); return false; }
    jclass type = env->GetObjectClass(sink);
    callback.method = env->GetMethodID(type, "onAudio", "([FJ)I");
    env->DeleteLocalRef(type);
    if (!callback.method) return false;
    callback.sink = env->NewGlobalRef(sink);
    return callback.sink != nullptr;
}

void finish(JNIEnv* env, Callback& callback, int result) {
    env->DeleteGlobalRef(callback.sink);
    if (callback.error) {
        env->Throw(callback.error);
        env->DeleteGlobalRef(callback.error);
    } else if (callback.attach_failed) fail(env, "Cannot attach audio callback thread");
    else if (result < 0) fail(env, pocket_last_error());
}
}

extern "C" JNIEXPORT jlong JNICALL Java_nl_user_pockettest_PocketNative_create(JNIEnv* env, jobject, jbyteArray path, jbyteArray settings) {
    try {
        std::string p, s;
        if (!utf8(env, path, p) || !utf8(env, settings, s)) return 0;
        const auto handle = pocket_create(p.c_str(), s.c_str());
        if (!handle) fail(env, pocket_last_error());
        return static_cast<jlong>(handle);
    } catch (const std::exception& error) { fail(env, error.what()); return 0; }
}

extern "C" JNIEXPORT void JNICALL Java_nl_user_pockettest_PocketNative_selectVoice(JNIEnv* env, jobject, jlong handle, jbyteArray path) {
    try {
        std::string p;
        if (utf8(env, path, p) && pocket_select_voice(static_cast<uint64_t>(handle), p.c_str()) != 0) fail(env, pocket_last_error());
    } catch (const std::exception& error) { fail(env, error.what()); }
}

extern "C" JNIEXPORT jint JNICALL Java_nl_user_pockettest_PocketNative_generate(JNIEnv* env, jobject, jlong handle, jbyteArray text, jobject sink) {
    try {
        std::string value;
        if (!utf8(env, text, value)) return -1;
        Callback callback;
        if (!begin(env, sink, callback)) return -1;
        const int result = pocket_generate(static_cast<uint64_t>(handle), value.c_str(), audio, &callback);
        finish(env, callback, result);
        return result;
    } catch (const std::exception& error) { fail(env, error.what()); return -1; }
}

extern "C" JNIEXPORT void JNICALL Java_nl_user_pockettest_PocketNative_stop(JNIEnv* env, jobject, jlong handle) {
    if (pocket_stop(static_cast<uint64_t>(handle)) != 0) fail(env, pocket_last_error());
}
extern "C" JNIEXPORT void JNICALL Java_nl_user_pockettest_PocketNative_close(JNIEnv* env, jobject, jlong handle) {
    if (pocket_destroy(static_cast<uint64_t>(handle)) != 0) fail(env, pocket_last_error());
}
extern "C" JNIEXPORT jint JNICALL Java_nl_user_pockettest_PocketNative_sampleRate(JNIEnv* env, jobject, jlong handle) {
    const auto rate = pocket_sample_rate(static_cast<uint64_t>(handle));
    if (!rate) fail(env, pocket_last_error());
    return static_cast<jint>(rate);
}

// This probe tests callback attachment and exceptions without loading the model.
extern "C" JNIEXPORT jint JNICALL Java_nl_user_pockettest_PocketNative_probe(JNIEnv* env, jobject, jobject sink) {
    try {
        Callback callback;
        if (!begin(env, sink, callback)) return -1;
        int result = 0;
        std::thread worker([&] {
            std::vector<float> samples(4096, 0.0f);
            result = audio(samples.data(), samples.size(), 0, &callback);
            if (result == 0) result = audio(samples.data(), 17, 4096, &callback);
        });
        worker.join();
        finish(env, callback, result);
        return result;
    } catch (const std::exception& error) { fail(env, error.what()); return -1; }
}
