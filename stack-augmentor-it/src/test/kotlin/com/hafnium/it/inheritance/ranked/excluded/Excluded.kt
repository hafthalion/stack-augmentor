package com.hafnium.it.inheritance.ranked.excluded

import com.hafnium.it.inheritance.ranked.Account

/** Matched by "ranked.excluded.*" = "-" first, and by the later "ranked.**". */
class Dropped(code: String) : Account(code) {
    fun drop(): Nothing = throw IllegalStateException("cannot drop")
}

/** Matched by both patterns too, but first by its exact entry "ranked.excluded.Kept" = "code". */
class Kept(code: String) : Account(code) {
    fun keep(): Nothing = throw IllegalStateException("cannot keep")
}
