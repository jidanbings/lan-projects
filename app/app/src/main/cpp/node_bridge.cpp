// Minimal JNI bridge that boots the bundled Node.js runtime (libnode.so).
//
// We only need to start the lan-projects server, so unlike nodejs-mobile's
// full channel bridge we do not embed any JS<->native messaging: we simply
// call node::Start() on a detached pthread, exactly as the node binary does.

#include <jni.h>
#include <pthread.h>
#include <cstdlib>
#include <cstring>
#include <vector>

#include "node.h"

namespace {

struct NodeArgs {
    int argc;
    char** argv;
};

void* runNode(void* p) {
    NodeArgs* na = static_cast<NodeArgs*>(p);
    int argc = na->argc;
    char** argv = na->argv;

    node::Start(argc, argv);

    for (int i = 0; i < argc; i++) {
        delete[] argv[i];
    }
    delete[] argv;
    delete na;
    return nullptr;
}

}  // namespace

extern "C" JNIEXPORT void JNICALL
Java_io_lanprojects_phone_NodeBridge_setEnv(
        JNIEnv* env, jobject /*this*/, jstring name, jstring value) {
    const char* n = env->GetStringUTFChars(name, nullptr);
    const char* v = env->GetStringUTFChars(value, nullptr);
    setenv(n, v, 1);
    env->ReleaseStringUTFChars(name, n);
    env->ReleaseStringUTFChars(value, v);
}

extern "C" JNIEXPORT void JNICALL
Java_io_lanprojects_phone_NodeBridge_startNodeWithArguments(
        JNIEnv* env, jobject /*this*/, jobjectArray arguments) {

    jsize argc = env->GetArrayLength(arguments);
    char** argv = new char*[argc];

    for (jsize i = 0; i < argc; i++) {
        jstring js = static_cast<jstring>(env->GetObjectArrayElement(arguments, i));
        const char* cstr = env->GetStringUTFChars(js, nullptr);
        size_t len = strlen(cstr);
        argv[i] = new char[len + 1];
        memcpy(argv[i], cstr, len + 1);
        env->ReleaseStringUTFChars(js, cstr);
        env->DeleteLocalRef(js);
    }

    NodeArgs* na = new NodeArgs{static_cast<int>(argc), argv};

    pthread_t thread;
    pthread_attr_t attr;
    pthread_attr_init(&attr);
    pthread_attr_setdetachstate(&attr, PTHREAD_CREATE_DETACHED);
    pthread_create(&thread, &attr, runNode, na);
    pthread_attr_destroy(&attr);
}
