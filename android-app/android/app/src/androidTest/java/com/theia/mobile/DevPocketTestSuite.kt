package com.theia.mobile

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DevPocketTestSuite {

    private lateinit var context: Context
    private lateinit var onboardingStateManager: OnboardingStateManager

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        onboardingStateManager = OnboardingStateManager(context)
        onboardingStateManager.reset()
    }

    @After
    fun tearDown() {
        onboardingStateManager.reset()
    }

    @Test
    fun usernameValidation_acceptsExpectedUsernames() {
        assertTrue(UserAccountConfig.isValidUsername("devpocket"))
        assertTrue(UserAccountConfig.isValidUsername("user_123"))
        assertTrue(UserAccountConfig.isValidUsername("abc"))
    }

    @Test
    fun usernameValidation_rejectsReservedNamesViaErrorMessage() {
        assertEquals("Username is reserved", UserAccountConfig.getUsernameErrorMessage("debian"))
        assertEquals(null, UserAccountConfig.getUsernameErrorMessage("root"))
    }

    @Test
    fun onboardingStateTransitions_workAsExpected() {
        assertEquals(OnboardingStateManager.OnboardingState.FIRST_RUN, onboardingStateManager.getConfig().state)

        onboardingStateManager.setState(OnboardingStateManager.OnboardingState.ONBOARDING_LOGIN)
        assertEquals(OnboardingStateManager.OnboardingState.ONBOARDING_LOGIN, onboardingStateManager.getConfig().state)

        onboardingStateManager.completeOnboarding()
        assertTrue(onboardingStateManager.isOnboardingComplete())
    }

    @Test
    fun workspaceDefaultsToAppPrivateStorage() {
        onboardingStateManager.setUsername("root")

        val workspace = TheiaRuntimePaths.getIdeWorkspace(context)
        assertTrue(workspace.absolutePath.startsWith(context.filesDir.absolutePath))
        assertTrue(workspace.absolutePath.contains("usr/var/lib/proot-distro/installed-rootfs/debian/root"))
    }

    @Test
    fun storageStats_returnNonNegativeValues() {
        val stats = StorageManager.getStorageStats(context)

        assertTrue(stats.totalUsedBytes >= 0)
        assertTrue(stats.availableBytes > 0)
    }

    @Test
    fun healthCheck_returnsComponentCoverage() {
        val report = HealthCheckService.performFullHealthCheck(context)

        assertNotNull(report)
        assertTrue(report.components.isNotEmpty())
        assertTrue(report.components.any { it.name == "File System" })
        assertTrue(report.components.any { it.name == "Workspace" })
    }

    @Test
    fun securityPasswordPolicy_enforcesStrengthRequirements() {
        assertFalse(SecurityManager.validateSudoPassword("weak").valid)
        assertTrue(SecurityManager.validateSudoPassword("MyP@ssw0rd").valid)
        assertTrue(SecurityManager.validateSudoPassword("MyPassword!").valid)
    }

    @Test
    fun performanceMetrics_returnSaneValues() {
        val disk = PerformanceManager.calculateDiskUsage(context)
        val memory = PerformanceManager.getMemoryStats(context)

        assertTrue(disk.totalUsedBytes >= 0)
        assertNotNull(disk.components)
        assertTrue(memory.usagePercent in 0.0..100.0)
        assertTrue(memory.maxMemoryBytes > 0)
    }

    @Test
    fun shellWrapper_pinsGitToRootGitconfig() {
        val installer = BootstrapInstallerService(context)
        val writeWrapperScripts = BootstrapInstallerService::class.java.getDeclaredMethod("writeWrapperScripts")
        writeWrapperScripts.isAccessible = true

        writeWrapperScripts.invoke(installer)

        val shellWrapper = TheiaRuntimePaths.termuxShellWrapper(context)
        assertTrue(shellWrapper.exists())

        val shellScript = shellWrapper.readText()
        assertTrue(shellScript.contains("GIT_CONFIG_NOSYSTEM=1"))
        assertTrue(shellScript.contains("GIT_CONFIG_GLOBAL=/root/.gitconfig"))
        assertTrue(shellScript.contains("HOST_PWD_ORIGINAL="))
        assertTrue(shellScript.contains("APP_FILES_ALIAS="))
    }

    @Test
    fun ensureRootGitConfig_createsPlaceholderWhenMissing() {
        val installer = BootstrapInstallerService(context)
        val ensureRootGitConfig = BootstrapInstallerService::class.java.getDeclaredMethod("ensureRootGitConfig", File::class.java)
        ensureRootGitConfig.isAccessible = true

        val rootHome = File(context.cacheDir, "devpocket-test-root-home")
        rootHome.deleteRecursively()
        rootHome.mkdirs()

        try {
            ensureRootGitConfig.invoke(installer, rootHome)

            val gitConfig = File(rootHome, ".gitconfig")
            assertTrue(gitConfig.exists())
            assertTrue(gitConfig.readText().contains("DevPocket Git config placeholder"))
        } finally {
            rootHome.deleteRecursively()
        }
    }

    @Test
    fun hasDebianGitBinary_detectsGitUnderUsrBin() {
        val installer = BootstrapInstallerService(context)
        val hasDebianGitBinary = BootstrapInstallerService::class.java.getDeclaredMethod("hasDebianGitBinary", File::class.java)
        hasDebianGitBinary.isAccessible = true

        val rootFs = File(context.cacheDir, "devpocket-test-rootfs")
        rootFs.deleteRecursively()
        File(rootFs, "usr/bin").mkdirs()

        try {
            assertEquals(false, hasDebianGitBinary.invoke(installer, rootFs))

            File(rootFs, "usr/bin/git").writeText("#!/bin/sh\n")

            assertEquals(true, hasDebianGitBinary.invoke(installer, rootFs))
        } finally {
            rootFs.deleteRecursively()
        }
    }
}
