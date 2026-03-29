# DevPocket App - Development Guide

## Project Overview

**DevPocket** is a self-contained Android mobile IDE based on Eclipse Theia 1.68.0. It allows full development work on Android devices with Node.js runtime, terminal support, and AI agent integration (Kilo Code, Claude Code).

**Key Stats:**
- Package: `com.theia.mobile`
- Min SDK: 24, Target SDK: 36
- Architecture: Monorepo with Lerna (58 Theia extension packages)
- Frontend: React 18.2 + TypeScript + Lumino widgets
- Backend: Node.js (ARM64 binary bundled in APK)
- Terminal: XTerm.js + custom PTY handling

---

## Architecture

```
┌─ Android Layer ─────────────────────────────┐
│  MainActivity.kt → WebView → localhost:3100 │
│  TheiaBackendService (Foreground Service)   │
└─────────────────────────────────────────────┘
              ↓
┌─ Node.js Backend ───────────────────────────┐
│  Theia Backend (localhost:3100)              │
│  - Terminal Server (node-pty / pipe fallback)│
│  - File System Service                       │
│  - Plugin Host (Kilo Code, Claude Code)      │
└─────────────────────────────────────────────┘
              ↓
┌─ Frontend Bundle (React + Lumino) ──────────┐
│  - Mobile Shell with Draggable FAB          │
│  - Monaco Editor                             │
│  - XTerm.js Terminal Widget                 │
│  - File Explorer                             │
│  - VS Code Extensions Support                │
└─────────────────────────────────────────────┘
```

---

## Recent Changes (2026-03-29)

### 1. UI Scaling (Touch Targets Only)
**Policy:** Scale ONLY interactive elements (buttons, lists, menus) for fat-finger usability. DO NOT scale status bar, tabs, or structural elements.

**Files Modified:**
- `packages/mobile-shell/src/browser/style/mobile-shell.css` - Added rules for file trees (36px), file items (40px), menus (40px), buttons (42px)
- `packages/mobile-shell/src/browser/style/mobile-shell-fab.css` - FAB items: 48px inner, 44px outer

### 2. Draggable FAB
**Files Modified:**
- `packages/mobile-shell/src/browser/mobile-shell-fab-widget.tsx` - Touch drag with 10px threshold, localStorage persistence, `getUserPosition()` / `setPosition()` API
- `packages/mobile-shell/src/browser/mobile-shell-contribution.ts` - `updateFabViewportPosition()` respects user position, clamps to viewport
- `packages/mobile-shell/src/browser/style/mobile-shell-fab.css` - `.dragging` class, `touch-action: none`

### 3. Permissions
**Files Modified:**
- `android-app/android/app/src/main/AndroidManifest.xml` - Added: WAKE_LOCK, ACCESS_NETWORK_STATE, ACCESS_WIFI_STATE, VIBRATE, RECEIVE_BOOT_COMPLETED, REQUEST_INSTALL_PACKAGES, SYSTEM_ALERT_WINDOW
- `android-app/android/app/src/main/java/com/theia/mobile/TheiaBackendService.kt` - WakeLock acquire/release
- `android-app/android/app/src/main/java/com/theia/mobile/MainActivity.kt` - Overlay permission request

### 4. Terminal Improvements
**Pipe-Based Fallback (StreamTerminal):**
- `packages/process/src/node/terminal-process.ts`:
  - Output normalization: bare `\n` → `\r\n` for xterm
  - Line ending fix: only convert standalone `\r` (not `\r\n`)
  - Resize forwarding via `stty cols/rows`
  - TTY env spoofing: COLORTERM, COLUMNS, LINES, FORCE_COLOR, CLICOLOR_FORCE

**Extension Spawn Patching:**
- `packages/plugin-ext/src/hosted/node/plugin-host.ts` - Added TERM, COLORTERM, FORCE_COLOR, CLICOLOR_FORCE to spawn/spawnSync patches

**Native PTY Helper (Future):**
- `createNativePtyTerminal()` method added to check for `$THEIA_ANDROID_RUNTIME_BIN/pty-helper` binary
- Falls back to StreamTerminal if helper not found

---

## Build & Deployment Process

### 1. Make TypeScript Changes
```bash
cd "/home/muhammad-taha/Downloads/DevPocket/DevPocket App"
npm run build  # Compiles all packages
```

### 2. Build Frontend Bundle
```bash
cd products/theia-android-lite
npm run bundle  # Webpack builds frontend (→ lib/frontend/)
```

### 3. Copy Bundle to Android Assets
```bash
# Copy ALL files from products/theia-android-lite/lib/frontend
# EXCLUDING .gz files (they cause duplicate resource errors)
find products/theia-android-lite/lib/frontend ! -name "*.gz" -type f \
  -exec cp --parents {} android-app/android/app/src/main/assets/runtime/theia-android-lite/lib/frontend/ \;
```

### 4. Build Android APK
```bash
cd android-app/android
./gradlew clean assembleDebug  # → app/build/outputs/apk/debug/app-debug.apk
```

### 5. Install on Device
```bash
/home/muhammad-taha/Android/Sdk/platform-tools/adb uninstall com.theia.mobile
/home/muhammad-taha/Android/Sdk/platform-tools/adb install app-debug.apk
adb shell pm clear com.theia.mobile  # Clear cache to load fresh bundle
```

**⚠️ CRITICAL:** Always clear .gz files before building - they create duplicate resource errors in Gradle.

---

## Key Files & Locations

### Frontend
| File | Purpose |
|------|---------|
| `packages/mobile-shell/src/browser/mobile-shell-fab-widget.tsx` | Draggable FAB with touch handling |
| `packages/mobile-shell/src/browser/style/mobile-shell.css` | Main mobile layout (full-width override) |
| `packages/mobile-shell/src/browser/style/mobile-shell-fab.css` | FAB styling + drag feedback |
| `packages/mobile-shell/src/browser/mobile-shell-contribution.ts` | Mobile shell logic, viewport management |
| `products/theia-android-lite/lib/frontend/bundle.js` | Compiled frontend (23MB, includes all CSS) |

### Terminal
| File | Purpose |
|------|---------|
| `packages/process/src/node/terminal-process.ts` | PTY spawning, StreamTerminal fallback, native PTY helper |
| `packages/terminal/src/browser/terminal-widget-impl.ts` | XTerm.js UI widget |
| `packages/terminal/src/node/shell-terminal-server.ts` | Shell server, env management |
| `packages/plugin-ext/src/hosted/node/plugin-host.ts` | Android spawn() patching |

### Android
| File | Purpose |
|------|---------|
| `android-app/android/app/src/main/AndroidManifest.xml` | Permissions, activities, services |
| `android-app/android/app/src/main/java/com/theia/mobile/MainActivity.kt` | App entry point, permission requests |
| `android-app/android/app/src/main/java/com/theia/mobile/TheiaBackendService.kt` | Node.js backend lifecycle, WakeLock |
| `android-app/android/app/build.gradle` | Gradle build config |

---

## User Preferences & Constraints

### DO
- **Make CSS changes for touch UX** - Larger buttons, lists, menus (36-48px)
- **Test on actual Android device** - Desktop might not show mobile-specific issues
- **Keep FAB draggable working** - Users reposition it frequently
- **Preserve terminal improvements** - Color support, size detection, agent compatibility
- **Copy bundle WITHOUT .gz** - Gradle duplicate resource errors otherwise

### DON'T
- **Scale status bar or tabs** - User specifically wants only touch targets scaled
- **Add unnecessary documentation** - Only update CLAUDE.md when architecture changes
- **Ignore build errors** - Always check `npm run bundle` and `gradlew build` output
- **Forget to clear .gz files** - They silently break the build with duplicate errors
- **Modify Theia core logic** - Extend via mobile-shell package instead

### Environment Setup
- **Node.js:** ≥20
- **Android SDK:** API 36, NDK available
- **ADB Path:** `/home/muhammad-taha/Android/Sdk/platform-tools/adb`
- **Bundle Size:** Expected ~23MB (includes all changes)

---

## Common Tasks & Timing

| Task | Time | Steps |
|------|------|-------|
| CSS-only change | 5min | Edit CSS → npm run bundle → copy assets → gradlew build → adb install |
| Add TypeScript feature | 15min | Edit TS → npm run build → npm run bundle → copy assets → gradlew build → adb install |
| Debug terminal | 10min | Edit terminal-process.ts → npm run build → npm run bundle → copy → gradlew build → adb install → test |
| Full rebuild | 8min | (all steps above, includes webpack + gradle) |

**Bottleneck:** `npm run bundle` takes ~3-4 min (webpack compilation). Cache is incremental.

---

## Testing Checklist

After any change, verify on device:

- [ ] **Touch targets** - All buttons, lists, menus are easy to tap
- [ ] **FAB draggable** - Long-press and drag moves it, position persists after close
- [ ] **Terminal colors** - `echo $TERM` shows xterm-256color, colored output appears
- [ ] **Agents** - Kilo Code and Claude Code render properly with color
- [ ] **Permissions** - Storage, notifications, network state all available
- [ ] **Backend runs** - Check `adb logcat | grep TheiaBackendService`

---

## Performance Notes

- **APK Size:** ~480MB (includes Node.js binary + Theia + frontend)
- **Backend Startup:** 10-30 sec (health check on localhost:3100)
- **Bundle Size:** 23MB uncompressed, ~8MB gzipped (not in assets, increases APK minimally)
- **Memory:** ~200-300MB at idle, can spike to 400MB+ with heavy editing

---

## Future Work

1. **Native PTY Helper (aarch64)** - Compile `pty-helper` binary for full PTY semantics on Android
2. **Boot Auto-Start** - Use RECEIVE_BOOT_COMPLETED + BootReceiver for optional auto-start
3. **Haptic Feedback** - Use VIBRATE permission for long-press feedback on FAB drag
4. **Network Detection** - Use ACCESS_NETWORK_STATE to offline-aware UI

---

## Contact & Support

- **User Device:** 224b4cd86e0d7ece (connected via adb)
- **Project Path:** `/home/muhammad-taha/Downloads/DevPocket/DevPocket App`
- **IDE:** VS Code with this codebase open
- **Last Updated:** 2026-03-29 (UI scaling + FAB drag + terminal improvements + permissions)
