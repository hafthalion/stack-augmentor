package com.hafnium.stackaugmentor.agent

import com.hafnium.stackaugmentor.agent.advice.ExitAdvice
import com.hafnium.stackaugmentor.agent.advice.IdArgNames
import com.hafnium.stackaugmentor.agent.advice.IdArgs
import com.hafnium.stackaugmentor.bridge.Dispatch
import net.bytebuddy.agent.builder.AgentBuilder
import net.bytebuddy.asm.Advice
import net.bytebuddy.description.annotation.AnnotationDescription
import net.bytebuddy.description.annotation.AnnotationList
import net.bytebuddy.description.method.MethodDescription
import net.bytebuddy.description.method.ParameterDescription
import net.bytebuddy.description.type.TypeDefinition
import net.bytebuddy.description.type.TypeDescription
import net.bytebuddy.dynamic.Nexus
import net.bytebuddy.dynamic.loading.ClassInjector
import net.bytebuddy.implementation.bytecode.StackManipulation
import net.bytebuddy.implementation.bytecode.assign.Assigner
import net.bytebuddy.implementation.bytecode.constant.NullConstant
import net.bytebuddy.implementation.bytecode.constant.TextConstant
import net.bytebuddy.implementation.bytecode.member.MethodVariableAccess
import net.bytebuddy.matcher.ElementMatcher
import net.bytebuddy.matcher.ElementMatchers.any
import net.bytebuddy.matcher.ElementMatchers.isBootstrapClassLoader
import net.bytebuddy.matcher.ElementMatchers.isSynthetic
import net.bytebuddy.matcher.ElementMatchers.nameStartsWith
import java.lang.instrument.Instrumentation

/** A parameter whose value is shown after the method name, with its label. */
class IdParameter(val parameter: ParameterDescription, val label: String)

/** Finds the id parameters of a method: annotated with `@StackTraceId`, or listed in the `[param]` config table. */
class IdParameters(private val config: AugmentorConfig) {

    fun select(type: TypeDescription, method: MethodDescription): List<IdParameter> {
        val parameters = method.parameters
        val labels = sortedMapOf<Int, String>()
        for (parameter in parameters) {
            val annotation = idAnnotation(parameter.declaredAnnotations) ?: continue
            labels[parameter.index] = annotationLabel(annotation) ?: parameter.name
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

    fun instrument(type: TypeDescription): Boolean =
        !type.isAnnotation && (receiverRelevant(type) || type.declaredMethods.any { isCandidate(it) && parameters.select(type, it).isNotEmpty() })

    /** Instance methods get a receiver id; other methods are only instrumented for their id parameters. */
    fun methods(type: TypeDescription): ElementMatcher<MethodDescription> {
        val receiver = receiverRelevant(type)
        return ElementMatcher { method ->
            isCandidate(method) && ((receiver && !method.isStatic) || parameters.select(type, method).isNotEmpty())
        }
    }

    private fun receiverRelevant(type: TypeDescription): Boolean =
        config.isIncluded(type.name) || hierarchy(type).any { config.ids.containsKey(it.name) || hasAnnotatedMember(it) }

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

internal object Installer {

    // Written without the trailing dot, so the shadow jar's relocation leaves them alone.
    private val IGNORED_PACKAGES = listOf(
        "java", "javax", "jdk", "sun", "com.sun", "kotlin", "net.bytebuddy", "com.hafnium.stackaugmentor",
    )

    fun install(instrumentation: Instrumentation, config: AugmentorConfig, format: FrameFormat) {
        Log.debug = config.debug
        Dispatch.install(ThrowHandler(config, IdResolver(config), format))

        // Inlined advice needs neither the Nexus nor Unsafe-based class injection; turning them off avoids
        // the JDK's sun.misc.Unsafe warnings. In the shaded jar these property names are relocated, so they
        // do not affect a ByteBuddy copy used by the application itself.
        System.setProperty(Nexus.PROPERTY, "true")
        System.setProperty(ClassInjector.UsingUnsafe.SAFE_PROPERTY, "true")

        val parameters = IdParameters(config)
        val matching = TypeMatching(config, parameters)
        val advice = Advice.withCustomMapping()
            .bind(IdArgsMapping(parameters))
            .bind(IdArgNamesMapping(parameters))
            .to(ExitAdvice::class.java)

        AgentBuilder.Default()
            .disableClassFormatChanges()
            .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
            .with(AgentBuilder.TypeStrategy.Default.DECORATE)
            // The advice is inlined, so no helper classes need to be injected into class loaders.
            .with(AgentBuilder.InjectionStrategy.Disabled.INSTANCE)
            .with(
                if (config.debug) {
                    AgentBuilder.Listener.StreamWriting.toSystemError().withTransformationsOnly()
                } else {
                    AgentBuilder.Listener.NoOp.INSTANCE
                },
            )
            .assureReadEdgeTo(instrumentation, Dispatch::class.java)
            .ignore(IGNORED_PACKAGES.fold(isSynthetic<TypeDescription>()) { matcher, prefix -> matcher.or(nameStartsWith("$prefix.")) })
            .or(any(), isBootstrapClassLoader())
            .type(ElementMatcher { type -> matching.instrument(type) })
            .transform { builder, type, _, _, _ -> builder.visit(advice.on(matching.methods(type))) }
            .installOn(instrumentation)
    }
}
