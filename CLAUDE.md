# DevPocket App - Development Guide

## ⚡ CRITICAL: Always Keep Memory and docs(CLAUDE.md) Updated 

This project has a comprehensive memory system at `.claude/projects/-home-...-DevPocket-DevPocket-App/memory/`.

**MANDATORY for every session:**
1. **Read MEMORY.md first** - Quick overview of all project knowledge
2. **Before implementing changes** - Check if the area you're modifying is documented
3. **After finishing work** - Update memory files if you discovered new information
4. **When editing these key areas:**
   - Terminal behavior → Update `terminal_pty_implementation.md`
   - File locations → Update `file_structure_map.md`
   - Build process → Update `quick_reference.md`
   - Architecture → Update `architecture_patterns.md`
   - New learnings → Update `lessons_learned.md`

**Never search the entire codebase if memory exists.** Memory should prevent 90% of repository exploration.

---

## Project Overview

**DevPocket** is a self-contained Android mobile IDE based on Eclipse Theia 1.68.0. It allows full development work on Android devices with Node.js runtime, terminal support, and AI agent integration (Kilo Code, Claude Code).

**Key Stats:**
- Package: `com.theia.mobile`
- Min SDK: 24, Target SDK: 36
- Architecture: Monorepo with Lerna (58 Theia extension packages)
- Frontend: React 18.2 + TypeScript + Lumino widgets
- Backend: Node.js (ARM64 binary bundled in APK)
- Terminal: **Real PTY via node-pty (2026-03-30)** with pipe fallback safety

---

## Architecture

```
┌─ Android Layer ─────────────────────────────┐
│  MainActivity.kt → WebView → localhost:3100 │
│  TheiaBackendService (Foreground Service)   │
│  - Backend lifecycle, environment vars      │
│  - Permission requests, WakeLock            │
└─────────────────────────────────────────────┘
              ↓ (TCP 127.0.0.1:3100)
┌─ Node.js Backend ───────────────────────────┐
│  Theia Backend (Express + WebSockets)       │
│  - Terminal Server (REAL PTY via node-pty)  │
│  - File System Service                      │
│  - Plugin Host (Kilo Code, Claude Code)     │
│  - Extension management                     │
└─────────────────────────────────────────────┘
              ↓ (HTTP + WebSocket)
┌─ Frontend Bundle (React + Lumino) ──────────┐
│  - Mobile Shell with Draggable FAB          │
│  - Monaco Editor + Terminal Widget          │
│  - File Explorer + Preferences              │
│  - XTerm.js Terminal UI                     │
└─────────────────────────────────────────────┘
```

---

## Recent Changes (2026-03-30)

### ✅ IMPLEMENTED: Real PTY Terminal Support

**Problem Solved:** Terminal was pipe-based (no job control, no `isatty()`, no resize). Git/npm failed due to missing env vars and SSL certs.

**Solution:** Enable ARM64 `pty.node` (already bundled!) via node-pty, configure environment properly, bundle CA certificates.

**Files Modified (3 files):**

1. **`packages/process/src/node/terminal-process.ts` (Lines 220-260)**
   - Removed Android early-exit that skipped node-pty
   - Now tries `createPseudoTerminal()` first (real PTY)
   - Falls back to `createFallbackTerminal()` (pipe) only on error
   - Removed unused `createNativePtyTerminal()` method and imports

2. **`android-app/.../TheiaBackendService.kt` (Lines 239-263)**
   - Added `GIT_EXEC_PATH` → git finds helpers
   - Added `GIT_CONFIG_NOSYSTEM=1` → skip Termux config
   - Added `SSL_CERT_FILE`, `GIT_SSL_CAINFO`, etc. → CA bundle
   - Added `npm_config_bin_links=false` → avoid symlink errors
   - Added `TERM=xterm-256color`, `COLORTERM=truecolor`

3. **`products/theia-android-lite/scripts/build-runtime-assets.mjs`**
   - Added `resolveSymlinks()` function → 145 git-core symlinks → real files
   - Added improved bash wrapper with GIT_EXEC_PATH export
   - Added CA certificate bundling (curl.se cacert.pem)
   - Added no-op spawn-helper for node-pty compatibility

**Build Steps Updated:**
```bash
cd "/home/muhammad-taha/Downloads/DevPocket/DevPocket App"
npm run build                                    # ✅ TS compile (66 packages)
cd products/theia-android-lite
npm run bundle                                   # ✅ Webpack (~30s)
node scripts/build-runtime-assets.mjs            # ✅ Assemble runtime
cd ../../android-app/android
./gradlew clean assembleDebug                    # ✅ APK build
/home/muhammad-taha/Android/Sdk/platform-tools/adb uninstall com.theia.mobile
/home/muhammad-taha/Android/Sdk/platform-tools/adb install app/build/outputs/apk/debug/app-debug.apk
```

**Verification:**
```bash
tty                    # Should show /dev/pts/X (real PTY!)
echo $TERM             # Should show xterm-256color
python3 -c "import os; print(os.isatty(0))"  # Should print True
sleep 100 # Ctrl+C    # Should interrupt (job control works)
git clone https://...  # Should work with SSL
npm install express    # Should work without symlink errors
```

### Earlier Changes (2026-03-29)

#### 1. UI Scaling (Touch Targets Only)
**Policy:** Scale ONLY interactive elements (buttons, lists, menus) for fat-finger usability. DO NOT scale status bar, tabs, or structural elements.

**Files Modified:**
- `packages/mobile-shell/src/browser/style/mobile-shell.css` - File trees (36px), file items (40px), menus (40px), buttons (42px)
- `packages/mobile-shell/src/browser/style/mobile-shell-fab.css` - FAB items: 48px inner, 44px outer

#### 2. Draggable FAB
**Files Modified:**
- `packages/mobile-shell/src/browser/mobile-shell-fab-widget.tsx` - Touch drag with 10px threshold, localStorage persistence
- `packages/mobile-shell/src/browser/mobile-shell-contribution.ts` - Viewport management, position clamping
- `packages/mobile-shell/src/browser/style/mobile-shell-fab.css` - Dragging styles

#### 3. Permissions
**Files Modified:**
- `android-app/android/app/src/main/AndroidManifest.xml` - WAKE_LOCK, NETWORK permissions, etc.
- `android-app/.../TheiaBackendService.kt` - WakeLock acquire/release
- `android-app/.../MainActivity.kt` - Runtime permission requests

---

## Key Files & Locations

### 🔴 CRITICAL FILES (Never search for these)

| File | Purpose | Edit When |
|------|---------|-----------|
| `packages/process/src/node/terminal-process.ts` | **Terminal behavior** - PTY vs pipe logic | Terminal issues, PTY handling |
| `android-app/.../TheiaBackendService.kt` | **Backend startup & env vars** - Everything backend needs | Git fails, SSL errors, env config |
| `products/theia-android-lite/scripts/build-runtime-assets.mjs` | **Runtime assembly** - Binaries, libs, CA certs | Build issues, asset preparation |
| `products/theia-android-lite/webpack.config.js` | **Module bundling** - pty.node redirection | Module resolution, bundling strategy |

### Frontend
| File | Purpose |
|------|---------|
| `packages/mobile-shell/src/browser/mobile-shell-fab-widget.tsx` | Draggable FAB with touch handling |
| `packages/mobile-shell/src/browser/style/mobile-shell.css` | Main mobile layout (full-width override) |
| `packages/mobile-shell/src/browser/style/mobile-shell-fab.css` | FAB styling + drag feedback |
| `packages/mobile-shell/src/browser/mobile-shell-contribution.ts` | Mobile shell logic, viewport management |
| `packages/terminal/src/browser/terminal-widget-impl.ts` | XTerm.js UI widget |

### Terminal
| File | Purpose |
|------|---------|
| `packages/terminal/src/node/shell-terminal-server.ts` | Shell server, env management |
| `packages/terminal/src/node/shell-process.ts` | Shell executable path & args resolution |
| `packages/plugin-ext/src/hosted/node/plugin-host.ts` | spawn() patching for Android |

### Android Native
| File | Purpose |
|------|---------|
| `android-app/android/app/src/main/AndroidManifest.xml` | Permissions, services, activities |
| `android-app/android/app/src/main/java/com/theia/mobile/MainActivity.kt` | App entry point, permission requests |
| `android-app/android/app/src/main/java/com/theia/mobile/TheiaRuntimePaths.kt` | Runtime path resolution |
| `android-app/android/app/src/main/java/com/theia/mobile/AssetExtractor.kt` | APK asset extraction |
| `android-app/android/app/build.gradle` | Gradle build config |

### Build & Bundling
| File | Purpose |
|------|---------|
| `products/theia-android-lite/webpack.config.js` | Webpack config, pty.node redirection |
| `products/theia-android-lite/scripts/build-runtime-assets.mjs` | Runtime assembly, CA certs, symlink resolution |

### Runtime Assets (In APK)
| Path | Purpose |
|------|---------|
| `android-app/.../assets/runtime/bin/` | Termux binaries (node, bash, git, rg) |
| `android-app/.../assets/runtime/lib/` | Termux shared libraries (.so files) |
| `android-app/.../assets/runtime/etc/ca-certificates/` | CA certificate bundle (cacert.pem) |
| `android-app/.../assets/runtime/theia-android-lite/lib/backend/native/` | pty.node, rg, spawn-helper |

---

## Build & Deployment Process

### Quick Commands (Always Execute in Order)
```bash
# 1. Compile TypeScript
cd "/home/muhammad-taha/Downloads/DevPocket/DevPocket App"
npm run build

# 2. Bundle frontend with webpack
cd products/theia-android-lite
npm run bundle

# 3. Assemble runtime assets (binaries, libs, CA certs, symlink resolution)
node scripts/build-runtime-assets.mjs

# 4. Build Android APK
cd ../../android-app/android
./gradlew clean assembleDebug

# 5. Install on device
/home/muhammad-taha/Android/Sdk/platform-tools/adb uninstall com.theia.mobile
/home/muhammad-taha/Android/Sdk/platform-tools/adb install app/build/outputs/apk/debug/app-debug.apk
```

### Timing Expectations
- `npm run build`: ~2-3 min (incremental, cached)
- `npm run bundle`: ~30s (webpack, cached)
- `node scripts/build-runtime-assets.mjs`: ~10s
- `./gradlew assembleDebug`: ~100s (cached)
- **Total**: ~5-6 minutes

### Critical Warnings
⚠️ **Always:**
- Clear `.gz` files before building (Gradle duplicate resource errors)
- Run build commands in correct directory
- Wait for health check on device before testing

⚠️ **Never:**
- Skip `build-runtime-assets.mjs` (symlinks won't be resolved, git fails on device)
- Hardcode absolute paths (use `TheiaRuntimePaths` helper in Kotlin)
- Use relative `..` in LD_LIBRARY_PATH (Android linker rejects them)

---

## User Preferences & Constraints

### DO ✅
- **Check memory before exploring** - `.claude/projects/.../memory/` has all knowledge
- **Update memory after changes** - Future sessions depend on it
- **Use file structure map** - Don't search, it's documented
- **Make CSS changes for touch UX** - Larger buttons, lists, menus (36-48px)
- **Test on actual Android device** - Desktop doesn't show all issues
- **Keep FAB draggable** - Users reposition it frequently
- **Copy bundle WITHOUT .gz** - Gradle errors otherwise
- **Use real PTY features** - Terminal now supports job control, isatty(), resize

### DON'T ✗
- **Ignore memory files** - They prevent hours of searching
- **Modify Theia core** - Extend via mobile-shell instead
- **Forget environment variables** - They configure behavior without code changes
- **Use hardcoded paths** - Always use `TheiaRuntimePaths`
- **Skip symlink resolution** - Android AssetManager rejects them

### Environment Setup
- **Node.js:** ≥20
- **Android SDK:** API 36, NDK available
- **ADB Path:** `/home/muhammad-taha/Android/Sdk/platform-tools/adb`
- **Device:** 224b4cd86e0d7ece (connected)
- **CA Certs:** `runtime/etc/cacert.pem` (226KB, curl.se)

---

## Common Tasks & Locations

### "I want to change X, where do I go?"

| Need | File | Line/Notes |
|------|------|-----------|
| **Terminal behavior** | `packages/process/src/node/terminal-process.ts` | Lines 220-260: `startTerminal()` function |
| **Git/SSL env vars** | `android-app/.../TheiaBackendService.kt` | Lines 239-263: environment setup |
| **Touch scaling** | `packages/mobile-shell/src/browser/style/mobile-shell.css` | CSS rules for targets |
| **FAB logic** | `packages/mobile-shell/src/browser/mobile-shell-fab-widget.tsx` | Touch drag handlers |
| **Shell selection** | `packages/terminal/src/node/shell-process.ts` | `getShellExecutablePath()` |
| **Build assets** | `products/theia-android-lite/scripts/build-runtime-assets.mjs` | Runtime assembly |
| **Webpack bundling** | `products/theia-android-lite/webpack.config.js` | Module replacement, aliases |
| **Permissions** | `android-app/android/app/src/main/AndroidManifest.xml` | Manifest permissions |
| **Backend startup** | `android-app/.../TheiaBackendService.kt` | `createProcessBuilder()` |

**Time to find:** If using memory = <1 min. If searching = 10-30 min.

---

## Testing Checklist

After any change, verify on device:

- [ ] **Build succeeds** - No Gradle errors, APK created
- [ ] **App installs** - `adb install` succeeds
- [ ] **Backend starts** - `adb logcat | grep TheiaBackendService` shows healthy startup
- [ ] **Frontend loads** - WebView shows IDE


## Performance Notes

- **APK Size:** ~480MB (mostly Node.js binary + libraries)
- **Backend Startup:** 10-30 sec (health check on localhost:3100)
- **Bundle Size:** 23MB uncompressed (not in assets, minimal APK impact)
- **Memory at Idle:** ~200-300MB, can spike to 400MB+ during heavy editing
- **APK Extraction:** ~1-2 min on device (async, happens once on first launch)
- **Terminal Creation:** <1 sec (uses real PTY now, no delays)

---

## Memory System (2026-03-30)

**Location:** `.claude/projects/-home-...-DevPocket-DevPocket-App/memory/`

**Files:**
- `MEMORY.md` - Index of all knowledge (read this first!)
- `terminal_pty_implementation.md` - Real PTY changes, technical details
- `file_structure_map.md` - Critical files & purposes (use instead of searching!)
- `quick_reference.md` - "I want to..." guide, build commands
- `android_runtime_structure.md` - Runtime bundling, extraction, env vars
- `architecture_patterns.md` - Monorepo structure, design patterns, data flows
- `lessons_learned.md` - Why decisions were made, mistakes avoided
- `project_devpocket.md` - Project overview (old)

**Usage Pattern:**
1. Start session → Read `MEMORY.md` (5 seconds)
2. Need to find file → Check `file_structure_map.md` (1 second)
3. Need to change X → Check `quick_reference.md` (1 second)
4. Finished work → Update relevant memory file (2 minutes)

**Maintains:** 48KB of curated knowledge prevents 90% of codebase exploration.

---

## Future Work

1. ✅ **Real PTY via node-pty** - COMPLETED 2026-03-30 (was using pipe fallback)
2. ✅ **Git support with CA certs** - COMPLETED 2026-03-30
3. ✅ **npm symlink compatibility** - COMPLETED 2026-03-30
4. **Native PTY Helper (optional)** - Custom pty-helper binary for advanced control
5. **Boot Auto-Start** - RECEIVE_BOOT_COMPLETED + BootReceiver
6. **Haptic Feedback** - VIBRATE permission + Android Vibrator API
7. **Network Detection** - ACCESS_NETWORK_STATE for offline UI

---

## Contact & Support

- **User Device:** 224b4cd86e0d7ece (connected via adb)
- **Project Path:** `/home/muhammad-taha/Downloads/DevPocket/DevPocket App`
- **IDE:** VS Code with this codebase open
- **Memory Path:** `.claude/projects/-home-...-DevPocket-DevPocket-App/memory/`
- **Last Updated:** 2026-03-30 (Real PTY, git/SSL, npm fixes + memory system)
- **Status:** ✅ Fully functional terminal with real PTY, git, npm support
