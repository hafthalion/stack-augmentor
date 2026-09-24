package com.hafnium.stackaugmentor.agent

import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties

enum class Mode { REWRITE, REGISTRY }

enum class Fallback { TO_STRING, IDENTITY, NONE }

/** Where a receiver id comes from, for classes configured externally. */
sealed interface IdSpec {
    val memberName: String

    data class FieldSpec(override val memberName: String) : IdSpec
    data class MethodSpec(override val memberName: String) : IdSpec
}

/** Selects a parameter of a configured method, by name or by position. */
sealed interface ParamRef {
    data class ByName(val name: String) : ParamRef
    data class ByIndex(val index: Int) : ParamRef
}

class ConfigException(message: String) : IllegalArgumentException(message)

data class AugmentorConfig(
    val include: List<String> = emptyList(),
    val mode: Mode = Mode.REWRITE,
    val fallback: Fallback = Fallback.TO_STRING,
    val maxIdLength: Int = 64,
    val frameFormat: String = DEFAULT_FRAME_FORMAT,
    val receiverFormat: String = DEFAULT_RECEIVER_FORMAT,
    val paramsFormat: String = DEFAULT_PARAMS_FORMAT,
    /** Receiver id sources by class name. */
    val ids: Map<String, IdSpec> = emptyMap(),
    /** Parameter ids by `className.methodName`. */
    val params: Map<String, List<ParamRef>> = emptyMap(),
    val debug: Boolean = false,
) {
    private val includePatterns: List<Regex> = include.map(::globToRegex)

    fun isIncluded(className: String): Boolean = includePatterns.any { it.matches(className) }

    fun paramRefs(className: String, methodName: String): List<ParamRef> =
        params["$className.$methodName"].orEmpty()

    fun hasParamEntries(className: String): Boolean = params.keys.any { it.startsWith("$className.") }

    companion object {
        const val DEFAULT_FRAME_FORMAT = "{class}{receiver}.{method}{params}"
        const val DEFAULT_RECEIVER_FORMAT = "[{name}={id}]"
        const val DEFAULT_PARAMS_FORMAT = "[{name}={id}, ...]"
        const val CONFIG_PROPERTY = "stackaugmentor.config"

        private val KNOWN_KEYS = setOf(
            "include", "mode", "fallback", "maxIdLength", "frameFormat", "receiverFormat", "paramsFormat", "debug",
        )
        private val IDENTIFIER = Regex("[\\p{L}_$][\\p{L}\\p{N}_$]*")

        /**
         * Loads the configuration named by the agent arguments (`config=<path>` or just `<path>`),
         * or by the `stackaugmentor.config` system property. Without either, the defaults apply.
         */
        fun load(agentArgs: String?): AugmentorConfig {
            val location = agentArgs?.trim()?.takeIf { it.isNotEmpty() }?.removePrefix("config=")
                ?: System.getProperty(CONFIG_PROPERTY)?.takeIf { it.isNotBlank() }
                ?: return AugmentorConfig()
            val path = Path.of(location)
            if (!Files.isRegularFile(path)) throw ConfigException("Configuration file not found: $path")
            val properties = Properties()
            Files.newBufferedReader(path).use(properties::load)
            return parse(properties)
        }

        fun parse(properties: Properties): AugmentorConfig {
            val ids = mutableMapOf<String, IdSpec>()
            val params = mutableMapOf<String, List<ParamRef>>()
            for (key in properties.stringPropertyNames()) {
                val value = properties.getProperty(key).trim()
                when {
                    key.startsWith("id.") -> ids[key.removePrefix("id.").requireClassName(key)] = parseIdSpec(key, value)
                    key.startsWith("param.") -> {
                        val target = key.removePrefix("param.")
                        if (target.lastIndexOf('.') <= 0) {
                            throw ConfigException("'$key' must name a class and a method, e.g. param.com.acme.OrderService.process")
                        }
                        params[target] = parseParamRefs(key, value)
                    }
                    key !in KNOWN_KEYS -> throw ConfigException("Unknown configuration key '$key'")
                }
            }
            val defaults = AugmentorConfig()
            return AugmentorConfig(
                include = properties.getProperty("include")?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }
                    ?: defaults.include,
                mode = properties.getProperty("mode")?.let { parseMode(it.trim()) } ?: defaults.mode,
                fallback = properties.getProperty("fallback")?.let { parseFallback(it.trim()) } ?: defaults.fallback,
                maxIdLength = properties.getProperty("maxIdLength")?.let { parseMaxIdLength(it.trim()) }
                    ?: defaults.maxIdLength,
                frameFormat = properties.getProperty("frameFormat") ?: defaults.frameFormat,
                receiverFormat = properties.getProperty("receiverFormat") ?: defaults.receiverFormat,
                paramsFormat = properties.getProperty("paramsFormat") ?: defaults.paramsFormat,
                ids = ids,
                params = params,
                debug = properties.getProperty("debug")?.trim().toBoolean(),
            )
        }

        private fun String.requireClassName(key: String): String {
            if (isEmpty()) throw ConfigException("'$key' must name a class, e.g. id.com.acme.Order")
            return this
        }

        private fun parseIdSpec(key: String, value: String): IdSpec {
            val method = value.endsWith("()")
            val name = value.removeSuffix("()")
            if (!IDENTIFIER.matches(name)) {
                throw ConfigException("'$key' must be a field name (e.g. orderId) or a method (e.g. getOrderId()), was '$value'")
            }
            return if (method) IdSpec.MethodSpec(name) else IdSpec.FieldSpec(name)
        }

        private fun parseParamRefs(key: String, value: String): List<ParamRef> {
            val refs = value.split(',').map { it.trim() }.filter { it.isNotEmpty() }.map { ref ->
                if (ref.startsWith("#")) {
                    val index = ref.substring(1).toIntOrNull()
                    if (index == null || index < 0) throw ConfigException("'$key': invalid parameter index '$ref'")
                    ParamRef.ByIndex(index)
                } else {
                    if (!IDENTIFIER.matches(ref)) throw ConfigException("'$key': invalid parameter name '$ref'")
                    ParamRef.ByName(ref)
                }
            }
            if (refs.isEmpty()) throw ConfigException("'$key' must list parameter names or #indexes")
            return refs
        }

        private fun parseMode(value: String): Mode = when (value.lowercase()) {
            "rewrite" -> Mode.REWRITE
            "registry" -> Mode.REGISTRY
            else -> throw ConfigException("mode must be 'rewrite' or 'registry', was '$value'")
        }

        private fun parseFallback(value: String): Fallback = when (value.lowercase()) {
            "tostring" -> Fallback.TO_STRING
            "identity" -> Fallback.IDENTITY
            "none" -> Fallback.NONE
            else -> throw ConfigException("fallback must be 'toString', 'identity' or 'none', was '$value'")
        }

        private fun parseMaxIdLength(value: String): Int {
            val length = value.toIntOrNull()
            if (length == null || length < 2) throw ConfigException("maxIdLength must be a number >= 2, was '$value'")
            return length
        }

        /** `*` matches within one package segment, `**` across segments, `?` one character. */
        internal fun globToRegex(glob: String): Regex {
            val regex = StringBuilder()
            var i = 0
            while (i < glob.length) {
                val c = glob[i]
                when {
                    c == '*' && i + 1 < glob.length && glob[i + 1] == '*' -> {
                        regex.append(".*")
                        i++
                    }
                    c == '*' -> regex.append("[^.]*")
                    c == '?' -> regex.append("[^.]")
                    else -> regex.append(Regex.escape(c.toString()))
                }
                i++
            }
            return Regex(regex.toString())
        }
    }
}
