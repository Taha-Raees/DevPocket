package com.theia.mobile

import android.content.Context
import android.content.SharedPreferences
import java.time.Instant

/**
 * Manages onboarding flow state and persistence.
 *
 * States:
 * - FIRST_RUN: App launched for first time, no onboarding started
 * - ONBOARDING_WELCOME: User on welcome screen
 * - ONBOARDING_LOGIN: User setting up account (username, etc.)
 * - ONBOARDING_GUIDE: User reviewing setup guide
 * - ONBOARDING_DEBIAN_INSTALL_REQUIRED: About to install Debian rootfs
 * - ONBOARDING_INSTALLING: Debian installation in progress
 * - ONBOARDING_INSTALL_FAILED: Installation failed, show retry/repair
 * - READY_TO_LAUNCH_IDE: Installation complete, ready for IDE
 * - IDE_RUNNING: User is using the IDE
 */
class OnboardingStateManager(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences(
        "devpocket_onboarding",
        Context.MODE_PRIVATE
    )

    enum class OnboardingState {
        FIRST_RUN,
        ONBOARDING_WELCOME,
        ONBOARDING_LOGIN,
        ONBOARDING_GUIDE,
        ONBOARDING_DEBIAN_INSTALL_REQUIRED,
        ONBOARDING_INSTALLING,
        ONBOARDING_INSTALL_FAILED,
        READY_TO_LAUNCH_IDE,
        IDE_RUNNING
    }

    data class OnboardingConfig(
        val state: OnboardingState = OnboardingState.FIRST_RUN,
        val username: String? = null,
        val displayName: String? = null,
        val sudoMode: String = "passwordless", // "passwordless" or "password"
        val sudoPassword: String? = null,
        val rootfsVersion: String? = null,
        val installedAt: String? = null,
        val installProgress: Int = 0, // 0-100
        val installError: String? = null,
        val runtimeHealthy: Boolean = false,
        val backendHealthy: Boolean = false,
        val terminalTested: Boolean = false
    )

    fun getConfig(): OnboardingConfig {
        val state = prefs.getString("state", OnboardingState.FIRST_RUN.name)
            ?.let { OnboardingState.valueOf(it) } ?: OnboardingState.FIRST_RUN
        
        return OnboardingConfig(
            state = state,
            username = prefs.getString("username", null),
            displayName = prefs.getString("display_name", null),
            sudoMode = prefs.getString("sudo_mode", "passwordless") ?: "passwordless",
            sudoPassword = prefs.getString("sudo_password", null),
            rootfsVersion = prefs.getString("rootfs_version", null),
            installedAt = prefs.getString("installed_at", null),
            installProgress = prefs.getInt("install_progress", 0),
            installError = prefs.getString("install_error", null),
            runtimeHealthy = prefs.getBoolean("runtime_healthy", false),
            backendHealthy = prefs.getBoolean("backend_healthy", false),
            terminalTested = prefs.getBoolean("terminal_tested", false)
        )
    }

    fun setState(state: OnboardingState) {
        prefs.edit().putString("state", state.name).apply()
    }

    fun setUsername(username: String) {
        prefs.edit().putString("username", username).apply()
    }

    fun setDisplayName(displayName: String?) {
        prefs.edit()
            .putString("display_name", displayName ?: "")
            .apply()
    }

    fun setSudoMode(mode: String) {
        // "passwordless" or "password"
        prefs.edit().putString("sudo_mode", mode).apply()
    }

    fun setSudoPassword(password: String?) {
        prefs.edit()
            .putString("sudo_password", password ?: "")
            .apply()
    }

    fun setRootfsVersion(version: String) {
        prefs.edit().putString("rootfs_version", version).apply()
    }

    fun markInstalledNow() {
        prefs.edit()
            .putString("installed_at", Instant.now().toString())
            .apply()
    }

    fun setInstallProgress(progress: Int) {
        prefs.edit().putInt("install_progress", progress.coerceIn(0, 100)).apply()
    }

    fun setInstallError(error: String?) {
        prefs.edit()
            .putString("install_error", error ?: "")
            .apply()
    }

    fun setRuntimeHealthy(healthy: Boolean) {
        prefs.edit().putBoolean("runtime_healthy", healthy).apply()
    }

    fun setBackendHealthy(healthy: Boolean) {
        prefs.edit().putBoolean("backend_healthy", healthy).apply()
    }

    fun setTerminalTested(tested: Boolean) {
        prefs.edit().putBoolean("terminal_tested", tested).apply()
    }

    fun completeOnboarding() {
        val config = getConfig()
        setState(OnboardingState.READY_TO_LAUNCH_IDE)
        markInstalledNow()
        setRuntimeHealthy(true)
        setBackendHealthy(false) // Will be set to true after health check
    }

    fun reset() {
        prefs.edit().clear().apply()
    }

    // Check if onboarding flow is complete
    fun isOnboardingComplete(): Boolean {
        val state = getConfig().state
        return state == OnboardingState.READY_TO_LAUNCH_IDE || 
               state == OnboardingState.IDE_RUNNING
    }

    // Check preflight conditions
    fun checkPreflight(): PreflightCheckResult {
        return PreflightChecker.check(getConfig())
    }

    data class PreflightCheckResult(
        val isHealthy: Boolean,
        val issues: List<String>
    )

    private object PreflightChecker {
        fun check(config: OnboardingConfig): PreflightCheckResult {
            val issues = mutableListOf<String>()

            if (config.state == OnboardingState.ONBOARDING_INSTALL_FAILED) {
                issues.add("Previous installation failed. Please repair or reinstall.")
            }

            if (config.username == null) {
                issues.add("No user account configured.")
            }

            if (config.rootfsVersion == null) {
                issues.add("Debian rootfs not installed.")
            }

            if (!config.runtimeHealthy) {
                issues.add("Runtime not healthy. Repair may be needed.")
            }

            return PreflightCheckResult(
                isHealthy = issues.isEmpty(),
                issues = issues
            )
        }
    }
}
