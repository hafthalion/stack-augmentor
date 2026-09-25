package com.hafnium.stackaugmentor.agent

import org.tomlj.Toml
import org.tomlj.TomlArray
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties

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
            "include", "fallback", "maxIdLength", "frameFormat", "receiverFormat", "paramsFormat", "debug",
        )
        private val IDENTIFIER = Regex("[\\p{L}_$][\\p{L}\\p{N}_$]*")

        /**
         * Loads the configuration named by the agent arguments (`config=<path>` or just `<path>`),
         * or by the `stackaugmentor.config` system property. Without either, the defaults apply.
         * A `.toml` file is read as TOML, anything else as a properties file.
         */
        fun load(agentArgs: String?): AugmentorConfig {
            val location = agentArgs?.trim()?.takeIf { it.isNotEmpty() }?.removePrefix("config=")
                ?: System.getProperty(CONFIG_PROPERTY)?.takeIf { it.isNotBlank() }
                ?: return AugmentorConfig()
            val path = Path.of(location)
            if (!Files.isRegularFile(path)) throw ConfigException("Configuration file not found: $path")
            if (path.fileName.toString().endsWith(".toml", ignoreCase = true)) {
                return parseToml(Files.readString(path), path.toString())
            }
            val properties = Properties()
            Files.newBufferedReader(path).use(properties::load)
            return parse(properties)
        }

        /**
         * Parses a TOML configuration. It is flattened into the same keys as the properties format,
         * e.g. `"com.acme.Order"` in the `[id]` table becomes `id.com.acme.Order`; arrays become lists.
         */
        fun parseToml(text: String, source: String = "TOML configuration"): AugmentorConfig {
            val toml = Toml.parse(text)
            if (toml.hasErrors()) {
                throw ConfigException("Invalid TOML in $source: ${toml.errors().joinToString("; ")}")
            }
            val properties = Properties()
            // Key paths make quoted ("com.acme.Order") and unquoted (com.acme.Order) class names equivalent.
            for (path in toml.keyPathSet()) {
                val key = path.joinToString(".")
                properties.setProperty(key, tomlValue(key, toml.get(path)))
            }
            return parse(properties)
        }

        private fun tomlValue(key: String, value: Any?): String = when (value) {
            is String -> value
            is Long, is Boolean -> value.toString()
            is TomlArray -> value.toList().joinToString(",") { item ->
                when {
                    item is String -> item
                    item is Long && key.startsWith("param.") -> "#$item" // parameter index
                    else -> throw ConfigException("'$key': unsupported array element '$item'")
                }
            }
            else -> throw ConfigException("'$key': unsupported value '$value'")
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
