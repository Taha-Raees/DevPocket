package com.theia.mobile

/**
 * User account configuration options and helpers for Debian environment.
 */
object UserAccountConfig {
    data class AccountSetup(
        val username: String,
        val displayName: String = "",
        val sudoMode: String = "passwordless"  // "passwordless" or "password"
    )

    // Sudo modes
    const val SUDO_MODE_PASSWORDLESS = "passwordless"
    const val SUDO_MODE_PASSWORD = "password"

    // Username validation
    fun isValidUsername(username: String): Boolean {
        // Linux username rules: lowercase, digits, underscore, hyphen
        // Must start with lowercase or underscore
        // 3-32 characters
        val pattern = Regex("^[a-z_][a-z0-9_-]{2,31}$")
        return pattern.matches(username.lowercase())
    }

    fun getUsernameErrorMessage(username: String): String? {
        return when {
            username.isBlank() -> "Username is required"
            username.length < 3 -> "Username must be at least 3 characters"
            username.length > 32 -> "Username must be at most 32 characters"
            !isValidUsername(username) -> "Username can only contain lowercase letters, numbers, hyphen, and underscore"
            username in listOf("root", "bin", "sys", "sync", "games", "man", "nobody", "ubuntu", "debian") -> 
                "Username is reserved"
            else -> null
        }
    }

    fun getSudoModeDescription(mode: String): String {
        return when (mode) {
            SUDO_MODE_PASSWORDLESS -> "Run sudo without password (easier, recommended for mobile)"
            SUDO_MODE_PASSWORD -> "Require password for sudo (more secure, traditional)"
            else -> "Unknown sudo mode"
        }
    }
}
