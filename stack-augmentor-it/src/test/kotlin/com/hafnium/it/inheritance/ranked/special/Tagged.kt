package com.hafnium.it.inheritance.ranked.special

import com.hafnium.it.inheritance.ranked.Account

/** Matched by "ranked.**" = "code" and by the more specific "ranked.special.*" = "tag". */
class Tagged(code: String, private val tag: String) : Account(code) {
    fun label(): Nothing = throw IllegalStateException("cannot label $tag")
}
