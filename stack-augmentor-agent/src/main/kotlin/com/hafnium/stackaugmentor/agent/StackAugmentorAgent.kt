package com.hafnium.stackaugmentor.agent

import java.lang.instrument.Instrumentation
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat
import java.util.concurrent.atomic.AtomicBoolean
import java.util.jar.JarFile

/**
 * Agent entry point: `-javaagent:stack-augmentor-agent.jar[=config=<path>]`.
 *
 * This class must not touch the bridge classes: they only become loadable once [BridgeInjector]
 * has appended the bridge jar to the bootstrap class loader.
 */
object StackAugmentorAgent {

    private val started = AtomicBoolean()

    @JvmStatic
    fun premain(agentArgs: String?, instrumentation: Instrumentation) = start(agentArgs, instrumentation)

    @JvmStatic
    fun agentmain(agentArgs: String?, instrumentation: Instrumentation) = start(agentArgs, instrumentation)

    private fun start(agentArgs: String?, instrumentation: Instrumentation) {
        if (!started.compareAndSet(false, true)) return
        // Invalid configuration stops the JVM here, with the reason in the message.
        val config = AugmentorConfig.load(agentArgs)
        val format = FrameFormat.create(config)
        BridgeInjector.inject(instrumentation)
        Installer.install(instrumentation, config, format)
    }
}

/** Extracts the embedded bridge jar and appends it to the bootstrap class loader search path. */
internal object BridgeInjector {

    private const val RESOURCE = "/META-INF/stack-augmentor/stack-augmentor-bridge.jar"
    private const val PROBE = "com.hafnium.stackaugmentor.bridge.Dispatch"

    fun inject(instrumentation: Instrumentation) {
        if (bootstrapHasBridge()) return
        val bytes = BridgeInjector::class.java.getResourceAsStream(RESOURCE)?.use { it.readAllBytes() }
            ?: error("$RESOURCE is missing from the agent jar")
        // Named after the content, so restarts reuse the file instead of leaving a new one each time.
        val hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes), 0, 8)
        val file = Path.of(System.getProperty("java.io.tmpdir"), "stack-augmentor-bridge-$hash.jar")
        if (!Files.isRegularFile(file) || Files.size(file) != bytes.size.toLong()) {
            val temp = Files.createTempFile(file.parent, "stack-augmentor-bridge", ".tmp")
            Files.write(temp, bytes)
            try {
                Files.move(temp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
            } catch (_: java.io.IOException) {
                Files.deleteIfExists(temp) // another JVM wrote it concurrently, or it is in use
            }
        }
        instrumentation.appendToBootstrapClassLoaderSearch(JarFile(file.toFile()))
    }

    private fun bootstrapHasBridge(): Boolean = try {
        Class.forName(PROBE, false, null)
        true
    } catch (_: ClassNotFoundException) {
        false
    }
}
