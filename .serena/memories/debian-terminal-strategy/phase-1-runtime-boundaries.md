# Phase 1: Runtime Boundaries & Directory Structure

**Status**: IMPLEMENTING
**Date**: 2026-03-31

## Current State (Pre-Phase 1)
- App launches → immediately starts backend service
- Backend runs in host Android environment (not containerized)
- Workspace at `/storage/emulated/0/Documents/DevPocket` (external public storage)
- No onboarding, environment is patched to work in Android context
- Terminal shells launched directly in Android environment

## New Model (Post-Phase 1)

### 1. Application Layers (Bottom to Top)

```
┌──────────────────────────────────────┐
│ Layer 5: IDE & Terminal UI           │
│ ├─ Lumino widget shell                │
│ ├─ Monaco editor + Monaco themes      │
│ ├─ XTerm.js terminal widget           │
│ └─ Mobile FAB controls                │
└──────────────────────────────────────┘
              ↑ WebSocket
┌──────────────────────────────────────┐
│ Layer 4: Theia Backend Node.js        │
│ ├─ Express HTTP server on 127.0.0.1  │
│ ├─ File system service                │
│ ├─ Terminal server (accepts PTY reqs) │
│ ├─ Plugin host (Code agents)          │
│ └─ Extension manager                  │
│                                       │
│ *Decision: Keep here or move to      │
│  Layer 3? See Phase 8 analysis*      │
└──────────────────────────────────────┘
         ↑ Process exec / stdin/stdout
┌──────────────────────────────────────┐
│ Layer 3: Debian Isolated Environment  │
│ ├─ Debian rootfs (chroot/container)   │
│ ├─ User account (devpocket / custom)  │
│ ├─ Shell: /bin/bash                   │
│ ├─ Package manager: apt                │
│ ├─ Standard UNIX utilities             │
│ └─ Optional: git, python, node, etc.  │
│   (user-installed, not preinstalled)   │
└──────────────────────────────────────┘
        ↑ sys-calls wrapper
┌──────────────────────────────────────┐
│ Layer 2: Bootstrap Runtime (Host)     │
│ ├─ Minimal Termux-like runtime        │
│ ├─ Node.js binary (ARM64)             │
│ ├─ Bash + basic shell utilities       │
│ ├─ curl/wget (download rootfs)        │
│ ├─ tar/unzip (extract Debian)         │
│ ├─ chroot/mount tools                 │
│ └─ CA certificates (for SSL)          │
│                                       │
│ *Used only for setup, repair,        │
│  recovery. Hidden from users.*       │
└──────────────────────────────────────┘
       ↑ Android syscalls
┌──────────────────────────────────────┐
│ Layer 1: Android App (Kotlin)         │
│ ├─ MainActivity.kt (UI + onboarding)  │
│ ├─ TheiaBackendService (orchestration)│
│ ├─ WebView (frontend rendering)       │
│ ├─ Permission management              │
│ └─ Foreground service lifecycle       │
└──────────────────────────────────────┘
        ↑ System API calls
┌──────────────────────────────────────┐
│ Android OS (Linux kernel + bionic)    │
└──────────────────────────────────────┘
```

### 2. Directory Structure (App-Private Linux Workspace)

**Base path**: Uses `getFilesDir()` + `/linux` (app-private, equivalent to `~/.devpocket/`)

```
$DEVPOCKET_BASE/
  ├─ INSTALL_MANIFEST.json          # Version, status, checksum, migration log
  │
  ├─ bootstrap/                     # Hidden bootstrap runtime (setup/repair only)
  │  ├─ bin/
  │  │  ├─ node                     # Node.js ARM64 binary
  │  │  ├─ bash, sh                 # Shell executables
  │  │  ├─ curl, wget               # Downloaders
  │  │  ├─ tar, unzip, gzip         # Extractors
  │  │  ├─ chroot (if needed)       # Container tool (optional)
  │  │  └─ ... (minimal host utils)
  │  │
  │  ├─ lib/                        # Shared libraries for bootstrap
  │  │  └─ lib{c,m,pthread,etc}.a
  │  │
  │  ├─ etc/
  │  │  ├─ ca-certificates/
  │  │  │  └─ cacert.pem           # SSL CA bundle (curl.se)
  │  │  ├─ profile.d/              # Shell rc scripts
  │  │  └─ ... (minimal OS config)
  │  │
  │  ├─ installer/                 # Self-extracting installer code
  │  │  ├─ install.js              # Rootfs downloader + extractor
  │  │  ├─ verify.js               # Checksum validation
  │  │  ├─ repair.sh               # Recovery tool
  │  │  └─ manifest.json           # Installer version, artifacts
  │  │
  │  └─ theia-bootstrap/           # Minimal Node.js entry for onboarding UI
  │     └─ lib/backend/            # Simple HTTP server for setup wizard
  │
  ├─ debian/                        # User-facing Debian rootfs
  │  ├─ bin/                        # Standard Debian /bin
  │  ├─ etc/                        # Debian /etc
  │  ├─ lib, lib64, usr/            # System files
  │  ├─ home/                       # User home inside rootfs
  │  │  └─ devpocket/              # Default user home (can be custom)
  │  │     ├─ .bashrc, .bash_profile
  │  │     ├─ .gitconfig
  │  │     ├─ .ssh/                # User SSH keys
  │  │     └─ code/                # Default project workspace
  │  │
  │  ├─ root/                       # Debian root account home (rarely used)
  │  └─ var/, tmp/, opt/, srv/     # Standard Debian directories
  │
  ├─ home/                          # Symlink: points to debian/home/devpocket/ or custom user
  │
  ├─ workspace/                     # User projects (alternative to debian/home/devpocket/code)
  │  ├─ project-1/
  │  ├─ project-2/
  │  └─ ... (user creates these in terminal via mkdir)
  │
  ├─ shared-imports/                # Imported files from Android shared storage
  │  ├─ Downloads/                  # Imports from Android downloads
  │  ├─ Documents/                  # Imports from Android docs
  │  └─ ... (explicit user import operations)
  │
  ├─ exports/                       # Files user wants to share back to Android
  │  ├─ myproject.zip              # Exported project backup
  │  └─ ... (explicit user export operations)
  │
  ├─ cache/                         # apt, package manager caches
  │  ├─ apt/                        # apt package downloads (apt-cache)
  │  ├─ pip/                        # pip cache (if python installed)
  │  ├─ npm/                        # npm cache (if node installed)
  │  └─ ... (other package managers)
  │
  ├─ temp/                          # Temporary install files, downloads during setup
  │  └─ ... (cleaned after install completes)
  │
  ├─ config/                        # Theia IDE configuration (moved to debian/home/...)
  │  ├─ settings.json
  │  ├─ keymaps.json
  │  └─ ... (mirrors Theia preferences)
  │
  ├─ extensions/                    # Installed Theia extensions
  │  ├─ builtin-extensions/
  │  ├─ kilo-code/
  │  ├─ claude-agent/
  │  └─ ... (user-installed extensions)
  │
  ├─ logs/                          # Application logs
  │  ├─ backend.log
  │  ├─ installer.log
  │  ├─ repair.log
  │  └─ terminal-session-YYYY-MM-DD.log
  │
  └─ metadata/                      # Runtime metadata
     ├─ status.json                # Current state: onboarding?, installed?, running user?
     ├─ sudo-policy.json          # Passwordless or pwd-protected sudo config
     ├─ user-config.json          # Username, display name, locale
     └─ health-check.json         # Last health check results
```

### 3. Key Design Decisions

#### A. Workspace Location
| Model | Path | Pros | Cons |
|-------|------|------|------|
| **Old** | `/storage/emulated/0/Documents/DevPocket` | Visible in Android | Public, permission issues |
| **New** | `$DEVPOCKET_BASE/debian/home/devpocket` | Private, standard Unix | Hidden from Android |
| **Alternative** | `$DEVPOCKET_BASE/workspace/` | Isolated from system | Further from Unix home convention |

**Decision**: Primary home = `debian/home/devpocket/code`. Secondary workspace = `$DEVPOCKET_BASE/workspace/`.

#### B. Backend Placement Decision (Deferred to Phase 8)
Two options to document:
1. **Option A (Stage 1)**: Backend runs in Layer 2 (Bootstrap), terminals execute in Layer 3 (Debian)
2. **Option B (Stage 2)**: Backend runs inside Layer 3 (Debian chroot)

**Phase 1 Decision**: Document both, decide in Phase 8 after stability proven.

#### C. Bootstrap vs Debian Separation
- **Bootstrap** (Layer 2): Minimal, hidden, only for installation + recovery
- **Debian** (Layer 3): Full user-facing environment, user installed

**Decision**: Strict separation prevents bloat, enables lightweight installer, supports repairs.

#### D. Home Directory Management
| Decision | Implication |
|----------|-------------|
| Default user: `devpocket` | Hardcoded in install, configurable via onboarding |
| User home: `debian/home/<username>/` | Standard Unix location inside rootfs |
| Workspace subdirectory: `code/` | Default project space, user can use entire home |
| Symlink `home/` → current user home | Convenience shortcut for scripts |

**Decision**: Default `devpocket`, ask in onboarding, allow change in settings later.

#### E. Import/Export Model (vs Direct Shared Storage Access)
| Old | New |
|-----|-----|
| Workspace at `/storage/emulated/0/` | Workspace in app-private Linux. |
| Implicit shared storage access | Explicit import/export operations. |
| Permission hacks | Clear permission model. |

**Decision**: App-private is default, shared storage is optional import/export only.

### 4. Environment Variables (New Model)

**Bootstrap context** (used during install, repair):
```bash
DEVPOCKET_BASE=/data/data/com.theia.mobile/files/linux
DEVPOCKET_BOOTSTRAP_BIN=$DEVPOCKET_BASE/bootstrap/bin
DEVPOCKET_INSTALLER=$DEVPOCKET_BASE/bootstrap/installer
```

**Debian context** (used by user working inside IDE):
```bash
HOME=/home/devpocket        # Inside Debian rootfs
DEVPOCKET_ROOT=/            # / inside Debian (transparent)
DEVPOCKET_WORKSPACE=/home/devpocket/code
DEVPOCKET_PROJECTS=/workspace  # Alternative workspace
```

### 5. Executable Ownership & Permissions

**Bootstrap binaries** (Layer 2):
- Owned by app UID (e.g., 10123)
- Executable: `755` or `700`
- Located in app-private dir (Android enforces protection)

**Debian tree** (Layer 3):
- Inside extracted rootfs
- Permissions copied from Debian image
- User `devpocket` (numeric UID inside Debian, e.g., 1000) owns home
- Standard Unix permission model applies

### 6. File Extraction & Symlink Resolution

**Problem**: Android's AssetManager doesn't preserve symlinks. Git's `git-core/` helpers are symlinks.

**Solution** (Already implemented in build):
- `build-runtime-assets.mjs` resolves symlinks to real files in bootstrap
- Debian rootfs includes proper symlinks (extracted as real files then recreated)

### 7. Health Check & Status Tracking

**File**: `$DEVPOCKET_BASE/metadata/status.json`

```json
{
  "app_version": "1.0.0",
  "installation_state": "ready",     // "first_run", "onboarding", "installing", "ready", "corrupted"
  "installed_at": "2026-03-31T10:30:00Z",
  "rootfs_version": "debian-minimal-2026-03-31",
  "user_account": "devpocket",
  "sudo_mode": "passwordless",       // or "password"
  "last_backend_start": "2026-03-31T11:00:00Z",
  "backend_healthy": true,
  "terminal_tested": true,
  "notes": "Health check passed"
}
```

## Key Decisions (Locked for Implementation)

✅ **Debian-first** - User-facing environment
✅ **Bootstrap minimal** - Setup/repair only, hidden  
✅ **App-private workspace** - `/data/data/com.theia.mobile/files/linux/`
✅ **Explicit import/export** - Shared storage not default
✅ **Default user "devpocket"** - Configurable in onboarding
✅ **Passwordless sudo default** - Recommended mobile-friendly choice (can change in settings)
✅ **Layer separation strict** - No bootstrap tools in user PATH

## Next Phase

**Phase 2**: Create Kotlin onboarding activity with states:
- First launch detection
- Welcome screen
- Login/account setup
- Terminal installation confirmation
- Installation progress UI
- Ready to open IDE

**Phase 3**: Bootstrap installer service to download & verify Debian rootfs
