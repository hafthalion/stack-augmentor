package com.hafnium.stackaugmentor.runtime.ids;

/** An id shown in a frame, with its label: the field, method or parameter it came from. */
public record NamedId(String name, String id) {
}
