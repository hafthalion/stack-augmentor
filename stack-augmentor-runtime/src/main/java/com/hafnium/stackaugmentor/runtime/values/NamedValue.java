package com.hafnium.stackaugmentor.runtime.values;

/** A value shown in a frame, with its label: the field, method or parameter it came from. */
public record NamedValue(String name, String value) {
}
