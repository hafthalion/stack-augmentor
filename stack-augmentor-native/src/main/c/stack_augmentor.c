/*
 * Native part of the agent's live-stack mode: -agentpath:<this library>, next to -javaagent.
 *
 * It only asks the JVM, before anything is compiled, for the JVMTI capability can_access_local_variables. With it, the
 * JIT keeps every local variable of compiled code readable at calls, also after its last use, so the Java agent can read
 * the receiver and the arguments of each frame from the live stack when an exception is created. Without it, compiled
 * frames lose them. The capability can only be added while the JVM starts, which is why this cannot be part of the
 * agent jar. The JVM looks up the native methods of the agent's classes in the libraries of -agentpath too, so the agent
 * asks this library through NativeLibrary.version() whether the capability is in place.
 */
#include <jvmti.h>
#include <stdio.h>
#include <string.h>

/* Raised when what the agent expects of this library changes. */
#define VERSION 1

static jint version = 0;

JNIEXPORT jint JNICALL Java_com_hafnium_stackaugmentor_agent_NativeLibrary_version(JNIEnv *env, jclass type) {
    return version;
}

JNIEXPORT jint JNICALL Agent_OnLoad(JavaVM *vm, char *options, void *reserved) {
    jvmtiEnv *jvmti;
    jvmtiCapabilities capabilities;
    jvmtiError error;

    if ((*vm)->GetEnv(vm, (void **) &jvmti, JVMTI_VERSION_1_2) != JNI_OK) {
        fprintf(stderr, "[stack-augmentor] ERROR native: this JVM has no JVMTI, so the agent cannot read frames from the live stack\n");
        return JNI_OK;
    }
    memset(&capabilities, 0, sizeof capabilities);
    capabilities.can_access_local_variables = 1;
    error = (*jvmti)->AddCapabilities(jvmti, &capabilities);
    if (error != JVMTI_ERROR_NONE) {
        fprintf(stderr, "[stack-augmentor] ERROR native: cannot keep local variables readable (JVMTI error %d), so the agent "
                        "cannot read frames from the live stack\n", (int) error);
        return JNI_OK;
    }
    version = VERSION;
    /* Never stop the JVM: without the capability, the agent instruments classes as usual. */
    return JNI_OK;
}
