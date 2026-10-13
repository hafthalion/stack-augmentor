package com.hafnium.it.report

import com.hafnium.stackaugmentor.runtime.config.AugmentorConfig
import com.hafnium.stackaugmentor.runtime.config.IdSpec
import java.nio.file.Path

/**
 * The [augment.receiver] entries of a configuration file, matched by the agent's own [AugmentorConfig]: the classes
 * come from the agent jar at runtime, so this only works in tests that run with the agent.
 */
class ReceiverEntries(private val config: AugmentorConfig) {

    /** An entry, as written in the file. */
    data class Entry(val key: String, val value: String)

    /** The entries that match the class, in file order: for each method the first one that applies to it decides. */
    fun matching(className: String): List<Entry> {
        val type = runCatching { Class.forName(className, false, javaClass.classLoader) }.getOrNull()
        val matching = if (type != null) config.classEntries(type) else config.classEntries(className) { it == className }
        return matching.map { Entry(it.key(), text(it.spec())) }
    }

    private fun text(spec: IdSpec) = AugmentorConfig.specText(spec)

    companion object {
        fun load(file: Path) = ReceiverEntries(AugmentorConfig.load(file))
    }
}
