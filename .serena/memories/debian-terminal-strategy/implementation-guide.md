# Debian-First Terminal Strategy Implementation Guide

## Project Direction
DevPocket is shifting from Android-hosted terminal environment to a **Debian-first isolated terminal runtime** model.

### Key Changes
- **Old Model**: Terminal runs in patched Android environment, tools installed in external storage
- **New Model**: Terminal runs in isolated Debian rootfs, primary workspace in app-private Linux storage
- **User Experience**: Feels like laptop IDE, tools installed via `apt`, no heavy preinstalled packages

## Architecture Overview
```
Android App Layer (Kotlin)
  ↓
Bootstrap Runtime (minimal host environment)
  ↓
Debian Isolated Environment (user-facing)
  ↓
Terminal Shells & IDE Backend
```

## Implementation Phases

### Immediate Priority (Phases 1-6)
- Phase 1: Define runtime boundaries and directory layout
- Phase 2: Add onboarding flow in Kotlin
- Phase 3: Bootstrap installer service
- Phase 4: Debian rootfs strategy
- Phase 5: Filesystem model (app-private workspace)
- Phase 6: User account and sudo config

### Core Integration (Phases 7-8)
- Phase 7: Terminal launch into Debian
- Phase 8: Theia backend integration strategy

### UX & Features (Phases 9-15)
- Phase 9: Package management UI helpers
- Phase 10-15: Storage, repair, security, testing, rollout

## Key Design Decisions (Locked)
- ✅ Debian-first runtime
- ✅ app-private Linux workspace (not /storage/emulated/0/)
- ✅ apt-based package management
- ✅ Onboarding-driven installation
- ✅ No heavy preinstalled developer packages
- ✅ Shared storage = import/export only
- ✅ Hidden bootstrap runtime for repair

## Critical Files to Modify
- `android-app/android/app/src/main/java/com/theia/mobile/MainActivity.kt` - onboarding gate
- `android-app/android/app/src/main/java/com/theia/mobile/TheiaBackendService.kt` - backend orchestration, env setup
- `packages/terminal/src/node/shell-terminal-server.ts` - shell startup strategy
- `packages/terminal/src/browser/terminal-widget-impl.ts` - terminal connection logic

## Status
- Previous work (2026-03-30): Real PTY via node-pty implemented, Git/SSL support added
- Current: Starting Phase 1 of Debian-first strategy

## Next Steps
1. Phase 1: Document runtime boundaries and app directory structure
2. Phase 2: Create onboarding UI flow
3. Phase 3: Build bootstrap installer infrastructure
