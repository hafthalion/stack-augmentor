package com.hafnium.it.inheritance;

/** Entry "label()": a default method compiled by javac, which adds no method to the implementing classes. */
public interface JavaLabeled {

    String label();

    default void relabel() {
        throw new IllegalStateException("cannot relabel " + label());
    }
}
