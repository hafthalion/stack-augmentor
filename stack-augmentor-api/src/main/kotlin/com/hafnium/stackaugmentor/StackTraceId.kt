package com.hafnium.stackaugmentor

/**
 * Marks the source of an id shown in stack traces by the stack augmentor agent.
 *
 * - On a field or a no-argument method: the value identifies the object, and is shown next to the
 *   class name of every frame running on that object, e.g. `ObjectClass[objectId=object-1].objectMethod`.
 * - On a method parameter: the argument is shown after the method name, e.g. `objectMethod[orderId=42]`.
 *
 * In Kotlin, a `val` declared in the primary constructor is also recognised.
 *
 * @property name label shown in the stack trace; empty means the real field, method or parameter name.
 */
@Target(
    AnnotationTarget.FIELD,
    AnnotationTarget.FUNCTION,
    AnnotationTarget.PROPERTY_GETTER,
    AnnotationTarget.VALUE_PARAMETER,
)
@Retention(AnnotationRetention.RUNTIME)
@MustBeDocumented
annotation class StackTraceId(val name: String = "")
