# Phase 4: Debian Rootfs Strategy - PLANNING & ARCHITECTURE

**Date**: 2026-03-31
**Status**: DEFINING STRATEGY

## Goal
Define the source, build process, versioning, and distribution strategy for the minimal Debian CLI rootfs that users will install on first launch.

## Options Analysis

### A. Rootfs Source Options

#### Option 1: Official Debian CLI Image
**Pros:**
- Fully official, audited, trusted
- Standard package versions
- Regular security updates from Debian project
- Clear upgrade path

**Cons:**
- May include unnecessary packages
- Size might be larger than needed
- Requires post-download minimal trimming

**Recommendation:** Start here for MVP (Minimum Viable Product)
- Download official Debian minimal netinst ISO or rootfs
- Trim to ~600MB uncompressed
- Include only: base system, apt, ca-certificates, bash, core utilities

#### Option 2: Custom DevPocket Build
**Pros:**
- Optimized for mobile (exact packages needed)
- Smaller size (~400-500MB)
- Faster operations (fewer packages)
- Custom optimizations possible

**Cons:**
- Maintenance burden (build and test infrastructure)
- Need to track Debian security updates
- Complex rebuild and release process

**Recommendation:** Post-MVP phase 2 (Phase 4B)
- After MVP stabilizes, transition to custom builds
- Use Debian Devuan or Buildroot for base
- Automate builds with CI/CD
- Release monthly security updates

#### Option 3: Termux/Alpine Derivative
**Pros:**
- Already optimized for mobile
- Smaller (Alpine ~350MB)
- Well-tested on Android

**Cons:**
- Different package manager from standard Linux
- User training needed ("apt" vs "apk")
- Less standard

**Recommendation:** Not recommended for Phase 4
- Stick with Debian for user familiarity

### B. Build & Release Infrastructure

#### Recommended Approach (MVP)

**Step 1: Create minimal Debian rootfs image**
```bash
# Use debootstrap to create minimal Debian image
debootstrap --variant=minbase \
  --include=apt,ca-certificates,bash,curl,wget,git,vim \
  bookworm debian-minimal/

# Size optimization
du -sh debian-minimal/    # Should be ~400-600MB

# Create archive
tar -czf debian-minimal-2026-03-31.tar.gz debian-minimal/
```

**Step 2: Host on release server**
- Domain: `releases.devpocket.dev`
- Path: `/debian/minimal/debian-minimal-YYYY-MM-DD.tar.gz`
- Also provide: `MANIFEST.json` with checksums and metadata

**Step 3: CI/CD Pipeline**
- GitHub Actions or similar automate builds
- Build trigger: monthly (security updates) or on-demand
- Automatic SHA256 generation
- Upload to release server
- Update `MANIFEST.json` and documentation

**Step 4: Version Scheme**
```
debian-minimal-2026-03-31  # YYYY-MM-DD date-based
```

Rationale:
- Easy to understand
- Correlates with update date
- Simple sorting/ordering
- No complex semver needed

### C. Distribution & Download

#### Current Plan (Phase 3-4)

**Primary Method**: HTTP direct download
```
https://releases.devpocket.dev/debian/minimal/debian-minimal-2026-03-31.tar.gz
```

**Checksum Verification**:
```json
{
  "version": "debian-minimal-2026-03-31",
  "filename": "debian-minimal-2026-03-31.tar.gz",
  "size_bytes": 157286400,
  "sha256": "abc123...",
  "released_at": "2026-03-31T00:00:00Z",
  "support_until": "2026-06-30"
}
```

**Fallback Options** (Phase 5):
- Mirror support (CDN, multiple servers)
- Resumable download (HTTP Range header)
- Torrent distribution (optional, for decentralization)

### D. Update Strategy

#### Versions Coexist Unsupported
- User starts with v1 (Dec 2025)
- Security updates available: v2 (Jan 2026), v3 (Feb 2026), v4 (Mar 2026)
- Install/reinstall over existing environment
- No in-place patching (tar extraction handles updates)
- No rollback (except manual restore from backups)

#### Update Flow (Future Phase)
1. Check available versions via MANIFEST.json
2. Compare installed vs latest
3. Show update available notification
4. User initiates download/extraction
5. Backup existing environment (optional)
6. Extract new version over old
7. Verify health post-update
8. Show success/failure

### E. Package Version Management

#### Immutable Release Policy
- Once `debian-minimal-2026-03-31.tar.gz` is released, **never modify it**
- Security fixes come in next version: `debian-minimal-2026-04-30.tar.gz`
- Users choose when to upgrade

#### Rationale
- Reproducibility (same rootfs = same versions)
- Auditability (can trace issues to specific release)
- Simplifies QA (test specific versions)

### F. Minimal Package List (MVP)

**Required for functionality:**
- `bash` - Shell
- `ca-certificates` - SSL/TLS trust store
- `apt` - Package manager
- `curl`, `wget` - Downloaders
- `git` - Version control
- `base-files`, `base-passwd` - System foundation
- `perl` - Sometimes needed by install scripts

**Commonly useful (include):**
- `nano` - Text editor (simpler than vim)
- `tar`, `gzip`, `bzip2` - Archive tools
- `grep`, `sed`, `awk` - Text processing
- `find`, `locate` - File search
- `less`, `more` - Pagers
- `man` - Manual pages
- `sudo` - Privilege escalation
- `openssh-client` - SSH access

**Deliberately excluded (user installs later)**
- Python, Node, Ruby, Java, Go, Rust (dev tools)
- Compilers, build tools, development headers
- Desktop environments, X11
- Large service daemons
- Language-specific runtimes

**Target Size Uncompressed**: 400-600MB

### G. Security Considerations

#### Signing & Verification
1. **Build System Security**
   - Isolated build environment
   - GPG sign the rootfs archive
   - Publish GPG key separately

2. **Download Verification**
   ```kotlin
   // Kotlin: Verify signature before extraction
   val manifest = downloadManifest()
   val signature = downloadSignature()
   GPGVerify.verify(rootfsFile, signature, manifest.publicKey)
   ```

3. **Checksum Chain**
   - MANIFEST.json provides SHA256
   - Installer verifies before extraction
   - Manifest itself signed with GPG

#### Runtime Security (Phase 12)
- Keep Debian isolated to app-private storage
- No direct access from Android to Debian home
- Explicit import/export for shared files
- No symlinks out of Debian environment

### H. Storage Footprint Analysis

| Component | Compressed | Uncompressed |
|-----------|-----------|--------------|
| Debian rootfs | 150 MB | 600 MB |
| Your projects | Grows | Grows |
| Package cache (apt) | - | Grows |
| Logs, temp | - | Grows |
| Bootstrap runtime | 50 MB | 80 MB |
| **Total (fresh)** | **200 MB** | **680 MB** |

**Recommendation**: Show pre-install warning if <1GB free space available

---

## Locked Decisions (Phase 4)

✅ **Debian-based rootfs** (not Alpine, not custom distro)
✅ **Minimal base** (~600MB uncompressed, ~150MB compressed)
✅ **Date-based versioning** (YYYY-MM-DD format)
✅ **Immutable releases** (never modify, upgrade via new version)
✅ **HTTP direct download** (primary distribution method)
✅ **SHA256 verification** mandatory post-download
✅ **GPG signatures** on manifests and releases

---

## Next Steps

### Phase 4A (Now): Strategy Finalization
- [ ] Decide: official Debian vs custom image
- [ ] Decide: release server infrastructure
- [ ] Document: exact debootstrap command
- [ ] Create: CI/CD pipeline for rootfs builds
- [ ] Establish: release schedule (monthly? on-demand?)
- [ ] Create: security policy and rollback procedure

### Phase 4B (Future): Custom Build
- [ ] Build custom minimal Debian image
- [ ] Test on Android device
- [ ] Automate builds with GitHub Actions
- [ ] Release first version: `debian-minimal-2026-04-30`
- [ ] Document: update process for users

### Phase 5 Integration
- BootstrapInstallerService will fetch URL from MANIFEST.json
- MANIFEST.json hosted on releases.devpocket.dev
- Checksum validation required before extraction

---

## Recommendation for Immediate Action

**Use Official Debian for Phase 1 MVP:**
1. Download Debian minimal base image (multiarch)
2. Extract and trim to ~600MB
3. Host on simple HTTP server for testing
4. Document checksum in code
5. Test end-to-end install flow

**Transition to Custom Builds in Phase 4B:**
1. Once MVP proved stable
2. Invest in custom build infrastructure
3. Automate all with CI/CD
4. Release on public schedule

This gives confidence early without huge upfront investment, then proper automation later.
