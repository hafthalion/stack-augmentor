package com.hafnium.it.webapp

import com.hafnium.stackaugmentor.Augmented
import com.hafnium.stackaugmentor.AugmentedParam
import com.hafnium.stackaugmentor.AugmentedReceiver

/**
 * Loaded by class loaders of their own in ClassLoaderConfigTest, as an application server loads its applications.
 */
@Augmented
class Webapp(@AugmentedReceiver val name: String) {

    fun fail(@AugmentedParam code: Int): Nothing = throw IllegalStateException("fail $code")
}

/** Like [Webapp], packaged in a jar of its own by ClassLoaderConfigTest, as a library of the application. */
@Augmented
class WebappLibrary(@AugmentedReceiver val name: String) {

    fun fail(@AugmentedParam code: Int): Nothing = throw IllegalStateException("fail $code")
}
