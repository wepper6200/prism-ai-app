#pragma once
// Call-local value adapter for the shared native pipeline. No JVM or JNI runtime on Apple.
#include "prism_native.h"
#include <cstdint>
#include <cstring>
#include <memory>
#include <string>
#include <vector>
using jint = int32_t;
using jlong = int64_t;
using jsize = int32_t;
using jbyte = int8_t;
using jfloat = float;
using jboolean = bool;
constexpr bool JNI_TRUE = true;
constexpr bool JNI_FALSE = false;
#define JNIEXPORT
#define JNICALL
struct CallValue { virtual ~CallValue() = default; virtual int size() const { return 0; } };
template<class T> struct CallArray : CallValue {
    std::vector<T> data;
    explicit CallArray(int n = 0) : data(n) {}
    int size() const override { return static_cast<int>(data.size()); }
};
struct CallString : CallValue { std::string text; explicit CallString(const char * s) : text(s ? s : "") {} };
struct CallListener : CallValue {
    prism_progress_fn progress = nullptr;
    prism_token_fn token = nullptr;
    prism_prefill_fn prefill = nullptr;
    void * user = nullptr;
};
using jobject = CallValue *;
using jclass = CallValue *;
using jstring = CallString *;
using jbyteArray = CallArray<jbyte> *;
using jintArray = CallArray<jint> *;
using jfloatArray = CallArray<jfloat> *;
using jobjectArray = CallArray<jobject> *;
using jmethodID = const char *;
struct JNIEnv {
    // Values live until the synchronous C call returns, including callback byte buffers.
    std::vector<std::unique_ptr<CallValue>> values;
    template<class T, class... A> T * make(A... args) {
        auto value = std::make_unique<T>(args...);
        auto * result = value.get();
        values.push_back(std::move(value));
        return result;
    }
    jsize GetArrayLength(jobject a) { return a ? a->size() : 0; }
    void GetByteArrayRegion(jbyteArray a, int start, int n, jbyte * out) { if (n) std::memcpy(out, a->data.data() + start, n); }
    void GetIntArrayRegion(jintArray a, int start, int n, jint * out) { if (n) std::memcpy(out, a->data.data() + start, n * sizeof(jint)); }
    void SetByteArrayRegion(jbyteArray a, int start, int n, const jbyte * in) { if (n) std::memcpy(a->data.data() + start, in, n); }
    void SetFloatArrayRegion(jfloatArray a, int start, int n, const jfloat * in) { if (n) std::memcpy(a->data.data() + start, in, n * sizeof(jfloat)); }
    jbyteArray NewByteArray(int n) { return make<CallArray<jbyte>>(n); }
    jfloatArray NewFloatArray(int n) { return make<CallArray<jfloat>>(n); }
    jobjectArray NewObjectArray(int n, jclass, jobject) { return make<CallArray<jobject>>(n); }
    jobject GetObjectArrayElement(jobjectArray a, int i) { return a->data[i]; }
    void SetObjectArrayElement(jobjectArray a, int i, jobject v) { a->data[i] = v; }
    jstring NewStringUTF(const char * s) { return make<CallString>(s); }
    const char * GetStringUTFChars(jstring s, void *) { return s->text.c_str(); }
    void ReleaseStringUTFChars(jstring, const char *) {}
    jclass FindClass(const char *) { return nullptr; }
    jclass GetObjectClass(jobject) { return nullptr; }
    jmethodID GetMethodID(jclass, const char * name, const char *) { return name; }
    bool CallBooleanMethod(jobject o, jmethodID, float p) { auto * v = static_cast<CallListener *>(o); return !v->progress || v->progress(v->user, p) != 0; }
    bool CallBooleanMethod(jobject o, jmethodID, int a, int b) { auto * v = static_cast<CallListener *>(o); return !v->prefill || v->prefill(v->user, a, b) != 0; }
    bool CallBooleanMethod(jobject o, jmethodID, jbyteArray bytes) { auto * v = static_cast<CallListener *>(o); return !v->token || v->token(v->user, bytes->data.data(), bytes->size()) != 0; }
    bool ExceptionCheck() { return false; }
    void DeleteLocalRef(jobject) {}
};
