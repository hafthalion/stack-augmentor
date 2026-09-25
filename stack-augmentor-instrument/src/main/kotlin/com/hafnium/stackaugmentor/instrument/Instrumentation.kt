package com.hafnium.stackaugmentor.instrument

import com.hafnium.stackaugmentor.instrument.advice.ExitAdvice
import com.hafnium.stackaugmentor.instrument.advice.IdArgNames
import com.hafnium.stackaugmentor.instrument.advice.IdArgs
import com.hafnium.stackaugmentor.runtime.AugmentorConfig
import com.hafnium.stackaugmentor.runtime.Log
import com.hafnium.stackaugmentor.runtime.ParamRef
import com.hafnium.stackaugmentor.runtime.STACK_TRACE_ID
import net.bytebuddy.asm.Advice
import net.bytebuddy.description.annotation.AnnotationDescription
import net.bytebuddy.description.annotation.AnnotationList
import net.bytebuddy.description.method.MethodDescription
import net.bytebuddy.description.method.ParameterDescription
import net.bytebuddy.description.type.TypeDefinition
import net.bytebuddy.description.type.TypeDescription
import net.bytebuddy.implementation.bytecode.StackManipulation
import net.bytebuddy.implementation.bytecode.assign.Assigner
import net.bytebuddy.implementation.bytecode.constant.NullConstant
import net.bytebuddy.implementation.bytecode.constant.TextConstant
import net.bytebuddy.implementation.bytecode.member.MethodVariableAccess
import net.bytebuddy.matcher.ElementMatcher

/** A parameter whose value is shown after the method name, with its label. */
class IdParameter(val parameter: ParameterDescription, val label: String)

/** Finds the id parameters of a method: annotated with `@StackTraceId`, or listed in the `[instrument.methodParams]` config table. */
class IdParameters(private val config: AugmentorConfig) {

    fun select(type: TypeDescription, method: MethodDescription): List<IdParameter> {
        val parameters = method.parameters
        val labels = sortedMapOf<Int, String>()
        if (config.honoursAnnotations(type.name)) {
            for (parameter in parameters) {
                val annotation = idAnnotation(parameter.declaredAnnotations) ?: continue
                labels[parameter.index] = annotationLabel(annotation) ?: parameter.name
            }
        }
        for (ref in config.paramRefs(type.name, method.internalName)) {
            val parameter = when (ref) {
                is ParamRef.ByName -> parameters.firstOrNull { it.isNamed && it.name == ref.name }
                is ParamRef.ByIndex -> parameters.getOrNull(ref.index)
            } ?: continue
            labels.putIfAbsent(parameter.index, parameter.name)
        }
        // Without the MethodParameters attribute, ByteBuddy names parameters arg0, arg1, ...
        return labels.map { (index, label) -> IdParameter(parameters[index], label) }
    }

    /** Debug messages for `[instrument.methodParams]` entries of this type that match no method or parameter. */
    fun unmatchedEntries(type: TypeDescription): List<String> {
        val messages = mutableListOf<String>()
        for ((target, refs) in config.params) {
            if (target.substringBeforeLast('.') != type.name) continue
            val methodName = target.substringAfterLast('.')
            val entry = "[instrument.methodParams] \"$target\""
            val methods = type.declaredMethods.filter { it.isMethod && it.internalName == methodName }
            if (methods.isEmpty()) {
                messages += "$entry: ${type.name} has no method '$methodName'"
                continue
            }
            for (method in methods) {
                val signature = "$methodName(${method.parameters.joinToString { "${it.type.asErasure().simpleName} ${it.name}" }})"
                for (ref in refs) {
                    messages += when (ref) {
                        is ParamRef.ByIndex -> if (ref.index < method.parameters.size) continue else "$entry: no parameter #${ref.index} in $signature"
                        is ParamRef.ByName -> when {
                            method.parameters.any { it.isNamed && it.name == ref.name } -> continue
                            method.parameters.any { !it.isNamed } ->
                                "$entry: cannot find '${ref.name}' in $signature, the class has no parameter names (compile with -parameters, or use an index)"
                            else -> "$entry: no parameter '${ref.name}' in $signature"
                        }
                    }
                }
            }
        }
        return messages
    }

    private fun annotationLabel(annotation: AnnotationDescription): String? = try {
        annotation.getValue("name").resolve(String::class.java).takeIf { it.isNotEmpty() }
    } catch (_: RuntimeException) {
        null
    }
}

internal fun idAnnotation(annotations: AnnotationList): AnnotationDescription? =
    annotations.firstOrNull { it.annotationType.name == STACK_TRACE_ID }

/** Decides which types and methods get the exit advice. */
class TypeMatching(private val config: AugmentorConfig, private val parameters: IdParameters) {

    fun instrument(type: TypeDescription): Boolean {
        if (type.isAnnotation) return false
        val instrument = receiverRelevant(type) || type.declaredMethods.any { isCandidate(it) && parameters.select(type, it).isNotEmpty() }
        if (Log.debug) {
            parameters.unmatchedEntries(type).forEach { message -> Log.debug { message } }
            if (!instrument && !config.honoursAnnotations(type.name) && usesAnnotations(type)) {
                Log.debug { "ignoring @StackTraceId in ${type.name}: not in instrument.annotatedClasses ${config.annotatedClasses}" }
            }
        }
        return instrument
    }

    /** One line for the debug log: why the type is instrumented, and which methods get which parameter ids. */
    fun describe(type: TypeDescription, methods: ElementMatcher<MethodDescription>): String {
        val configured = hierarchy(type).firstOrNull { config.ids.containsKey(it.name) }
        val reason = when {
            configured != null -> "receiver id from [instrument.classIds] \"${configured.name}\""
            receiverRelevant(type) -> "receiver id from @StackTraceId"
            else -> "parameter ids only"
        }
        val instrumented = type.declaredMethods.filter { methods.matches(it) }.joinToString { method ->
            val params = parameters.select(type, method)
            if (params.isEmpty()) method.internalName else "${method.internalName}{${params.joinToString { it.label }}}"
        }
        return "instrumenting ${type.name} ($reason): $instrumented"
    }

    private fun usesAnnotations(type: TypeDescription): Boolean =
        hierarchy(type).any { hasAnnotatedMember(it) } ||
            type.declaredMethods.any { method -> method.parameters.any { idAnnotation(it.declaredAnnotations) != null } }

    /** Instance methods get a receiver id; other methods are only instrumented for their id parameters. */
    fun methods(type: TypeDescription): ElementMatcher<MethodDescription> {
        val receiver = receiverRelevant(type)
        return ElementMatcher { method ->
            isCandidate(method) && ((receiver && !method.isStatic) || parameters.select(type, method).isNotEmpty())
        }
    }

    /** Configured in `[instrument.classIds]`, or annotated (possibly in a superclass) in an `instrument.annotatedClasses` package. */
    private fun receiverRelevant(type: TypeDescription): Boolean =
        hierarchy(type).any { config.ids.containsKey(it.name) } ||
            (config.honoursAnnotations(type.name) && hierarchy(type).any { hasAnnotatedMember(it) })

    private fun isCandidate(method: MethodDescription): Boolean =
        method.isMethod && !method.isAbstract && !method.isNative && !method.isBridge && !method.isSynthetic

    private fun hasAnnotatedMember(type: TypeDescription): Boolean =
        type.declaredFields.any { !it.isStatic && idAnnotation(it.declaredAnnotations) != null } ||
            type.declaredMethods.any { method ->
                (method.isMethod && !method.isStatic && idAnnotation(method.declaredAnnotations) != null) ||
                    (method.isConstructor && method.parameters.any { idAnnotation(it.declaredAnnotations) != null })
            }

    /** The type and its superclasses, stopping at `Object` or at a superclass that cannot be resolved. */
    private fun hierarchy(type: TypeDescription): Sequence<TypeDescription> = sequence {
        var current: TypeDefinition? = type
        while (current != null) {
            val erasure = current.asErasure()
            if (erasure.represents(Any::class.java)) break
            yield(erasure)
            current = try {
                current.superClass
            } catch (_: RuntimeException) {
                null
            }
        }
    }
}

private val OBJECT = TypeDescription.Generic.OfNonGenericType.ForLoadedType.of(Any::class.java)
private val STRING = TypeDescription.Generic.OfNonGenericType.ForLoadedType.of(String::class.java)

/**
 * Binds `@IdArgs Object[]` to the id parameter values of each instrumented method. The array is built
 * where the advice reads it, so only on the exception path.
 */
class IdArgsMapping(private val parameters: IdParameters) : Advice.OffsetMapping.Factory<IdArgs> {
    override fun getAnnotationType(): Class<IdArgs> = IdArgs::class.java

    override fun make(
        target: ParameterDescription.InDefinedShape,
        annotation: AnnotationDescription.Loadable<IdArgs>,
        adviceType: Advice.OffsetMapping.Factory.AdviceType,
    ): Advice.OffsetMapping = Advice.OffsetMapping { instrumentedType, instrumentedMethod, assigner, argumentHandler, _ ->
        val selected = parameters.select(instrumentedType, instrumentedMethod)
        if (selected.isEmpty()) {
            Advice.OffsetMapping.Target.ForStackManipulation(NullConstant.INSTANCE)
        } else {
            Advice.OffsetMapping.Target.ForArray.ReadOnly(OBJECT, selected.map {
                val type = it.parameter.type
                StackManipulation.Compound(
                    MethodVariableAccess.of(type).loadFrom(argumentHandler.argument(it.parameter.offset)),
                    assigner.assign(type, OBJECT, Assigner.Typing.DYNAMIC),
                )
            })
        }
    }
}

/** Binds `@IdArgNames String[]` to the labels of the values bound by [IdArgsMapping]. */
class IdArgNamesMapping(private val parameters: IdParameters) : Advice.OffsetMapping.Factory<IdArgNames> {
    override fun getAnnotationType(): Class<IdArgNames> = IdArgNames::class.java

    override fun make(
        target: ParameterDescription.InDefinedShape,
        annotation: AnnotationDescription.Loadable<IdArgNames>,
        adviceType: Advice.OffsetMapping.Factory.AdviceType,
    ): Advice.OffsetMapping = Advice.OffsetMapping { instrumentedType, instrumentedMethod, _, _, _ ->
        val selected = parameters.select(instrumentedType, instrumentedMethod)
        if (selected.isEmpty()) {
            Advice.OffsetMapping.Target.ForStackManipulation(NullConstant.INSTANCE)
        } else {
            Advice.OffsetMapping.Target.ForArray.ReadOnly(STRING, selected.map { TextConstant(it.label) })
        }
    }
}

/** The exit advice, with the `@IdArgs`/`@IdArgNames` bindings for these id parameters. */
fun exitAdvice(parameters: IdParameters): Advice = Advice.withCustomMapping()
    .bind(IdArgsMapping(parameters))
    .bind(IdArgNamesMapping(parameters))
    .to(ExitAdvice::class.java)
