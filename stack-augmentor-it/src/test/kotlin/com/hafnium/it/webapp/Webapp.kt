package com.hafnium.it.webapp

import com.hafnium.stackaugmentor.Augmented
import com.hafnium.stackaugmentor.AugmentedParam
import com.hafnium.stackaugmentor.AugmentedReceiver

/**
 * Loaded by class loaders of their own in ClassLoaderConfigTest, as an application server loads its applications: no
 * [augment.annotations] entry of the agent's configuration selects this package.
 */
@Augmented
class Webapp(@AugmentedReceiver val name: String) {

    fun fail(@AugmentedParam code: Int): Nothing = throw IllegalStateException("fail $code")
}
