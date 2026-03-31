# Phase 2: Onboarding Flow on Kotlin Side - COMPLETED

**Date**: 2026-03-31
**Status**: ✅ IMPLEMENTED

## Deliverables

### 1. OnboardingStateManager.kt
Location: `android-app/android/app/src/main/java/com/theia/mobile/OnboardingStateManager.kt`

Manages onboarding state persistence with states:
- FIRST_RUN → ONBOARDING_WELCOME → ONBOARDING_LOGIN → ONBOARDING_GUIDE 
- → ONBOARDING_DEBIAN_INSTALL_REQUIRED → ONBOARDING_INSTALLING
- → ONBOARDING_INSTALL_FAILED (recovery) or READY_TO_LAUNCH_IDE → IDE_RUNNING

Key methods:
- `getConfig()` - Retrieves full onboarding state
- `setState()` - Updates current state
- `setUsername()`, `setDisplayName()`, `setSudoMode()` - User config
- `setInstallProgress()`, `setInstallError()` - Installation progress
- `isOnboardingComplete()` - Check if flow complete
- `checkPreflight()` - Validate readiness
- `completeOnboarding()` - Mark flow complete
- `reset()` - Clear all onboarding data

Data persisted via SharedPreferences: `devpocket_onboarding`

### 2. OnboardingActivity.kt
Location: `android-app/android/app/src/main/java/com/theia/mobile/OnboardingActivity.kt`

UI activity with wizard flow:
1. **Welcome** - Branding + "Begin Setup" button
2. **Account Setup** - Username input (defaults to "devpocket")
3. **Guide** - Feature overview (terminal, apt, etc.)
4. **Preflight** - Warning dialog, storage checks, "Download & Install" button
5. **Installing** - Progress bar + simulated progress messages
6. **Complete** - Success screen, "Open IDE" button
7. **Error Recovery** - Install failed dialog with Retry/Advanced Repair

Features:
- Simulated installation progress (10-second flow for now)
- Back button handling (blocks during installation)
- Storage permission requests before install
- Auto-transitions between steps
- Forwards to MainActivity when complete

### 3. AndroidManifest.xml Updated
Added:
```xml
<activity
    android:name=".OnboardingActivity"
    android:exported="false"
    android:label="DevPocket Setup"
    android:theme="@style/AppTheme"
    android:launchMode="singleTask" />
```

### 4. MainActivity.kt Updated
Added onboarding gate in `onCreate()`:
```kotlin
// Check if onboarding is required
val onboardingManager = OnboardingStateManager(this)
if (!onboardingManager.isOnboardingComplete()) {
    // Launch onboarding flow
    val intent = Intent(this, OnboardingActivity::class.java)
    startActivity(intent)
    finish()
    return
}
```

### 5. UI Layout: activity_onboarding.xml
Location: `android-app/android/app/src/main/res/layout/activity_onboarding.xml`

ScrollView container with 7 step layouts:
- All steps hidden by default
- Toggle visibility based on state
- Catppuccin theme colors (#1A1A2E bg, #CDD6F4 text, #89B4FA accent)
- EditText for username input
- Buttons for navigation
- Progress bar for installation

### 6. Drawable Resources
Created:
- `btn_primary.xml` - Blue accent buttons
- `et_background.xml` - EditText with border
- `card_background.xml` - Info card styling

## Known Design Decisions

1. **Passwordless vs Password Sudo** - Default set to passwordless (more mobile-friendly), but state manager supports password mode. Advanced option can be added in future.

2. **Installation Simulation** - Currently simulated with 10-second progress animation. Will be replaced in Phase 3 with actual bootstrap installer.

3. **No Direct IME Focus** - EditText keyboard appears naturally, no forced show (respects system preferences).

4. **Back Button Policy** - Allowed on welcome screen, blocked during account setup→installation to prevent accidental flows.

## Integration Points

- MainActivity.kt now gates IDE launch on onboarding completion
- OnboardingStateManager uses SharedPreferences for persistence across app launches
- States are tracked atomically, enabling resumable flows if app is killed

## Next: Phase 3 - Bootstrap Runtime Installer

Phase 3 will:
1. Create BootstrapInstallerService to download Debian rootfs
2. Implement checksum verification 
3. Handle extraction to app-private storage
4. Create user account container
5. Replace simulated progress with real progress from download/extraction

**Phase 3 Integration**: When OnboardingActivity.startInstallationFlow() is called, it will spawn a BootstrapInstallerService instead of simulating progress.
