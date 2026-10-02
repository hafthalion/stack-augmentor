package com.hafnium.it

/**
 * The agent's mode in this test run: `testLiveStack` loads the native library, so the agent reads frames from the live
 * stack instead of instrumenting classes. The modes differ in which frames get ids; the tests say where.
 */
object AgentMode {
    val liveStack: Boolean = System.getProperty("stackaugmentor.it.mode") == "live"
}
