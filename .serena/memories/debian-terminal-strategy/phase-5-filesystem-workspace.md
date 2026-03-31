# Phase 5: Filesystem and Workspace Model - COMPLETED

**Date**: 2026-03-31
**Status**: ✅ IMPLEMENTED (with backward compatibility)

## Deliverables

### 1. TheiaRuntimePaths Extended
Location: `android-app/android/app/src/main/java/com/theia/mobile/TheiaRuntimePaths.kt`

New method: `getIdeWorkspace(context: Context): File`

**Priority resolution:**
1. **App-private Debian workspace** (new): `$filesDir/linux/debian/home/<username>/code/`
   - Used if onboarding complete and username configured
   - Preferred for full Debian-first model
   
2. **Legacy external storage** (backward compat): `/storage/emulated/0/Documents/DevPocket`
   - Fallback for users upgrading without re-onboarding
   - Only if external storage mounted
   
3. **Internal fallback**: `$filesDir/workspaces/primary/`
   - Last resort if nothing else available
   - Fully app-private, no extra permissions needed

**Rationale:**
- Gradual migration path (no breaking changes)
- Existing users keep their workspace in familiar location
- New users get app-private workspace automatically
- Can be reversed if needed

### 2. TheiaBackendService Updated
Location: `android-app/android/app/src/main/java/com/theia/mobile/TheiaBackendService.kt`

**Changes in createProcessBuilder():**

```kotlin
// Old:
val devPocketWorkspace = File("/storage/emulated/0/Documents/DevPocket")

// New:
val devPocketWorkspace = TheiaRuntimePaths.getIdeWorkspace(this)
logLine("IDE workspace resolved to: ${devPocketWorkspace.absolutePath}")
```

**New Environment Variables:**
```kotlin
DEVPOCKET_DEBIAN_ROOT        = /data/data/com.theia.mobile/files/linux/debian
DEVPOCKET_USER               = devpocket (or custom username)
DEVPOCKET_WORKSPACE          = /data/data/.../linux/debian/home/devpocket/code
```

These enable terminal sessions to be aware of the Debian environment.

### 3. Migration Path

**Phase 5A (Now): Coexistence**
- Onboarded users: workspace at `$filesDir/linux/debian/home/<user>/code/`
- Existing users: workspace at `/storage/emulated/0/Documents/DevPocket`
- System detects available and picks best option automatically

**Phase 5B (Phase 10+): Optional Migration**
- Provide UI option: "Migrate workspace to app-private storage"
- Guide copy process: external → internal
- Support old location as secondary import source

**Phase 5C (Future Major): Deprecation**
- Can eventually remove external storage fallback
- Only after majority of users migrated
- Preserve backward compat layer for at least 2 releases

## Workspace Structure (New Model)

```
$filesDir/linux/
├── debian/
│   ├── bin/, etc/, usr/, var/, ...   # Debian system files
│   ├── home/
│   │   └── devpocket/                # Default user home
│   │       ├── code/                 # Default workspace for IDE
│   │       ├── .bashrc
│   │       ├── .bash_profile
│   │       ├── .gitconfig
│   │       ├── .ssh/
│   │       └── projects/             # Alternative project location
│   │
│   └── root/                         # Debian root home (unused normally)
│
├── bootstrap/                        # Installer runtime (hidden)
├── temp/                             # Temp download/extract files
├── INSTALL_MANIFEST.json            # Installation metadata
└── metadata/
    ├── status.json                  # Onboarding/health status
    ├── user-config.json            # Username, preferences
    ├── sudo-policy.json            # Sudo configuration
    └── health-check.json           # Last health check results
```

## Key Properties

### Advantages of App-Private Workspace
1. **Private by default** - No Android permission issues
2. **Unlinked from shared storage** - Prevents accidental deletion
3. **Standard Linux behavior** - File permissions, ownership work correctly
4. **Simpler permission model** - No need MANAGE_EXTERNAL_STORAGE
5. **Faster access** - No shared storage overhead
6. **Secure** - Hidden from user's file browser by default

### Backward Compatibility
1. Existing projects stay in `/storage/emulated/0/Documents/DevPocket`
2. No forced migration (opt-in only)
3. New installs go to app-private automatically
4. Can use both simultaneously if needed

### Shared Storage Role (Phase 10)
- **Import**: User picks files → copied into app-private workspace
- **Export**: User selects files/projects → copied to shared storage
- **Not default location**: Must be explicitly chosen for every operation

## Integration with Onboarding

1. **OnboardingActivity** → creates user account
2. **BootstrapInstallerService** → extracts Debian
3. **BootstrapInstallerService.createUserAccount()** → creates `/home/<username>/` and code/ subdirectory
4. **OnboardingStateManager** → stores username in preferences
5. **TheiaRuntimePaths.getIdeWorkspace()** → reads username, resolves workspace path
6. **TheiaBackendService** → uses resolved workspace as IDE root

## Environment in Terminal Sessions (Phase 7 Integration)

When users open terminal inside IDE:
```bash
$ echo $HOME
/home/devpocket           # Inside Debian chroot

$ echo $DEVPOCKET_WORKSPACE
/data/data/com.theia.mobile/files/linux/debian/home/devpocket/code

$ pwd
/home/devpocket/code      # From IDE's perspective in Debian
```

Terminal sees the standard Unix path (`/home/devpocket`), not the Android path. This is handled by Phase 7's terminal shell setup.

## IMPORTANT: File Permissions

**Android filesystem** (before extraction):
- App-private files: `rwx------` (app only)
- Shared storage: Less restricted

**Inside Debian** (after extraction):
- Standard Unix perms apply
- User account owns home directory
- Proper ownership chain: `devpocket:devpocket` or custom user

**Key implementation detail** (Phase 6):
- BootstrapInstallerService must set correct ownership
- User account inside Debian (UID 1000+) controls files
- No conflict with Android UID (usually 10123)

## Testing Checklist

- [ ] OnboardingActivity completes, sets username
- [ ] BootstrapInstallerService creates Debian tree
- [ ] TheiaRuntimePaths.getIdeWorkspace() returns Debian path
- [ ] IDE opens with workspace at correct location
- [ ] Files created in IDE appear in $filesDir/linux/debian/home/username/code/
- [ ] Backward compat: existing external storage workspace still accessible if present

## Next: Phase 6 - User Account and Sudo Configuration

Phase 6 will:
1. Create actual Linux user account inside Debian chroot
2. Configure sudo access (passwordless or password-protected)
3. Set proper home directory and shell
4. Configure login environment (.bashrc, .bash_profile)
5. Allow optional user account customization in onboarding
