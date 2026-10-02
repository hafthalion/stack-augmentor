package com.hafnium.it.report

import com.hafnium.stackaugmentor.runtime.AugmentorConfig
import com.hafnium.stackaugmentor.runtime.IdSpec
import java.nio.file.Path

/**
 * The [augment.receiver] entries of a configuration file, matched by the agent's own [AugmentorConfig]: the classes
 * come from the agent jar at runtime, so this only works in tests that run with the agent.
 */
class ReceiverEntries(private val config: AugmentorConfig) {

    /** An entry, as written in the file. */
    data class Entry(val key: String, val value: String)

    /** The entry that decides for the class, then the other entries that match its name too. */
    fun matching(className: String): List<Entry> {
        val deciding = config.classEntry(className) ?: return emptyList()
        val others = config.classes().filter { (key, spec) ->
            key != deciding.key() && AugmentorConfig.builder().classes(mapOf(key to spec)).build().classEntry(className) != null
        }
        return listOf(Entry(deciding.key(), text(deciding.spec()))) + others.map { (key, spec) -> Entry(key, text(spec)) }
    }

    private fun text(spec: IdSpec) = AugmentorConfig.specText(spec)

    companion object {
        fun load(file: Path) = ReceiverEntries(AugmentorConfig.load(file))
    }
}
