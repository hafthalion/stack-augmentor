package com.hafnium.stackaugmentor.logback

import ch.qos.logback.classic.pattern.ThrowableHandlingConverter
import ch.qos.logback.classic.pattern.ThrowableProxyConverter
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.classic.spi.ThrowableProxy
import com.hafnium.stackaugmentor.AugmentedStackTraces

/**
 * Prints the exception of a log event with the ids recorded by the agent in registry mode.
 *
 * ```xml
 * <conversionRule conversionWord="aex"
 *                 class="com.hafnium.stackaugmentor.logback.AugmentedThrowableConverter"/>
 * <pattern>%d %level %logger - %msg%n%aex</pattern>
 * ```
 */
class AugmentedThrowableConverter : ThrowableHandlingConverter() {

    private val fallback = ThrowableProxyConverter()

    override fun start() {
        fallback.context = context
        fallback.optionList = optionList
        fallback.start()
        super.start()
    }

    override fun stop() {
        fallback.stop()
        super.stop()
    }

    override fun convert(event: ILoggingEvent): String {
        val proxy = event.throwableProxy ?: return ""
        // Deserialized events have no Throwable, so there is nothing to look up.
        if (proxy !is ThrowableProxy) return fallback.convert(event)
        return AugmentedStackTraces.format(proxy.throwable)
    }
}
