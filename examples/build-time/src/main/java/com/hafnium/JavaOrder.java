package com.hafnium;

import com.hafnium.stackaugmentor.StackTraceId;

/**
 * A Java class instrumented at build time (by byteBuddyJava). It needs no Kotlin runtime:
 * WithoutKotlinTest runs it with only stack-augmentor-api and stack-augmentor-runtime on the classpath.
 */
public class JavaOrder {

    @StackTraceId
    private final String orderId;

    public JavaOrder(String orderId) {
        this.orderId = orderId;
    }

    public void ship(@StackTraceId(name = "warehouse") String warehouse) {
        throw new IllegalStateException("cannot ship from " + warehouse);
    }

    public static void main(String[] args) {
        try {
            new JavaOrder("o-17").ship("north");
        } catch (IllegalStateException e) {
            e.printStackTrace(System.out);
        }
    }
}
