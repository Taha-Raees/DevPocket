package com.theia.mobile

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AlertDialog
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/**
 * Onboarding activity for Debian-first terminal setup.
 *
 * Guides user through:
 * 1. Welcome screen
 * 2. Account setup (username, optional password, sudo mode)
 * 3. Setup guide / information
 * 4. Preflight checks (storage, network, battery)
 * 5. Installation confirmatio
 * 6. Installation progress
 * 7. Completion handoff to MainActivity
 */
class OnboardingActivity : AppCompatActivity() {
    companion object {
        private const val TAG = "OnboardingActivity"
    }

    private lateinit var stateManager: OnboardingStateManager
    private lateinit var mainContainer: View
    private lateinit var stepWelcome: View
    private lateinit var stepAccountSetup: View
    private lateinit var stepGuide: View
    private lateinit var stepPreflight: View
    private lateinit var stepInstallConfirm: View
    private lateinit var stepInstalling: View
    private lateinit var stepComplete: View

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_onboarding)

        stateManager = OnboardingStateManager(this)
        mainContainer = findViewById(R.id.onboarding_container)

        // Initialize all step views (hidden by default)
        stepWelcome = findViewById(R.id.step_welcome)
        stepAccountSetup = findViewById(R.id.step_account_setup)
        stepGuide = findViewById(R.id.step_guide)
        stepPreflight = findViewById(R.id.step_preflight)
        stepInstallConfirm = findViewById(R.id.step_install_confirm)
        stepInstalling = findViewById(R.id.step_installing)
        stepComplete = findViewById(R.id.step_complete)

        // Check current onboarding state
        val config = stateManager.getConfig()
        if (config.state == OnboardingStateManager.OnboardingState.IDE_RUNNING) {
            // Already onboarded, shouldn't be here. Go straight to IDE.
            launchMainActivity()
            return
        }

        if (config.state == OnboardingStateManager.OnboardingState.READY_TO_LAUNCH_IDE) {
            // Installation complete, go to IDE
            launchMainActivity()
            return
        }

        // Render appropriate step
        renderStep(config.state)

        // Set up button handlers
        setupWelcomeButtons()
        setupAccountSetupButtons()
        setupGuideButtons()
        setupPreflightButtons()
        setupInstallConfirmButtons()
        setupCompleteButtons()
    }

    private fun renderStep(state: OnboardingStateManager.OnboardingState) {
        // Hide all steps
        listOf(stepWelcome, stepAccountSetup, stepGuide, stepPreflight, stepInstallConfirm, stepInstalling, stepComplete)
            .forEach { it.visibility = View.GONE }

        // Show current step
        when (state) {
            OnboardingStateManager.OnboardingState.FIRST_RUN,
            OnboardingStateManager.OnboardingState.ONBOARDING_WELCOME -> {
                stepWelcome.visibility = View.VISIBLE
            }
            OnboardingStateManager.OnboardingState.ONBOARDING_LOGIN -> {
                stepAccountSetup.visibility = View.VISIBLE
            }
            OnboardingStateManager.OnboardingState.ONBOARDING_GUIDE -> {
                stepGuide.visibility = View.VISIBLE
            }
            OnboardingStateManager.OnboardingState.ONBOARDING_DEBIAN_INSTALL_REQUIRED -> {
                stepPreflight.visibility = View.VISIBLE
            }
            OnboardingStateManager.OnboardingState.ONBOARDING_INSTALLING -> {
                stepInstalling.visibility = View.VISIBLE
                startInstallationFlow()
            }
            OnboardingStateManager.OnboardingState.ONBOARDING_INSTALL_FAILED -> {
                showInstallationFailedDialog()
            }
            OnboardingStateManager.OnboardingState.READY_TO_LAUNCH_IDE,
            OnboardingStateManager.OnboardingState.IDE_RUNNING -> {
                launchMainActivity()
            }
        }
    }

    private fun setupWelcomeButtons() {
        val btnBegin = stepWelcome.findViewById<Button>(R.id.btn_begin_onboarding)
        btnBegin?.setOnClickListener {
            stateManager.setState(OnboardingStateManager.OnboardingState.ONBOARDING_LOGIN)
            renderStep(OnboardingStateManager.OnboardingState.ONBOARDING_LOGIN)
        }
    }

    private fun setupAccountSetupButtons() {
        val etUsename = stepAccountSetup.findViewById<android.widget.EditText>(R.id.et_username)
        val sudoModeGroup = stepAccountSetup.findViewById<android.widget.RadioGroup>(R.id.rg_sudo_mode)
        val etSudoPassword = stepAccountSetup.findViewById<android.widget.EditText>(R.id.et_sudo_password)
        val etSudoPasswordConfirm = stepAccountSetup.findViewById<android.widget.EditText>(R.id.et_sudo_password_confirm)
        val btnNext = stepAccountSetup.findViewById<Button>(R.id.btn_account_setup_next)
        
        // Setup radio button listeners to toggle password fields
        val rbPasswordless = stepAccountSetup.findViewById<android.widget.RadioButton>(R.id.rb_sudo_passwordless)
        val rbPassword = stepAccountSetup.findViewById<android.widget.RadioButton>(R.id.rb_sudo_password)
        
        rbPasswordless?.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                etSudoPassword?.visibility = View.GONE
                etSudoPasswordConfirm?.visibility = View.GONE
            }
        }
        
        rbPassword?.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                etSudoPassword?.visibility = View.VISIBLE
                etSudoPasswordConfirm?.visibility = View.VISIBLE
            }
        }
        
        btnNext?.setOnClickListener {
            val username = etUsename?.text?.toString()?.trim() ?: "devpocket"
            
            // Validate username
            val validationError = UserAccountConfig.getUsernameErrorMessage(username)
            if (validationError != null) {
                etUsename?.error = validationError
                return@setOnClickListener
            }

            // Get sudo mode selection
            val sudoModeId = sudoModeGroup?.checkedRadioButtonId
            val sudoMode = when (sudoModeId) {
                R.id.rb_sudo_password -> UserAccountConfig.SUDO_MODE_PASSWORD
                else -> UserAccountConfig.SUDO_MODE_PASSWORDLESS
            }

            // Validate and save password if password mode is selected
            if (sudoMode == UserAccountConfig.SUDO_MODE_PASSWORD) {
                val password = etSudoPassword?.text?.toString() ?: ""
                val passwordConfirm = etSudoPasswordConfirm?.text?.toString() ?: ""
                
                // Validate password strength
                val passwordValidation = SecurityManager.validateSudoPassword(password)
                if (!passwordValidation.valid) {
                    etSudoPassword?.error = passwordValidation.errorMessage
                    return@setOnClickListener
                }
                
                // Check passwords match
                if (password != passwordConfirm) {
                    etSudoPasswordConfirm?.error = "Passwords do not match"
                    return@setOnClickListener
                }
                
                // Save password to state
                stateManager.setSudoPassword(password)
            }

            // Save configuration
            stateManager.setUsername(username)
            stateManager.setSudoMode(sudoMode)
            stateManager.setState(OnboardingStateManager.OnboardingState.ONBOARDING_GUIDE)
            renderStep(OnboardingStateManager.OnboardingState.ONBOARDING_GUIDE)
        }
    }

    private fun setupGuideButtons() {
        val btnNext = stepGuide.findViewById<Button>(R.id.btn_guide_next)
        btnNext?.setOnClickListener {
            stateManager.setState(OnboardingStateManager.OnboardingState.ONBOARDING_DEBIAN_INSTALL_REQUIRED)
            renderStep(OnboardingStateManager.OnboardingState.ONBOARDING_DEBIAN_INSTALL_REQUIRED)
        }
    }

    private fun setupPreflightButtons() {
        val btnInstall = stepPreflight.findViewById<Button>(R.id.btn_preflight_install)
        btnInstall?.setOnClickListener {
            // Request storage permissions one more time before install
            requestStoragePermissionsIfNeeded()
            
            stateManager.setState(OnboardingStateManager.OnboardingState.ONBOARDING_INSTALLING)
            renderStep(OnboardingStateManager.OnboardingState.ONBOARDING_INSTALLING)
        }
    }

    private fun setupInstallConfirmButtons() {
        val btnConfirm = stepInstallConfirm.findViewById<Button>(R.id.btn_install_confirm)
        btnConfirm?.setOnClickListener {
            stateManager.setState(OnboardingStateManager.OnboardingState.ONBOARDING_INSTALLING)
            renderStep(OnboardingStateManager.OnboardingState.ONBOARDING_INSTALLING)
        }
    }

    private fun setupCompleteButtons() {
        val btnLaunchIde = stepComplete.findViewById<Button>(R.id.btn_launch_ide)
        btnLaunchIde?.setOnClickListener {
            stateManager.setState(OnboardingStateManager.OnboardingState.IDE_RUNNING)
            launchMainActivity()
        }
    }

    private fun startInstallationFlow() {
        val progressBar = stepInstalling.findViewById<ProgressBar>(R.id.progress_install)
        val statusText = stepInstalling.findViewById<TextView>(R.id.tv_install_status)

        // Run installer in background thread
        Thread {
            val installer = BootstrapInstallerService(this)
            
            // Set progress callback
            installer.setProgressCallback { progress ->
                runOnUiThread {
                    progressBar?.progress = progress.percentComplete
                    statusText?.text = when (progress.phase) {
                        "downloading" -> "Downloading Debian rootfs... ${progress.percentComplete}%"
                        "verifying" -> "Verifying checksum... ${progress.percentComplete}%"
                        "extracting" -> "Extracting files... ${progress.percentComplete}%"
                        "finalizing" -> "Finalizing setup... ${progress.percentComplete}%"
                        else -> "Installing... ${progress.percentComplete}%"
                    }
                }
            }

            // Perform installation
            val success = installer.install()

            // Cleanup temporary files
            installer.cleanup()

            runOnUiThread {
                if (success) {
                    stateManager.setState(OnboardingStateManager.OnboardingState.READY_TO_LAUNCH_IDE)
                    stepInstalling.visibility = View.GONE
                    stepComplete.visibility = View.VISIBLE
                    setupCompleteButtons()
                } else {
                    stateManager.setState(OnboardingStateManager.OnboardingState.ONBOARDING_INSTALL_FAILED)
                    showInstallationFailedDialog()
                }
            }
        }.start()
    }

    private fun showInstallationFailedDialog() {
        val config = stateManager.getConfig()
        val dialog = AlertDialog.Builder(this)
            .setTitle("Installation Failed")
            .setMessage(config.installError ?: "Unknown error during installation")
            .setPositiveButton("Retry") { _, _ ->
                stateManager.setInstallError(null)
                stateManager.setState(OnboardingStateManager.OnboardingState.ONBOARDING_INSTALLING)
                renderStep(OnboardingStateManager.OnboardingState.ONBOARDING_INSTALLING)
            }
            .setNegativeButton("Advanced Repair") { _, _ ->
                // TODO: Show advanced repair options in Phase 11
            }
            .show()
    }

    private fun launchMainActivity() {
        val intent = Intent(this, MainActivity::class.java)
        startActivity(intent)
        finish()
    }

    private fun requestStoragePermissionsIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!android.os.Environment.isExternalStorageManager()) {
                try {
                    val intent = Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                    intent.addCategory("android.intent.category.DEFAULT")
                    intent.data = android.net.Uri.parse(String.format("package:%s", packageName))
                    startActivityForResult(intent, 2296)
                } catch (e: Exception) {
                    val intent = Intent()
                    intent.action = android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION
                    startActivityForResult(intent, 2296)
                }
            }
        } else {
            val permissions = arrayOf(
                android.Manifest.permission.READ_EXTERNAL_STORAGE,
                android.Manifest.permission.WRITE_EXTERNAL_STORAGE
            )
            if (ContextCompat.checkSelfPermission(this, permissions[0]) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, permissions, 100)
            }
        }
    }

    override fun onBackPressed() {
        val config = stateManager.getConfig()
        if (config.state == OnboardingStateManager.OnboardingState.ONBOARDING_WELCOME ||
            config.state == OnboardingStateManager.OnboardingState.FIRST_RUN) {
            // Allow back on welcome
            super.onBackPressed()
        } else {
            // Don't allow back during setup/installation
            Log.d(TAG, "Back press blocked during onboarding state: ${config.state}")
        }
    }
}
