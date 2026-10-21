package com.hafnium.it.inheritance.ranked

// Entry order, see InheritanceTest. Entries, in this order: "...ranked.excluded.Kept" = "code",
// "...ranked.excluded.*" = "-", "...ranked.special.*" = "tag", "com.hafnium.it.inheritance.ranked.**" = "code" and
// "...ranked.Account" = "-".

/** Matched by "ranked.**" first, and by its exact entry "-" too, which comes later. */
open class Account(private val code: String) {
    fun close(): Nothing = throw IllegalStateException("cannot close $code")
}
