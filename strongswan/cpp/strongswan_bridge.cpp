// strongswan/cpp/strongswan_bridge.cpp
#include <jni.h>
#include <string>
#include <android/log.h>
#include <dlfcn.h>

#define LOG_TAG "StrongSwanBridge"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// Function pointer types
typedef int (*charon_start_t)();
typedef void (*charon_stop_t)();
typedef int (*load_connection_t)(const char*);
typedef int (*initiate_connection_t)(const char*);
typedef void (*terminate_connection_t)(const char*);

// Function pointers
static charon_start_t fn_charon_start = nullptr;
static charon_stop_t fn_charon_stop = nullptr;
static load_connection_t fn_load_connection = nullptr;
static initiate_connection_t fn_initiate_connection = nullptr;
static terminate_connection_t fn_terminate_connection = nullptr;

// Initialize function pointers
static bool init_function_pointers() {
    if (fn_charon_start != nullptr) {
        return true; // Already initialized
    }

    // Try to load the androidbridge library
    void* handle = dlopen("libandroidbridge.so", RTLD_LAZY);
    if (!handle) {
        LOGE("Failed to load libandroidbridge.so: %s", dlerror());
        return false;
    }

    // Clear any existing error
    dlerror();

    // First try with android_bridge_ prefix
    fn_charon_start = (charon_start_t)dlsym(handle, "android_bridge_charon_start");
    if (!fn_charon_start) {
        // Try without prefix
        fn_charon_start = (charon_start_t)dlsym(handle, "charon_start");
        if (!fn_charon_start) {
            LOGE("Could not find charon_start function: %s", dlerror());
            return false;
        }
    }

    // Same pattern for other functions
    fn_charon_stop = (charon_stop_t)dlsym(handle, "android_bridge_charon_stop");
    if (!fn_charon_stop) {
        fn_charon_stop = (charon_stop_t)dlsym(handle, "charon_stop");
        if (!fn_charon_stop) {
            LOGE("Could not find charon_stop function: %s", dlerror());
            return false;
        }
    }

    fn_load_connection = (load_connection_t)dlsym(handle, "android_bridge_load_connection");
    if (!fn_load_connection) {
        fn_load_connection = (load_connection_t)dlsym(handle, "load_connection");
        if (!fn_load_connection) {
            LOGE("Could not find load_connection function: %s", dlerror());
            return false;
        }
    }

    fn_initiate_connection = (initiate_connection_t)dlsym(handle, "android_bridge_initiate_connection");
    if (!fn_initiate_connection) {
        fn_initiate_connection = (initiate_connection_t)dlsym(handle, "initiate_connection");
        if (!fn_initiate_connection) {
            LOGE("Could not find initiate_connection function: %s", dlerror());
            return false;
        }
    }

    fn_terminate_connection = (terminate_connection_t)dlsym(handle, "android_bridge_terminate_connection");
    if (!fn_terminate_connection) {
        fn_terminate_connection = (terminate_connection_t)dlsym(handle, "terminate_connection");
        if (!fn_terminate_connection) {
            LOGE("Could not find terminate_connection function: %s", dlerror());
            return false;
        }
    }

    LOGD("Successfully loaded all StrongSwan bridge functions");
    return true;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_example_shadowlinkvpn_service_StrongSwanBridge_initializeCharon(
        JNIEnv *env, jobject thiz) {
    LOGD("Initializing Charon via bridge");

    if (!init_function_pointers()) {
        LOGE("Failed to initialize function pointers");
        return false;
    }

    int result = fn_charon_start();
    if (result != 0) {
        LOGE("Failed to start Charon daemon, error: %d", result);
        return false;
    }
    return true;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_example_shadowlinkvpn_service_StrongSwanBridge_loadConfig(
        JNIEnv *env, jobject thiz, jstring config_path) {
    if (!init_function_pointers()) {
        return false;
    }

    const char *path = env->GetStringUTFChars(config_path, 0);
    LOGD("Loading config from: %s", path);

    int result = fn_load_connection(path);

    env->ReleaseStringUTFChars(config_path, path);
    return result == 0;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_example_shadowlinkvpn_service_StrongSwanBridge_startConnection(
        JNIEnv *env, jobject thiz, jstring connection_name) {
    if (!init_function_pointers()) {
        return false;
    }

    const char *name = env->GetStringUTFChars(connection_name, 0);
    LOGD("Starting connection: %s", name);

    int result = fn_initiate_connection(name);

    env->ReleaseStringUTFChars(connection_name, name);
    return result == 0;
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_shadowlinkvpn_service_StrongSwanBridge_stopConnection(
        JNIEnv *env, jobject thiz, jstring connection_name) {
    if (!init_function_pointers()) {
        return;
    }

    const char *name = env->GetStringUTFChars(connection_name, 0);
    LOGD("Stopping connection: %s", name);

    fn_terminate_connection(name);

    env->ReleaseStringUTFChars(connection_name, name);
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_shadowlinkvpn_service_StrongSwanBridge_cleanup(
        JNIEnv *env, jobject thiz) {
    if (!init_function_pointers()) {
        return;
    }

    LOGD("Cleaning up Charon via bridge");
    fn_charon_stop();
}
