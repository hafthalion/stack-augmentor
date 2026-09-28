package com.hafnium.it.inheritance.ranked.excluded

import com.hafnium.it.inheritance.ranked.Account

/** Matched by "ranked.**" and by the more specific "ranked.excluded.*" = "-". */
class Dropped(code: String) : Account(code) {
    fun drop(): Nothing = throw IllegalStateException("cannot drop")
}

/** Matched by both patterns too, but also by its exact entry "ranked.excluded.Kept" = "code". */
class Kept(code: String) : Account(code) {
    fun keep(): Nothing = throw IllegalStateException("cannot keep")
}
