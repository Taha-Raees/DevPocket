# Phases 8-15: Remaining Phases - PLANNING SUMMARY

**Date**: 2026-03-31
**Status**: STRATEGIC PLANNING (implementation in later phases)

## Phase 8: Theia Backend Integration Strategy

**Decision Required**: Where should the Theia backend Node.js process run?

### Option A: Backend in Bootstrap Runtime (Stage 1 MVP Recommended)
- Backend runs in host Android environment (Layer 2: Bootstrap Runtime)
- Terminal shells execute inside Debian (Layer 3)
- File access: Backend uses workspace in app-private Debian home
- WebSocket bridge: IDE in WebView ↔ Backend on host ↔ Terminals in Debian

**Pros:**
- Simpler to implement first
- Backend has full Android permissions
- Can service multiple terminal sessions
- Easy debugging (all on host)

**Cons:**
- Backend doesn't use Debian tools natively
- Two-layer complexity (backend+debian)

### Option B: Backend Inside Debian (Stage 2, Post-Stability)
- Backend Node.js inside chroot/container
- Runs as Debian user with Debian tools
- Direct access to Debian package managers (apt for extensions)
- Container-native experience

**Pros:**
- "Proper" Linux environment for backend
- Native Debian tool access
- Simpler mental model (everything in Debian)

**Cons:**
- More complex setup (chroot backend)
- Android permissions harder to manage
- Harder to debug initial issues

**Recommendation for Phase 8**: 
- Implement Stage 1 (Option A) for MVP stability
- Plan transition to Stage 2 (Option B) after proving concept

### Implementation Steps (Phase 8)
1. Document current backend path resolution
2. Identify all backend dependencies on Android
3. Map file path accesses (filesystem locations)
4. Decide: isolate or unify?
5. Create path resolution helpers if splitting
6. Test backend accessing Debian workspace

**Key File**: `android-app/.../TheiaBackendService.kt` (already updated for workspace)

---

## Phase 9: Package Management UX

**Goal**: Make installing tools feel natural and documented

### Features to Add
1. **"Install Tools" dialog in UI**
   - Quick install buttons for: git, python, node, gcc, etc.
   - Shows progress and output
   - Maps to `apt install` commands

2. **Suggested packages based on file type**
   - `.py` file → suggest python3
   - `.js` file → suggest node
   - `.c` file → suggest gcc
   - etc.

3. **Terminal integration**
   - Help menu showing common install commands
   - Copy-paste friendly command examples

4. **Post-install verification**
   - Check `git --version`, `python3 --version`, etc.
   - Show success/failure with suggestions

### Implementation
- Add "Tools" menu in mobile-shell
- Create PackageManager service
- Hook into terminal output to detect missing commands
- Maintain APT package cache in app-private storage

---

## Phase 10: Storage Permissions & Import/Export

**Goal**: Reduce reliance on MANAGE_EXTERNAL_STORAGE, add explicit import/export

### Features
1. **Import Project**
   - User picks .zip file from Android storage
   - Extract to `/data/data/.../workspace/`
   - Show progress

2. **Export Project**
   - User selects folder in IDE
   - Compress to .zip
   - Save to Downloads or custom location
   - Share options (email, cloud, etc.)

3. **Backup/Restore**
   - One-click backup of entire workspace
   - Auto-backups to app-private storage
   -Restore from backup

### Files to Modify
- `android-app/.../MainActivity.kt` - permission audits
- Add ImportExportService
- Add import/export UI to mobile-shell

**Permission Impact**:
- May reduce need for `MANAGE_EXTERNAL_STORAGE` to "special use"
- Use `ACTION_OPEN_DOCUMENT_TREE` for scoped access

---

## Phase 11: Reliability & Repair Flows

**Goal**: Self-healing when things break

### Health Checks
1. **Startup checks**
   - Debian rootfs exists and readable
   - User home directory has correct owner
   - .bashrc and shell configs present
   - Sudo policy file exists

2. **Runtime checks**
   - Backend health check (already exists)
   - Terminal launch test
   - Apt functionality test
   - File permissions sanity check

3. **Periodic checks**
   - Daily health diagnostics running
   - Detect and report issues
   - Auto-repair if safe (e.g., recreate .bashrc)

### Repair Actions
1. **Automatic repairs** (safe, auto-run)
   - Recreate missing .bashrc
   - Reset broken shell configs
   - Clear temp cache files
   - Rebuild apt cache

2. **User-initiated repairs**
   - "Verify Installation" button
   - "Repair Debian" option
   - Full reinstall (with backup)

3. **Advanced diagnostics**
   - Check disk space
   - Verify checksum of key files
   - Test apt repository connectivity
   - Show detailed logs

### Implementation
- Create HealthCheckService
- Log health status to SharedPreferences
- Surface issues in settings page
- Auto-repair on startup if needed

---

## Phase 12: Security & Trust Model

**Goal**: Establish clear security boundaries

### Considerations

1. **GPG Signature Verification**
   - Verify rootfs archive with GPG
   - Verify manifest with GPG key
   - Key distribution and pinning

2. **SSL/TLS Certificate Management**
   - Root CA bundle in Debian
   - Certificate updates via apt
   - Pin important certificates (GitHub, package repos)

3. **sudo Password Security**
   - If password mode: hash with SHA512 crypt
   - don't store plaintext
   - Timeout after inactivity
   - Audit sudo usage

4. **Data At Rest**
   - Workspace in app-private storage (encrypted by Android if device encrypted)
   - No sensitive files in shared storage
   - Clear temp files after operations

5. **Access Control**
   - Debian user cannot escape chroot (future phases)
   - Android foreground service cannot be killed by user (currently allowed)
   - Explicit permissions for shared storage access

### Files to Create/Modify
- Create SecurityManager service
- Update BootstrapInstallerService with GPG verification
- Add certificate pinning in network layer
- Audit all SharedPreferences for sensitive data

---

## Phase 13: Performance & Footprint Controls

**Goal**: Keep app lightweight and responsive

### Features

1. **Disk usage UI**
   - Show workspace size
   - Show package cache size
   - Show logs usage
   - Quick cleanup buttons

2. **Cache management**
   - Clear apt cache (apt cache clean)
   - Clear package downloads (apt clean)
   - Clear terminal history
   - Clear temp install files

3. **Auto-cleanup**
   - Delete unused extracted packages
   - Rotate logs (keep 30 days)
   - Cleanup failed installs

4. **Memory optimization**
   - Debian rootfs mounted read-only (future)
   - Lazy-load extensions
   - Monitor backend memory usage
   - Kill zombie processes

5. **Startup optimization**
   - Measure boot time
   - Profile backend startup
   - Parallelize initialization
   - Show progress accurately

### Metrics to Monitor
- First launch boot time: target <30s
- Terminal startup: target <1s
- IDE ready time: target <5s
- Memory at idle: target 200-300MB
- Package operation time: apt update should show progress

---

## Phase 14: Testing Strategy

**Comprehensive test plan for all phases**

### Test Categories

1. **Unit Tests**
   - OnboardingStateManager state transitions
   - UserAccountConfig username validation
   - TheiaRuntimePaths path resolution

2. **Integration Tests**
   - Onboarding → Backend startup flow
   - Terminal → Debian environment
   - File import/export round-trip
   - Health checks → auto-repair

3. **End-to-End Tests**
   - Fresh install: launch → IDE ready
   - Interrupted install: resume correctly
   - Upgrade: existing users get new version
   - Repair: corrupt install auto-repairs

4. **Device Tests** (on real Android device)
   - First launch on clean device
   - Terminal: git clone over HTTPS
   - apt: install package and use it
   - File: create file in IDE, export and verify
   - Network: offline mode graceful handling
   - Low storage: warnings and cleanup suggestions

5. **Stress Tests**
   - Large file operations (>1GB clone)
   - Many terminal windows (10+)
   - Long idle time (24+ hours)
   - Rapid app launch/background cycles

### Test Artifacts
- Automated test suite in `test/` directory
- Device test checklist for QA
- Performance benchmark suite
- Health check test harness

---

## Phase 15: Rollout Order & Deprecation

**Timeline and strategy for release**

### Release Strategy

**Phase 15A: Alpha (Internal Only)**
- Months 1-2 after Phase 1 completion
- Core team + early testers
- Focus: onboarding → IDE launch
- Collect: crash logs, UX feedback

**Phase 15B: Beta (EarlyAccess)**
- Months 3-4
- Wider testing: ~100-500 testers
- Full feature set Phases 1-8
- Focus: stability, permission issues, terminal correctness

**Phase 15C: Production (GA)**
- Month 5+
- Public Play Store release
- Phases 1-14 complete
- Ongoing: security updates, performance tweaks

### Deprecation Path

1. **Keep external storage support (Years 1-2)**
   - Existing users keep workspace in `/storage/emulated/0/Documents/DevPocket`
   - New users go to app-private by default
   - Optional migration tool available

2. **Announce deprecation (Year 2)**
   - In-app notification: "Migrate to app-private storage"
   - Help documentation
   - Settings: show current location, offer migration

3. **Enforce migration (Year 3+)**
   - New features only work with app-private workspace
   - Can still access old projects via import

### Rollout Checklist

- [ ] All tests passing
- [ ] Crash-free baseline on real devices
- [ ] 3+ devices tested (different Android versions)
- [ ] Battery test: 8 hour usage normal power drain
- [ ] Offline mode: graceful degradation
- [ ] Network: retries on failure
- [ ] Storage: cleanup on removal
- [ ] Privacy: no external logs, telemetry opt-in
- [ ] Documentation: user guide + troubleshooting

---

## Integrated Timeline

**Realistic full development schedule:**

- **Months 1-2**: Phases 1-6 (what's been done)
- **Months 2-3**: Phases 7-8 (terminal + backend decision)
- **Months 3-4**: Phases 9-10 (UX polish + import/export)
- **Months 4-5**: Phases 11-12 (reliability + security)
- **Months 5-6**: Phase 13 optimization
- **Months 6-7**: Phase 14 testing + bug fixes
- **Month 8**: Phase 15 rollout (alpha)
- **Months 9-10**: Beta testing
- **Month 11**: GA production release

**Total**: ~11 months from spec to production (with realistic buffers)

---

## Success Criteria (All 15 Phases Complete)

✅ Users install app from Play Store
✅ First launch triggers onboarding (minutes)
✅ Debian environment installed automatically (~5 min)
✅ IDE opens with real terminal ready
✅ `apt update && apt install git python3 node` works
✅ Can clone GitHub repos over HTTPS
✅ File permissions work correctly
✅ Editor saves files to correct location
✅ Export project to shared storage
✅ Recover from corruption automatically
✅ Memory/battery/performance acceptable
✅ All tests passing
✅ Documentation complete
✅ Users happy 😊
