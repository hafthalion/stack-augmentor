package com.thirdparty.db

// A stand-in for a library class in a sub-package, selected by a wildcard entry in the agent configuration.

class OrderRepository {
    fun findById(id: Long): Nothing = throw IllegalStateException("not found")

    fun findAll(): Nothing = throw IllegalStateException("cannot list")
}
