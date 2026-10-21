package com.hafnium.it.inheritance.ranked.special

import com.hafnium.it.inheritance.ranked.Account

/** Matched by "ranked.special.*" = "tag" first, and by the later "ranked.**" = "code". */
class Tagged(code: String, private val tag: String) : Account(code) {
    fun label(): Nothing = throw IllegalStateException("cannot label $tag")
}
