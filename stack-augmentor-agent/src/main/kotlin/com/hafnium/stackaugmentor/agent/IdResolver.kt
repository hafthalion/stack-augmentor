package com.hafnium.stackaugmentor.agent

import java.lang.reflect.AnnotatedElement
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/** Name of the id annotation. Matched by name, because the application may load its own copy of the API. */
const val STACK_TRACE_ID = "com.hafnium.stackaugmentor.StackTraceId"

/**
 * Turns objects into ids. The id source of each class is looked up once and cached. Every id is
 * converted to a String right away, so no references to live objects are kept.
 */
class IdResolver(private val config: AugmentorConfig) {

    private sealed interface Source {
        val name: String
        val description: String
        fun read(target: Any): Any?
    }

    private class FieldSource(private val field: Field, override val name: String) : Source {
        override val description get() = "field ${field.declaringClass.name}.${field.name}, label '$name'"
        override fun read(target: Any): Any? = field.get(target)
    }

    private class MethodSource(private val method: Method, override val name: String) : Source {
        override val description get() = "method ${method.declaringClass.name}.${method.name}(), label '$name'"
        override fun read(target: Any): Any? = method.invoke(target)
    }

    private val sources = object : ClassValue<Source?>() {
        override fun computeValue(type: Class<*>): Source? = findSource(type).also { source ->
            Log.debug { "id source of ${type.name}: ${source?.description ?: "none"}" }
        }
    }

    private val lineBreaks = Regex("[\\r\\n]+")

    /** The id of the object a frame runs on, or `null` if its class has no configured or annotated id source. */
    fun receiverId(target: Any): NamedId? =
        sources.get(target.javaClass)?.let { NamedId(it.name, read(it, target)) }

    /** The id of an argument: its class's id source if it has one, otherwise its text. */
    fun paramId(value: Any?): String {
        if (value == null) return "null"
        val source = when (value) {
            is CharSequence, is Number, is Boolean, is Char, is Enum<*> -> null
            else -> sources.get(value.javaClass)
        }
        if (source != null) return read(source, value)
        return guarded {
            if (value.javaClass.isArray) java.util.Arrays.deepToString(arrayOf(value)).removeSurrounding("[", "]") else value.toString()
        }
    }

    private fun read(source: Source, target: Any): String = guarded { source.read(target)?.toString() ?: "null" }

    private inline fun guarded(block: () -> String): String {
        val text = try {
            block()
        } catch (_: Throwable) {
            return "?"
        }
        return sanitize(text)
    }

    private fun sanitize(text: String): String {
        val singleLine = if (text.contains('\n') || text.contains('\r')) text.replace(lineBreaks, " ") else text
        return if (singleLine.length > config.maxIdLength) singleLine.take(config.maxIdLength - 1) + "…" else singleLine
    }

    private fun findSource(type: Class<*>): Source? {
        // 1. External configuration, which also covers subclasses of a configured class.
        hierarchy(type).forEach { owner ->
            config.ids[owner.name]?.let { spec -> return sourceFor(owner, spec) }
        }
        // 2. @StackTraceId on a field, a no-argument method or (Kotlin) a primary constructor property,
        //    for classes in the augmentAnnotatedClasses packages.
        if (!config.honoursAnnotations(type.name)) return null
        hierarchy(type).forEach { owner ->
            owner.declaredFields.firstOrNull { !Modifier.isStatic(it.modifiers) && idAnnotation(it) != null }?.let {
                return fieldSource(it, label(idAnnotation(it), it.name))
            }
            owner.declaredMethods.firstOrNull {
                !Modifier.isStatic(it.modifiers) && it.parameterCount == 0 && !it.isSynthetic && idAnnotation(it) != null
            }?.let { return methodSource(it, label(idAnnotation(it), it.name)) }
            for (constructor in owner.declaredConstructors) {
                for (parameter in constructor.parameters) {
                    val annotation = idAnnotation(parameter) ?: continue
                    if (!parameter.isNamePresent) continue
                    owner.declaredFields.firstOrNull { it.name == parameter.name && !Modifier.isStatic(it.modifiers) }?.let {
                        return fieldSource(it, label(annotation, it.name))
                    }
                }
            }
        }
        return null
    }

    private fun sourceFor(owner: Class<*>, spec: IdSpec): Source? {
        val source = when (spec) {
            is IdSpec.FieldSpec -> hierarchy(owner).firstNotNullOfOrNull { type ->
                type.declaredFields.firstOrNull { it.name == spec.memberName && !Modifier.isStatic(it.modifiers) }
            }?.let { fieldSource(it, it.name) }
            is IdSpec.MethodSpec -> hierarchy(owner).firstNotNullOfOrNull { type ->
                type.declaredMethods.firstOrNull { it.name == spec.memberName && it.parameterCount == 0 && !Modifier.isStatic(it.modifiers) }
            }?.let { methodSource(it, it.name) }
        }
        if (source == null) {
            Log.warn { "[augmentClassIds] \"${owner.name}\": no ${if (spec is IdSpec.MethodSpec) "method ${spec.memberName}()" else "field ${spec.memberName}"} found" }
        }
        return source
    }

    private fun fieldSource(field: Field, name: String): Source? =
        if (field.trySetAccessible()) FieldSource(field, name) else null.also { Log.warn { "cannot access $field" } }

    private fun methodSource(method: Method, name: String): Source? =
        if (method.trySetAccessible()) MethodSource(method, name) else null.also { Log.warn { "cannot access $method" } }

    private fun hierarchy(type: Class<*>): Sequence<Class<*>> =
        generateSequence(type) { it.superclass }.takeWhile { it != Any::class.java }

    companion object {
        fun idAnnotation(element: AnnotatedElement): Annotation? =
            element.declaredAnnotations.firstOrNull { it.annotationClass.java.name == STACK_TRACE_ID }

        /** The annotation's `name` if set, otherwise [default]. */
        fun label(annotation: Annotation?, default: String): String {
            val name = try {
                annotation?.annotationClass?.java?.getMethod("name")?.invoke(annotation) as? String
            } catch (_: ReflectiveOperationException) {
                null
            }
            return if (name.isNullOrEmpty()) default else name
        }
    }
}
