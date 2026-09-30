package com.hafnium.stackaugmentor.runtime

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SensitiveNamesTest {

    private val defaults = SensitiveNames(AugmentorConfig.DEFAULT_SENSITIVE_PARAMS)

    @Test
    fun `names are split at humps, digits and underscores`() {
        assertEquals(listOf("user", "e", "mail", "address"), SensitiveNames.words("userEMailAddress2"))
        assertEquals(listOf("first", "name"), SensitiveNames.words("FIRST_NAME"))
        assertEquals(listOf("api", "key"), SensitiveNames.words("APIKey"))
    }

    @Test
    fun `the defaults match secrets and personal data`() {
        for (name in listOf(
            "password", "newPassword", "PASSWORD_HASH", "apiKey", "api_key", "accessToken", "email", "eMail", "userEmail", "EMAIL_ADDRESS",
            "phoneNumber", "firstName", "last_name", "userName", "iban", "cardNumber", "creditCardNo", "birthDate", "dob",
            "pin", "street", "ssn",
        )) {
            assertTrue(defaults.matches(name), name)
        }
    }

    @Test
    fun `whole words only`() {
        for (name in listOf("order", "shipping", "orderId", "author", "quantity", "note", "name", "arg0", "passenger", "mailbox")) {
            assertFalse(defaults.matches(name), name)
        }
    }

    @Test
    fun `configured names replace the defaults, in any spelling`() {
        val custom = SensitiveNames(listOf("customerNumber", "vin"))
        assertTrue(custom.matches("customer_number"))
        assertTrue(custom.matches("theVin"))
        assertFalse(custom.matches("password"))
    }
}
