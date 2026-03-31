# Phase 7: Terminal Launch & Shell Integration - PLANNING

**Date**: 2026-03-31
**Status**: PLANNING (implementation deferred pending Phase 8 backend decision)

## Goal
Ensure terminal shells launch and execute **inside the Debian environment**, not in the Android host. This provides users with a standard Linux terminal experience.

## Current Behavior (Pre-Phase 7)
**Terminal Location**: `/packages/terminal/src/node/shell-process.ts`
- Resolves shell executable from host Android environment
- Launches with Android environment variables
- Working directory in IDE workspace (external storage or app-private)
- Shell sees Android environment, not Debian environment

## Desired Behavior (Post-Phase 7)
**Terminal Location**: Inside Debian chroot/namespace
- Shell executable from Debian: `/bin/bash`
- Working directory: `/home/<username>/code` (inside Debian)
- Environment variables from Debian initialization
- User context: Debian user account (UID 1000+, not Android UID)
- Sees Debian filesystem, not Android filesystem

## Implementation Options

### Option A: Simple Shell Wrapping (Lightweight, MVP)
**Approach**: Wrap Debian shell with PATH manipulation

```bash
#!/bin/bash
# bootstrap-shell-wrapper.sh
export SHELL=/home/devpocket/bin/bash
export PATH=/bin:/usr/bin:/usr/local/bin:$PATH
export HOME=/home/devpocket
export TERM=xterm-256color

# Set working directory
cd /home/devpocket/code

# Source user shell init
[ -f /home/devpocket/.bashrc ] && source /home/devpocket/.bashrc

# Execute requested shell
exec /bin/bash "$@"
```

**Pros:**
- Simple, no special kernel features needed
- Works on Android (no namespace/mount support needed)
- Easy to test and debug
- Minimal performance overhead

**Cons:**
- Not true filesystem isolation
- User can access parent filesystem with relative paths
- Less secure (but still app-private storage)

**Recommendation**: Use for MVP (Phase 7A)

### Option B: chroot into Debian (Better Isolation)
**Approach**: Use `chroot()` syscall to change root

```javascript
// pseudo-code for shell-process.ts
const debianRoot = '/data/data/com.theia.mobile/files/linux/debian';
const username = 'devpocket';

// Use ProcessBuilder with special handling
const pb = ProcessBuilder(['chroot', debianRoot, '/bin/bash', '-i', '-c', 'cd ~/code && $SHELL']);
const env = pb.environment();
env['HOME'] = `/home/${username}`;
env['USER'] = username;
env['SHELL'] = '/bin/bash';
```

**Pros:**
- True filesystem isolation at `/` level
- User cannot escape to parent filesystem
- More secure
- Standard Linux tool

**Cons:**
- Requires chroot binary in Debian
- Needs careful setup of /dev, /proc inside chroot
- More complex error handling
- Android may have restrictions

**Recommendation**: Evaluate in Phase 7B after MVP testing

### Option C: Linux Namespaces (Container-Like, Advanced)
**Approach**: Use mount/pid/uts namespaces

```bash
# Would use unshare or similar
unshare --mount --uts --ipc --pid \
  chroot /data/data/com.theia.mobile/files/linux/debian \
  /bin/bash -i
```

**Pros:**
- Full containerization similar to Docker
- Complete isolation
- Modern Linux container approach

**Cons:**
- Complex setup
- Android Linux kernel may not support all namespaces
- Significant performance overhead
- Difficult to debug

**Recommendation**: Phase 8+ (after stabilizing simpler approach)

## Phase 7A Implementation: Shell Wrapping + Environment

### 1. Files to Modify/Create

#### A. Create `shell-wrapper.sh` (in bootstrap runtime)
Location: `android-app/.../assets/runtime/bin/devpocket-shell`

```bash
#!/bin/bash
# DevPocket Shell Wrapper
# Provides Debian-aware terminal execution

set -e

# Detect Debian root location
DEVPOCKET_ROOT="${DEVPOCKET_ROOT:-/data/data/com.theia.mobile/files/linux}"
DEBIAN_ROOT="${DEVPOCKET_ROOT}/debian"
DEVPOCKET_USER="${DEVPOCKET_USER:-devpocket}"

# Verify Debian exists
if [ ! -d "${DEBIAN_ROOT}" ]; then
    echo "Error: Debian environment not found at ${DEBIAN_ROOT}"
    echo "Please run onboarding to install Debian."
    exit 1
fi

# Setup environment for Debian
export SHELL="${DEBIAN_ROOT}/bin/bash"
export BASH_ENV="${DEBIAN_ROOT}/home/${DEVPOCKET_USER}/.bashrc"
export PATH="${DEBIAN_ROOT}/usr/local/sbin:${DEBIAN_ROOT}/usr/local/bin:${DEBIAN_ROOT}/usr/sbin:${DEBIAN_ROOT}/usr/bin:${DEBIAN_ROOT}/bin:/sbin:${PATH}"
export LD_LIBRARY_PATH="${DEBIAN_ROOT}/usr/local/lib:${DEBIAN_ROOT}/usr/lib:${DEBIAN_ROOT}/lib:/lib64"

# User configuration
export HOME="${DEBIAN_ROOT}/home/${DEVPOCKET_USER}"
export USER="${DEVPOCKET_USER}"
export LOGNAME="${DEVPOCKET_USER}"

# Terminal capabilities
export TERM=xterm-256color
export COLORTERM=truecolor

# Set working directory inside Debian
cd "${HOME}/code" || cd "${HOME}"

# Execute Debian shell with initialization
exec "${DEBIAN_ROOT}/bin/bash" -i -c "[ -f ~/.bashrc ] && source ~/.bashrc; $SHELL"
```

#### B. Modify `shell-process.ts`
Location: `packages/process/src/node/terminal-process.ts`

```typescript
// Pseudo-code for changes needed
private getShellExecutablePath(): string {
    // Check if Debian environment exists
    const debianRoot = '/data/data/com.theia.mobile/files/linux/debian';
    const debianShell = path.join(debianRoot, 'bin/bash');
    
    if (fs.existsSync(debianShell)) {
        // Use Debian shell
        return debianShell;
    }
    
    // Fallback to wrappershell if exists
    const wrapperShell = '/data/data/com.theia.mobile/files/runtime/bin/devpocket-shell';
    if (fs.existsSync(wrapperShell)) {
        return wrapperShell;
    }
    
    // Last resort: legacy shell
    return this.getHostShellPath();
}

private createProcessBuilder(): ProcessBuilder {
    const shellPath = this.getShellExecutablePath();
    
    // Set Debian-aware environment
    const env = ProcessBuilder().environment();
    
    if (isDorectory('/data/data/com.theia.mobile/files/linux/debian')) {
        env['DEVPOCKET_ROOT'] = '/data/data/com.theia.mobile/files/linux';
        env['DEVPOCKET_USER'] = 'devpocket'; // or from state manager
        env['DEVPOCKET_DEBIAN_AWARE'] = '1';
    }
    
    return pb;
}
```

#### C. Modify `shell-terminal-server.ts`
Location: `packages/terminal/src/node/shell-terminal-server.ts`

Add Debian environment initialization:

```typescript
protected async create(options: IShellTerminalServerOptions): Promise<ITerminalServer> {
    // ... existing code ...
    
    // Add Debian environment detection
    const debianRoot = process.env['DEVPOCKET_DEBIAN_ROOT'] || 
                       '/data/data/com.theia.mobile/files/linux/debian';
    const isDebianAvailable = fs.existsSync(debianRoot);
    
    if (isDebianAvailable) {
        // Set Debian-aware environment variables
        env['SHELL'] = path.join(debianRoot, 'bin/bash');
        env['HOME'] = path.join(debianRoot, 'home', env['DEVPOCKET_USER'] || 'devpocket');
        
        // Update PATH to prefer Debian executables
        const debianBin = path.join(debianRoot, 'bin');
        env['PATH'] = `${debianBin}:${env['PATH']}`;
    }
    
    // ... rest of implementation ...
}
```

### 2. Environment Variables for Debian Awareness

**Set by TheiaBackendService.kt (already done in Phase 5):**
```
DEVPOCKET_DEBIAN_ROOT=/data/data/com.theia.mobile/files/linux/debian
DEVPOCKET_USER=devpocket (or custom)
DEVPOCKET_WORKSPACE=/data/data/.../linux/debian/home/devpocket/code
```

**Set by shell-wrapper.sh (new):**
```
HOME=/data/data/.../linux/debian/home/<user>
SHELL=/data/data/.../linux/debian/bin/bash
LD_LIBRARY_PATH=<debian paths>
TERM=xterm-256color
```

### 3. Shell Initialization Order

**User opens terminal in IDE:**
1. TerminalWidgetImpl requests new shell
2. ShellTerminalServer.create() -> checks DEVPOCKET_DEBIAN_ROOT
3. Detects Debian available
4. Resolves shell to Debian bash or wrapper script
5. Sets DEVPOCKET_* env vars
6. ProcessBuilder executes shell with Debian env
7. Shell loads ~/.bashrc (Debian version)
8. User gets Debian prompt: `devpocket@host:~/code$`

### 4. Data Flow Diagram

```
IDE Terminal Widget
  ↓ requests new PTY
Theia Terminal Server
  ├─ Checks DEVPOCKET_DEBIAN_ROOT (from env)
  ├─ Sets DEVPOCKET_DEBIAN_AWARE=1
  └─ Creates process with Debian shell path
      ↓
Node-pty (creates real PTY)
  ↓ executes
Shell Wrapper (devpocket-shell) OR Debian Bash
  ├─ Sets HOME, SHELL, PATH to Debian locations
  ├─ Changes CWD to ~/code
  ├─ Sources .bashrc
  └─ Becomes interactive shell
      ↓
User typing in terminal
  ↓ input to PTY
Bash (inside Debian context environment)
  ├─ Runs commands with Debian PATH
  ├─ Sees Debian filesystem
  ├─ User owns files (not Android UID)
  └─ Full Unix-like experience
```

## Phase 7 Limitations & Future

### MVP Limitations
- Shell not true filesystem isolated (can use `../` to escape)
- No changes to Android system behavior
- Still depends on host OS for actual syscalls
- Limited container features

### Phase 8 Integration Check
- Phase 8 will decide if backend should run inside or outside Debian
- If inside: Terminal and backend both in same Debian environment
- If outside: Terminal in Debian, backend in host (current plan)
- Phase 7 works either way with proper env vars

### Security Notes (Phase 12)
- Debian workspace in app-private storage prevents user access
- Root inside Debian ≠ Android root
- File ownership (UID 1000) inside Debian ≠ Android UID (10123)
- Isolation is container-level, not kernel-level (no true namespaces)

## Testing Plan (Phase 14)

**Acceptance Criteria:**
1. [ ] Terminal opens inside IDE
2. [ ] Prompt shows username inside Debian (e.g., `devpocket@devpocket:~/code$`)
3. [ ] `echo $HOME` shows Debian path
4. [ ] `which bash` shows Debian bash path
5. [ ] `apt update && apt install git` works
6. [ ] `git clone https://...` succeeds with CA certs
7. [ ] Files created appear in correct Debian directory
8. [ ] Multiple terminal tabs use same user context
9. [ ] Terminal restart clears history but maintains env

## Decision Gate (Phase 8)

Phase 7 implementation is deferred pending Phase 8 decision:
- **If Phase 8 says: Backend outside Debian** → Proceed with Phase 7A (shell wrapping)
- **If Phase 8 says: Backend inside Debian** → Extend Phase 7 with chroot setup

**Recommendation**: Implement Phase 7A now (shell wrapper), then decide Phase 8 based on stability.
