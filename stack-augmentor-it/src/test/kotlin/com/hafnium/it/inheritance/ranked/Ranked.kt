package com.hafnium.it.inheritance.ranked

// Pattern precedence, see InheritanceTest. Entries: "com.hafnium.it.inheritance.ranked.**" = "code",
// "...ranked.excluded.*" = "-", "...ranked.excluded.Kept" = "code" and "...ranked.special.*" = "tag".

/** Matched only by "ranked.**". */
open class Account(private val code: String) {
    fun close(): Nothing = throw IllegalStateException("cannot close $code")
}
