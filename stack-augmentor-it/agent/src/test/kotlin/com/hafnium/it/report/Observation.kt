package com.hafnium.it.report

import java.lang.reflect.Modifier

/** One call a test made: the frames of the exception it threw, and what reflection says about its receiver. */
class Observation(
    val category: String,
    val test: String,
    val call: String,
    val frames: List<String>,
    val types: List<TypeInfo>,
    val declarations: List<Declaration>,
)

/** A type in the receiver's hierarchy. */
class TypeInfo(
    /** "interface", "enum", "abstract class", "open class" or "class". */
    val kind: String,
    val name: String,
    /** The superclass (other than Object) first, then the interfaces it implements directly. */
    val supertypes: List<String>,
    val isInterface: Boolean,
    /** A body of an enum constant, e.g. Priority$HIGH. */
    val isEnumConstant: Boolean,
    val isAnonymous: Boolean,
    /** The class this inner or nested class is declared in. */
    val enclosing: String?,
    /** Defined at runtime, e.g. a proxy: its class loader has no class file for it. */
    val isGenerated: Boolean,
) {
    companion object {
        fun of(type: Class<*>): TypeInfo {
            val enumConstant = type.superclass?.isEnum == true
            return TypeInfo(
                kind = when {
                    type.isInterface -> "interface"
                    type.isEnum || enumConstant -> "enum"
                    Modifier.isAbstract(type.modifiers) -> "abstract class"
                    Modifier.isFinal(type.modifiers) -> "class"
                    else -> "open class"
                },
                name = type.name,
                supertypes = (listOfNotNull(type.superclass?.takeIf { it != Any::class.java }) + type.interfaces).map { it.name },
                isInterface = type.isInterface,
                isEnumConstant = enumConstant,
                isAnonymous = type.isAnonymousClass,
                enclosing = type.enclosingClass?.takeIf { !type.isAnonymousClass && !enumConstant }?.name,
                isGenerated = type.classLoader?.getResource(type.name.replace('.', '/') + ".class") == null && !type.name.startsWith("java."),
            )
        }
    }
}

/** A declaration of the called method in one of the receiver's types. */
class Declaration(val owner: String, val name: String, val parameterTypes: List<String>, val modifiers: Set<String>) {

    override fun toString() = "$owner.$name(${parameterTypes.joinToString(", ")})" +
        if (modifiers.isEmpty()) "" else " [" + modifiers.joinToString(", ") + "]"

    companion object {
        fun of(owner: Class<*>, method: java.lang.reflect.Method) = Declaration(
            owner.name,
            method.name,
            method.parameterTypes.map { it.simpleName },
            listOfNotNull(
                "bridge".takeIf { method.isBridge },
                "synthetic".takeIf { method.isSynthetic && !method.isBridge },
                "abstract".takeIf { Modifier.isAbstract(method.modifiers) },
                "default".takeIf { method.isDefault },
                "static".takeIf { Modifier.isStatic(method.modifiers) },
                "final".takeIf { Modifier.isFinal(method.modifiers) },
            ).toSet(),
        )
    }
}

/** The class, then its superclasses, each followed by the interfaces it implements, directly or not. */
internal fun hierarchyOf(type: Class<*>): Set<Class<*>> {
    val types = LinkedHashSet<Class<*>>()
    generateSequence(type) { it.superclass }.forEach { c ->
        types += c
        generateSequence(c.interfaces.toList()) { level -> level.flatMap { it.interfaces.toList() }.ifEmpty { null } }.forEach { types += it }
    }
    return types
}
