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

    /** The entry that decides for the class, then the later entries that match it too. */
    fun matching(className: String): List<Entry> {
        val type = runCatching { Class.forName(className, false, javaClass.classLoader) }.getOrNull()
        fun entry(config: AugmentorConfig) =
            if (type != null) config.classEntry(type) else config.classEntry(className) { it == className }
        val deciding = entry(config) ?: return emptyList()
        val others = config.classes().filter { (key, spec) ->
            key != deciding.key() && entry(AugmentorConfig.builder().classes(mapOf(key to spec)).build()) != null
        }
        return listOf(Entry(deciding.key(), text(deciding.spec()))) + others.map { (key, spec) -> Entry(key, text(spec)) }
    }

    private fun text(spec: IdSpec) = AugmentorConfig.specText(spec)

    companion object {
        fun load(file: Path) = ReceiverEntries(AugmentorConfig.load(file))
    }
}
