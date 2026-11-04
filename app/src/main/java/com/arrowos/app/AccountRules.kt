package com.arrowos.app

import java.util.Locale

/** Checks on what the user types into the sign-in screen, before anything is sent. */
object AccountRules {

    const val MIN_PASSWORD = 6

    private val emailShape = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]{2,}$")

    fun cleanEmail(raw: String): String = raw.trim().lowercase(Locale.ROOT)

    /** A message for the user, or null when the email looks right. */
    fun emailProblem(raw: String): String? = when {
        raw.isBlank() -> "Type your email address"
        !emailShape.matches(cleanEmail(raw)) -> "That doesn't look like an email address"
        else -> null
    }

    /** A message for the user, or null when the password is long enough. */
    fun passwordProblem(raw: String): String? = when {
        raw.isEmpty() -> "Type a password"
        raw.length < MIN_PASSWORD -> "The password needs at least $MIN_PASSWORD characters"
        else -> null
    }
}
