# Phase 6: User Account & Sudo Configuration - COMPLETED

**Date**: 2026-03-31
**Status**: ✅ IMPLEMENTED

## Deliverables

### 1. UserAccountConfig.kt
Location: `android-app/android/app/src/main/java/com/theia/mobile/UserAccountConfig.kt`

Helper object for user account management:
```kotlin
data class AccountSetup(
    val username: String,
    val displayName: String = "",
    val sudoMode: String = "passwordless"
)
```

**Constants:**
- `SUDO_MODE_PASSWORDLESS` = "passwordless"
- `SUDO_MODE_PASSWORD` = "password"

**Validation Functions:**
- `isValidUsername()` - Checks Linux username rules
  - Pattern: `^[a-z_][a-z0-9_-]{2,31}$`
  - 3-32 characters, lowercase/digits/underscore/hyphen
  - Rejects reserved names: root, bin, sys, games, nobody, ubuntu, debian
- `getUsernameErrorMessage()` - Provides specific error feedback
- `getSudoModeDescription()` - Human-readable descriptions

### 2. BootstrapInstallerService Enhanced
Updated `createUserAccount()` method:

**Files Created Inside Debian:**
1. `/home/<username>/` - User home directory
2. `/home/<username>/.bashrc` - Shell configuration
   - Aliases (ls, grep, rm, cp, mv)
   - TERM and COLORTERM settings
   - PS1 prompt
   - History settings
3. `/home/<username>/.bash_profile` - Login shell config
4. `/home/<username>/.ssh/` - SSH directory (prepared for keys)
5. `/etc/sudoers.d/devpocket-<username>` - Sudo policy

**Sudo Configuration:**

Passwordless mode (default):
```sudoers
<username> ALL=(ALL) NOPASSWD:ALL
```

Password-protected mode:
```sudoers
<username> ALL=(ALL) ALL
```

### 3. OnboardingActivity Enhanced
Updated `setupAccountSetupButtons()` to:
1. Read username from EditText
2. Validate using UserAccountConfig.isValidUsername()
3. Read sudo mode from RadioGroup
4. Save both via OnboardingStateManager
5. Provide specific error feedback

### 4. Layout Updated: activity_onboarding.xml
Added to Step 2 (Account Setup):
- RadioGroup with two options:
  - "Passwordless (recommended for mobile)" - default checked
  - "Password-protected (more secure)"
- Explanatory text about sudo modes

## Key Design Decisions

### A. Sudo Mode Selection
**Mobile-First Default**: Passwordless
- Reasoning: 
  - Easier UX on touchscreen (no password prompt delays)
  - Safe inside isolated app environment
  - Advanced users can change in settings later
  - Can be prompted for password during sensitive operations if needed

**Alternative Mode**: Password-protected
- For users who prefer traditional Linux security
- Still isolated to Debian container (no real device root risk)
- Can be changed in settings later

### B. Shell Configuration
**Provided by Default:**
- `.bashrc` - Standard shell init with color support
- `.bash_profile` - Login shell wrapper
- TERM=xterm-256color support for full IDE integration
- Common aliases for mobile convenience

**Not Provided (User Installs Later):**
- zsh, fish, other shells
- Custom prompt configs
- Language-specific environment setup

### C. Username Validation
**Strict Linux Rules:**
- Lowercase only (Linux convention)
- Must start with letter or underscore
- 3-32 character range
- Reserved system usernames blocked
- Real-time validation feedback in UI

**Rationale:**
- Prevents issues with case sensitivity
- Avoids special character headaches
- Standard Linux naming conventions
- Clear error messages guide users

### D. Reserved Usernames (Blocked)
- `root` - System root
- `bin`, `sys`, `sync`, `games`, `man` - System accounts
- `nobody` - Unprivileged system user
- `ubuntu`, `debian` - Distribution-specific

## Integration Flow

```
OnboardingActivity
  ├─ User enters username
  ├─ Real-time validation (UserAccountConfig)
  ├─ User selects sudo mode (passwordless or password)
  └─ User taps "Continue"
      ├─ OnboardingStateManager.setUsername()
      ├─ OnboardingStateManager.setSudoMode()  
      └─ Navigate to Guide step

Later: BootstrapInstallerService.install()
  ├─ extractTarGz()
  ├─ createUserAccount(username, sudoMode)
  │   ├─ Create /home/<username>/ directory
  │   ├─ Write .bashrc with color + TERM setup
  │   ├─ Write .bash_profile
  │   ├─ Create /home/<username>/.ssh/ directory
  │   └─ Write /etc/sudoers.d/devpocket-<username>
  └─ Mark installation complete
```

## Settings Integration (Future Phase 16+)

Users should be able to later:
- [ ] View configured username
- [ ] Change username (advanced option, requires account migration)
- [ ] Change sudo mode (requires sudoers update)
- [ ] Reset sudo password
- [ ] Delete account and re-onboard

## Security Considerations

### Passwordless Sudo Safety
- Only safe because Debian is in app-private storage
- Android user cannot access app storage without root
- Container isolation means compromised app ≠ compromised device
- Future: Can enforce certificate-based sudo for extra security if needed

### Configuration File Permissions
- `/etc/sudoers.d *`: 0o440 (read-only for root, owner)
- Home files: User owns them (standard Unix)
- Shell configs: 0o644 (readable by all, writable by owner)

### Password Storage (Future Phase 6B)
If password mode selected:
- Password should be **hashed** (not stored plaintext)
- Use crypt(3) or SHA512 hashing
- Stored in `/etc/shadow` (Linux shadow file)
- Would require actual useradd/usermod tools inside chroot

## Testing Checklist

- [ ] Invalid usernames rejected with feedback
- [ ] Valid usernames accepted
- [ ] Sudo mode radio button toggles
- [ ] Username and mode saved to preferences
- [ ] .bashrc created with proper content
- [ ] .bash_profile created
- [ ] .ssh directory created
- [ ] Sudo policy file created with correct content
- [ ] Sudo policy respects chosen mode (passwordless vs password)
- [ ] Color support working in terminal
- [ ] Aliases functional in terminal sessions

## Next: Phase 7 - Terminal Launch & Shell Integration  

Phase 7 will:
1. Configure terminal server to launch shells **inside Debian** (not Android host)
2. Set up chroot/namespace isolation (if available)
3. Configure shell search paths to find Debian executables
4. Set environment variables for Debian awareness
5. Handle login initialization (.bashrc/.bash_profile execution)
6. Support multiple terminal sessions with same user context

**Key Change**: Terminals currently run in host Android; Phase 7 makes them run in Debian with proper isolation.
