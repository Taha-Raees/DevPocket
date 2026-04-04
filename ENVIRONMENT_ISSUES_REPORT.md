# DevPocket Environment Issues Report

**Date:** 2026-04-01  
**Tested On:** Android device (aarch64, Linux 5.4.274)  
**App Version:** Based on Eclipse Theia 1.68.0

---

## Summary

Multiple critical issues prevent normal development workflows in the DevPocket terminal environment. The main categories are:

1. **Missing essential binaries** in the runtime bundle
2. **Broken git-remote-https** helper (overwritten with wrapper script)
3. **Missing `env` binary** in runtime
4. **Debian shell PATH not including runtime binaries**
5. **SSL/curl linking issues**

---

## Issue 1: git-remote-https Binary Corrupted

### Severity: 🔴 CRITICAL
### Impact: Git push/pull/clone over HTTPS completely broken

### Problem
The file at `/data/data/com.theia.mobile/files/runtime/bin/libexec/git-core/git-remote-https` was found to be a **shell script wrapper** (809 bytes) instead of the actual **git multi-call binary** (3,421,768 bytes).

### Evidence
```bash
$ ls -la /data/data/com.theia.mobile/files/runtime/bin/libexec/git-core/git-remote-https
-rwxr-xr-x 1 10431 10431 809 Apr  1 12:03 git-remote-https

$ head /data/data/com.theia.mobile/files/runtime/bin/libexec/git-core/git-remote-https
#!/system/bin/sh
# Minimal wrapper for git-remote-https to avoid "Argument list too long" error
exec env -i \
  PATH="/data/data/com.theia.mobile/files/runtime/bin:..." \
  ...
```

### Error Messages
```
git: 'remote-https' is not a git command. See 'git --help'.
fatal: remote helper 'https' aborted session
```
and
```
fatal: cannot handle remote-https as a builtin
fatal: remote helper 'https' aborted session
```

### Root Cause
The `build-runtime-assets.mjs` script or a previous fix attempt accidentally overwrote the actual git binary with a wrapper script. The git-remote-https must be either:
- A **hard link** to the main `git` binary, OR
- A **copy** of the main `git` binary

### Fix Required
In `build-runtime-assets.mjs`, after copying the git binary, ensure git-remote-https is properly created:

```javascript
// In build-runtime-assets.mjs, after copying git binary:
const gitCoreDir = path.resolve(binOut, 'libexec', 'git-core');
mkdirSync(gitCoreDir, { recursive: true });

// Copy git binary to git-core directory
cpSync(path.resolve(termuxBinDir, 'git'), path.resolve(gitCoreDir, 'git'));

// Create git-remote-https as a copy of git (not a wrapper script!)
cpSync(path.resolve(termuxBinDir, 'git'), path.resolve(gitCoreDir, 'git-remote-https'));
chmodSync(path.resolve(gitCoreDir, 'git-remote-https'), 0o755);
```

---

## Issue 2: Missing `env` Binary in Runtime

### Severity: 🔴 CRITICAL
### Impact: Cannot clear environment for git-remote-https, causes "Argument list too long" errors

### Problem
The `env` binary is not included in the Termux runtime bundle at `/data/data/com.theia.mobile/files/runtime/bin/env`.

### Evidence
```bash
$ which env
# (no output)

$ ls /data/data/com.theia.mobile/files/runtime/bin/env
ls: cannot access '...': No such file or directory

$ ls /bin/env
/bin/env  # Only available in Debian proot, not in Android runtime
```

### Error Messages
```
/data/data/com.theia.mobile/files/runtime/bin/libexec/git-core/git-remote-https[12]: env: inaccessible or not found
```

### Root Cause
The `essentialBinaries` list in `build-runtime-assets.mjs` does not include `env`.

### Fix Required
Add `env` to the essential binaries list in `build-runtime-assets.mjs`:

```javascript
const essentialBinaries = [
    'node', 'bash', 'bash.real',
    'npm', 'npx',
    'git', 'git-receive-pack', 'git-upload-pack', 'git-upload-archive',
    'git-remote-http', 'git-remote-https',  // Add these explicitly
    'env',  // <-- ADD THIS
    'python3', 'python',
    'curl', 'wget',
    // ... rest of binaries
];
```

---

## Issue 3: Missing Essential Git Helper Binaries

### Severity: 🔴 CRITICAL
### Impact: Git operations over HTTPS fail

### Problem
The following git helper binaries are not being bundled in the runtime:
- `git-remote-http`
- `git-remote-https`
- `git-remote-ftp`
- `git-remote-ftps`

### Evidence
```bash
$ ls /data/data/com.theia.mobile/files/runtime/bin/libexec/git-core/ | grep remote
git-ls-remote
git-remote
git-remote-ext
git-remote-fd
git-remote-ftp
git-remote-ftps
git-remote-http
git-remote-https
# These exist in the original Termux install but may not be bundled correctly
```

### Fix Required
Ensure ALL git helper binaries are copied from Termux to the runtime:

```javascript
// Copy all git-remote-* helpers
const gitRemoteHelpers = ['git-remote-http', 'git-remote-https', 'git-remote-ftp', 'git-remote-ftps'];
for (const helper of gitRemoteHelpers) {
    const src = path.resolve(termuxBinDir, helper);
    if (existsSync(src)) {
        // Copy as actual binary, not wrapper
        cpSync(src, path.resolve(gitCoreDir, helper));
        chmodSync(path.resolve(gitCoreDir, helper), 0o755);
    }
}
```

---

## Issue 4: Debian Shell Wrapper Not Preserving Environment

### Severity: 🟡 HIGH
### Impact: Commands work in Android fallback shell but fail inside Debian proot

### Problem
The `devpocket-shell` wrapper script launches proot but the environment variables set before proot are not properly passed through to the Debian environment.

### Evidence
```bash
# In Android shell (fallback):
$ which git
/data/data/com.theia.mobile/files/runtime/bin/git  # ✅ Works

# Inside Debian proot:
$ which git
/bin/bash: line 1: git: command not found  # ❌ Fails
```

### Root Cause
The proot environment has its own PATH (`/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin`) which doesn't include the runtime binaries path.

### Fix Required
The devpocket-shell wrapper should use proot's `-b` bind mount AND set environment variables inside proot:

```bash
# In devpocket-shell wrapper:
"$PROOT_BIN" -0 \
  -r "${DEVPOCKET_DEBIAN_ROOT}" \
  -b "${DEVPOCKET_RUNTIME_BIN}:/opt/devpocket/bin" \
  -b "${DEVPOCKET_RUNTIME_LIB}:/opt/devpocket/lib" \
  -w "/home/devpocket" \
  /bin/env \
    PATH="/opt/devpocket/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin" \
    LD_LIBRARY_PATH="/opt/devpocket/lib" \
    HOME="/home/devpocket" \
    TERM="xterm-256color" \
    /bin/bash "$@"
```

---

## Issue 5: SSL Certificate Configuration

### Severity: 🟡 MEDIUM
### Impact: HTTPS operations (git clone, curl, npm install) may fail without proper CA certs

### Current Status
CA certificates ARE bundled at `/data/data/com.theia.mobile/files/runtime/etc/ca-certificates/cacert.pem` (226KB from curl.se).

### Potential Issue
The SSL_CERT_FILE and GIT_SSL_CAINFO environment variables need to be set in:
1. TheiaBackendService.kt (backend environment)
2. Terminal sessions (shell-process.ts)
3. Inside Debian proot (devpocket-shell wrapper)

### Verification Needed
After fixing the above issues, verify:
```bash
export SSL_CERT_FILE="/data/data/com.theia.mobile/files/runtime/etc/ca-certificates/cacert.pem"
export GIT_SSL_CAINFO="$SSL_CERT_FILE"
git clone https://github.com/example/repo.git  # Should work
curl -I https://github.com  # Should work
```

---

## Issue 6: Missing Development Tools

### Severity: 🟡 MEDIUM
### Impact: Cannot build native modules, compile code, etc.

### Missing Binaries
The following development tools are NOT included in the runtime but may be needed:
- `make` - Build automation
- `cmake` - Cross-platform build system
- `gcc`/`clang` - C/C++ compilers
- `pkg-config` - Library configuration
- `python3` - Python runtime (needed by node-gyp)

### Fix Required
Add to essential binaries list if needed:
```javascript
const essentialBinaries = [
    // ... existing binaries
    'make', 'cmake',
    'pkg-config',
    'python3', 'python',
    // Note: gcc/clang are very large, consider using pre-built binaries instead
];
```

---

## Issue 7: LD_LIBRARY_PATH Not Propagated

### Severity: 🟡 MEDIUM
### Impact: Shared libraries (.so files) not found, causing "library not found" errors

### Problem
When running binaries from the runtime, the shared libraries in `/data/data/com.theia.mobile/files/runtime/lib/` are not found because LD_LIBRARY_PATH is not set.

### Evidence
```bash
$ /data/data/com.theia.mobile/files/runtime/bin/git --version
CANNOT LINK EXECUTABLE "/data/data/com.theia.mobile/files/runtime/bin/git": library "libpcre2-8.so" not found
```

### Fix Required
Ensure LD_LIBRARY_PATH is set in ALL execution contexts:
1. TheiaBackendService.kt: `env["LD_LIBRARY_PATH"] = runtimeLib`
2. Terminal sessions: Add to shell-process.ts
3. devpocket-shell wrapper: `export LD_LIBRARY_PATH="${DEVPOCKET_RUNTIME_LIB}:$LD_LIBRARY_PATH"`

---

## Recommended Fix Order

1. **First**: Fix git-remote-https binary corruption (Issue 1) - This is blocking git push
2. **Second**: Add `env` binary to runtime (Issue 2) - Required for environment clearing
3. **Third**: Ensure all git-remote-* helpers are bundled (Issue 3)
4. **Fourth**: Fix devpocket-shell PATH propagation (Issue 4)
5. **Fifth**: Verify SSL certificate configuration (Issue 5)
6. **Sixth**: Add missing development tools (Issue 6)
7. **Seventh**: Fix LD_LIBRARY_PATH propagation (Issue 7)

---

## Build Commands

After making fixes on laptop:

```bash
# Navigate to project root
cd "/home/muhammad-taha/Downloads/DevPocket/DevPocket App"

# 1. Install dependencies
npm install

# 2. Compile TypeScript
npm run build

# 3. Bundle frontend
cd products/theia-android-lite
npm run bundle

# 4. Assemble runtime assets (THIS IS WHERE THE FIXES APPLY)
node scripts/build-runtime-assets.mjs

# 5. Verify runtime assets
ls -la ../../android-app/android/app/src/main/assets/runtime/bin/
ls -la ../../android-app/android/app/src/main/assets/runtime/bin/libexec/git-core/

# 6. Build APK
cd ../../android-app/android
./gradlew clean assembleDebug

# 7. Install on device
/home/muhammad-taha/Android/Sdk/platform-tools/adb uninstall com.theia.mobile
/home/muhammad-taha/Android/Sdk/platform-tools/adb install app/build/outputs/apk/debug/app-debug.apk
```

---

## Verification Checklist

After rebuilding and installing:

```bash
# Open terminal in DevPocket app and run:

# 1. Check git binary
which git
git --version

# 2. Check git-remote-https
ls -la $(git --exec-path)/git-remote-https
file $(git --exec-path)/git-remote-https  # Should be ELF binary, NOT script

# 3. Check env binary
which env
env --version

# 4. Test git clone
git clone https://github.com/octocat/Hello-World.git

# 5. Test git push (with authentication)
cd /home/devpocket/code
git remote -v
git push -u origin master

# 6. Check npm
which npm
npm --version

# 7. Check python3
which python3
python3 --version

# 8. Check curl
which curl
curl --version

# 9. Check wget
which wget
wget --version
```

---

## Files Modified in This Session

| File | Changes |
|------|---------|
| `products/theia-android-lite/scripts/build-runtime-assets.mjs` | Added expanded essential binaries list, created devpocket-shell wrapper with proot bindings |
| `android-app/android/app/src/main/java/com/theia/mobile/TheiaBackendService.kt` | Added DEVPOCKET_RUNTIME_BIN and DEVPOCKET_RUNTIME_LIB environment variables |
| `packages/terminal/src/node/shell-process.ts` | Enhanced terminal environment to propagate runtime PATH and LD_LIBRARY_PATH |

---

## Notes for Future Development

1. **Never overwrite git-remote-https with a script** - It must be the actual git binary
2. **Always test on device** - Desktop emulation doesn't catch Android-specific issues
3. **Keep runtime binaries minimal** - Only include what's actually needed
4. **Test git operations early** - Git is critical for development workflows
5. **Verify SSL setup** - HTTPS operations need proper CA certificates
