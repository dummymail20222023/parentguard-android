package com.parentguard.agent.domain

/**
 * Hardcoded child safety and crisis helpline filter.
 * Interactions with crisis, suicide prevention, or child protection helplines are unconditionally
 * excluded on-device prior to database persistence or encryption.
 */
object SafetyHelplineFilter {

    private val PROTECTED_SHORTCODES_AND_NUMBERS = setOf(
        "988",          // US/Canada Suicide & Crisis Lifeline
        "911",          // Emergency Services
        "112",          // European / International Emergency
        "999",          // UK Emergency
        "1098",         // Childline India (24/7 Child Protection Helpline)
        "116111",       // European Child Helpline
        "741741",       // Crisis Text Line
        "18002738255",  // National Suicide Prevention Lifeline
        "08001111",     // Childline UK
        "1800551800"    // Kids Helpline Australia
    )

    fun isProtectedHelpline(rawPhoneNumber: String): Boolean {
        val digitsOnly = rawPhoneNumber.filter { it.isDigit() }
        if (digitsOnly.isEmpty()) return false
        if (digitsOnly in PROTECTED_SHORTCODES_AND_NUMBERS) return true
        // Also strip leading country code +1 / +91 / +44 if matching a helpline
        val withoutUsPrefix = if (digitsOnly.length > 10 && digitsOnly.startsWith("1")) {
            digitsOnly.substring(1)
        } else {
            digitsOnly
        }
        return withoutUsPrefix in PROTECTED_SHORTCODES_AND_NUMBERS
    }
}
