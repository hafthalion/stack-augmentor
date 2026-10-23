package com.hafnium.it.buildtime

import com.hafnium.stackaugmentor.Augmented
import com.hafnium.stackaugmentor.AugmentedParam

// Top-level functions: their file class TopLevelKt cannot carry @Augmented, so each function takes its own.

@Augmented
fun topLevel(@AugmentedParam code: Int): Nothing = throw IllegalStateException("top-level $code")

fun topLevelWithoutAugmented(@AugmentedParam code: Int): Nothing = throw IllegalStateException("top-level $code")
