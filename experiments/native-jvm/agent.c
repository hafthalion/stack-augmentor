#include <jvmti.h>
#include <string.h>
#include <stdint.h>
#include <stdio.h>

static jvmtiEnv *jvmti;
static jclass hookClass;
static jmethodID hookFrame;
static __thread int inCallback;
static int noLocals, noJava;

#define CACHE 8192
static jmethodID keys[CACHE];
static int kinds[CACHE]; /* 0 unknown, 1 none, 2 Svc.call, 3 Child.<init> */

static int kindOf(jmethodID m) {
    uintptr_t h = ((uintptr_t) m >> 3) & (CACHE - 1);
    for (;;) {
        if (keys[h] == m) return kinds[h];
        if (keys[h] == NULL) break;
        h = (h + 1) & (CACHE - 1);
    }
    jclass cls; char *sig, *name;
    int kind = 1;
    (*jvmti)->GetMethodDeclaringClass(jvmti, m, &cls);
    (*jvmti)->GetClassSignature(jvmti, cls, &sig, NULL);
    (*jvmti)->GetMethodName(jvmti, m, &name, NULL, NULL);
    if (!strcmp(sig, "LProto$Svc;") && !strcmp(name, "call")) kind = 2;
    if (!strcmp(sig, "LProto$Child;") && !strcmp(name, "<init>")) kind = 3;
    (*jvmti)->Deallocate(jvmti, (unsigned char *) sig);
    (*jvmti)->Deallocate(jvmti, (unsigned char *) name);
    kinds[h] = kind; keys[h] = m; /* single-threaded prototype */
    return kind;
}

static void JNICALL onException(jvmtiEnv *env, JNIEnv *jni, jthread thread, jmethodID method, jlocation location,
                                jobject exception, jmethodID catchMethod, jlocation catchLocation) {
    if (inCallback || hookFrame == NULL) return;
    inCallback = 1;
    jvmtiFrameInfo frames[512];
    jint count;
    if ((*env)->GetStackTrace(env, thread, 0, 512, frames, &count) == JVMTI_ERROR_NONE) {
        for (jint i = 0; i < count; i++) {
            int kind = kindOf(frames[i].method);
            if (kind < 2) continue;
            jobject self = NULL, p = NULL; jint n = -1; jvmtiError err;
            if (noLocals) { if (!noJava) (*jni)->CallStaticVoidMethod(jni, hookClass, hookFrame, exception, i, NULL, n, NULL); continue; }
            err = (*env)->GetLocalInstance(env, thread, i, &self);
            if (err != JVMTI_ERROR_NONE) self = NULL;
            if (kind == 2) (*env)->GetLocalInt(env, thread, i, 1, &n);
            else (*env)->GetLocalObject(env, thread, i, 1, &p);
            (*jni)->CallStaticVoidMethod(jni, hookClass, hookFrame, exception, i, kind == 3 ? NULL : self, n, p);
            if ((*jni)->ExceptionCheck(jni)) (*jni)->ExceptionClear(jni);
            if (self) (*jni)->DeleteLocalRef(jni, self);
            if (p) (*jni)->DeleteLocalRef(jni, p);
        }
    }
    inCallback = 0;
}

static void JNICALL onVMInit(jvmtiEnv *env, JNIEnv *jni, jthread thread) {
    jclass c = (*jni)->FindClass(jni, "Proto$Hook");
    if (c == NULL) { (*jni)->ExceptionClear(jni); return; }
    hookClass = (*jni)->NewGlobalRef(jni, c);
    hookFrame = (*jni)->GetStaticMethodID(jni, c, "frame", "(Ljava/lang/Throwable;ILjava/lang/Object;ILjava/lang/Object;)V");
}

JNIEXPORT jint JNICALL Agent_OnLoad(JavaVM *vm, char *options, void *reserved) {
    (*vm)->GetEnv(vm, (void **) &jvmti, JVMTI_VERSION_11);
    jvmtiCapabilities caps; memset(&caps, 0, sizeof caps);
    caps.can_access_local_variables = !(options && strchr(options, 'C'));
    int events = !(options && !strcmp(options, "capsonly"));
    noLocals = options && strchr(options, 'L') != NULL;
    noJava = options && strchr(options, 'J') != NULL;
    caps.can_generate_exception_events = events;
    if ((*jvmti)->AddCapabilities(jvmti, &caps) != JVMTI_ERROR_NONE) return 1;
    jvmtiEventCallbacks cb; memset(&cb, 0, sizeof cb);
    cb.Exception = onException; cb.VMInit = onVMInit;
    (*jvmti)->SetEventCallbacks(jvmti, &cb, sizeof cb);
    (*jvmti)->SetEventNotificationMode(jvmti, JVMTI_ENABLE, JVMTI_EVENT_VM_INIT, NULL);
    if (events) (*jvmti)->SetEventNotificationMode(jvmti, JVMTI_ENABLE, JVMTI_EVENT_EXCEPTION, NULL);
    return 0;
}

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved) {
    jvmtiEnv *env;
    if ((*vm)->GetEnv(vm, (void **) &env, JVMTI_VERSION_11) != JNI_OK) { fprintf(stderr, "no jvmti\n"); return JNI_VERSION_1_8; }
    jvmtiCapabilities caps; memset(&caps, 0, sizeof caps);
    caps.can_access_local_variables = 1;
    jvmtiError err = (*env)->AddCapabilities(env, &caps);
    fprintf(stderr, "live-phase AddCapabilities(can_access_local_variables) -> %d\n", err);
    return JNI_VERSION_1_8;
}

/* Prefilter: how many frames of the current thread are configured, without touching locals. */
JNIEXPORT jint JNICALL Java_Proto_00024Hook_count(JNIEnv *jni, jclass cls) {
    jvmtiFrameInfo frames[1024];
    jint count, matches = 0;
    if ((*jvmti)->GetStackTrace(jvmti, NULL, 0, 1024, frames, &count) != JVMTI_ERROR_NONE) return -1;
    for (jint i = 0; i < count; i++) if (kindOf(frames[i].method) >= 2) matches++;
    return matches;
}
