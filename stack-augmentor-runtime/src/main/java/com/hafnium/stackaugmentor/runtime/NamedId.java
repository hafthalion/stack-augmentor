package com.hafnium.stackaugmentor.runtime;

/** An id shown in a frame, with its label: the field, method or parameter it came from. */
public record NamedId(String name, String id) {
}
