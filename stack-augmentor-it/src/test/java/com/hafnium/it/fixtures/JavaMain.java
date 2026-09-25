package com.hafnium.it.fixtures;

/** A Java-only application: run by WithoutKotlinTest in a JVM without the Kotlin runtime. */
public final class JavaMain {

    private JavaMain() {
    }

    public static void main(String[] args) {
        try {
            new JavaFixture().run(42);
        } catch (IllegalStateException e) {
            e.printStackTrace(System.out);
        }
    }
}
