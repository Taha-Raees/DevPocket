#!/usr/bin/env node

/**
 * DevPocket Bootstrap Installer (JavaScript/Node.js)
 *
 * Handles:
 * - Downloading Debian rootfs with progress reporting
 * - SHA256 verification
 * - Tar.gz extraction
 * - User account creation
 * - Manifest generation
 *
 * Usage: node installer.mjs --base-dir /path/to/linux/dir --username devpocket [--progress-callback port:3300]
 */

import fs from 'fs';
import path from 'path';
import crypto from 'crypto';
import { spawn } from 'child_process';
import http from 'http';
import https from 'https';
import { fileURLToPath } from 'url';

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);

// Debian rootfs metadata
const ROOTFS_VERSION = 'debian-userland-2026-03-31';
const ROOTFS_FILENAME = 'arm64-rootfs.tar.gz';
const ROOTFS_SIZE_MB = 150;
const ROOTFS_URL = `https://github.com/CypherpunkArmory/UserLAnd-Assets-Debian/releases/download/v0.0.10/${ROOTFS_FILENAME}`;
const ROOTFS_SHA256_URL = `${ROOTFS_URL}.sha256`;
const ROOTFS_SHA256 = '47eb42fd93d27b4dd0520a1228c44431aa0cdff9a93ba624039147f984b79028';

const getHttpClient = targetUrl => new URL(targetUrl).protocol === 'https:' ? https : http;

class BootstrapInstaller {
    constructor(baseDir, username, progressCallback) {
        this.baseDir = baseDir;
        this.username = username || 'devpocket';
        this.progressCallback = progressCallback;

        this.devpocketBase = path.join(baseDir, 'linux');
        this.bootstrapDir = path.join(this.devpocketBase, 'bootstrap');
        this.debianDir = path.join(this.devpocketBase, 'debian');
        this.tempDir = path.join(this.devpocketBase, 'temp');
        this.manifestFile = path.join(this.devpocketBase, 'INSTALL_MANIFEST.json');
    }

    async install() {
        try {
            console.log('[Installer] Starting bootstrap installation');
            this.ensureDirectories();

            // Phase 1: Download
            this.publishProgress('downloading', 0, ROOTFS_SIZE_MB * 1024 * 1024);
            await this.downloadRootfs();

            // Phase 2: Verify
            this.publishProgress('verifying', 0, ROOTFS_SIZE_MB * 1024 * 1024);
            const rootfsFile = path.join(this.tempDir, ROOTFS_FILENAME);
            const expectedChecksum = await this.resolveExpectedChecksum();
            const checksum = await this.calculateSHA256(rootfsFile);
            console.log(`[Installer] Calculated SHA256: ${checksum}`);

            if (!expectedChecksum) {
                throw new Error('Missing expected checksum for rootfs');
            }
            if (checksum !== expectedChecksum) {
                throw new Error(`Checksum mismatch: expected ${expectedChecksum}, got ${checksum}`);
            }

            // Phase 3: Extract
            this.publishProgress('extracting', 0, ROOTFS_SIZE_MB * 1024 * 1024);
            await this.extractTarGz(rootfsFile, this.debianDir);

            // Phase 4: User account
            this.publishProgress('finalizing', 0, 100);
            await this.createUserAccount();

            // Phase 5: Manifest
            await this.writeManifest(checksum);

            console.log('[Installer] Installation completed successfully');
            return true;

        } catch (error) {
            console.error('[Installer] Installation failed:', error.message);
            return false;
        }
    }

    ensureDirectories() {
        [this.devpocketBase, this.debianDir, this.tempDir].forEach(dir => {
            if (!fs.existsSync(dir)) {
                fs.mkdirSync(dir, { recursive: true });
            }
        });
    }

    async downloadRootfs() {
        console.log(`[Installer] Downloading ${ROOTFS_URL}`);

        // Check if file already exists
        const rootfsFile = path.join(this.tempDir, ROOTFS_FILENAME);
        if (fs.existsSync(rootfsFile)) {
            const stats = fs.statSync(rootfsFile);
            const expectedSize = ROOTFS_SIZE_MB * 1024 * 1024;
            if (stats.size > expectedSize * 0.9) {
                console.log('[Installer] Rootfs already downloaded, skipping');
                return;
            }
        }

        return new Promise((resolve, reject) => {
            const file = fs.createWriteStream(rootfsFile);
            let downloadedBytes = 0;

            getHttpClient(ROOTFS_URL).get(ROOTFS_URL, (response) => {
                const contentLength = parseInt(response.headers['content-length'], 10);

                response.on('data', (chunk) => {
                    downloadedBytes += chunk.length;
                    this.publishProgress('downloading', downloadedBytes, contentLength);
                });

                response.pipe(file);

                file.on('finish', () => {
                    file.close();
                    console.log('[Installer] Download complete');
                    resolve();
                });

                file.on('error', (err) => {
                    fs.unlink(rootfsFile, () => { });
                    reject(err);
                });
            }).on('error', reject);
        });
    }

    async resolveExpectedChecksum() {
        if (ROOTFS_SHA256) {
            return ROOTFS_SHA256.trim().toLowerCase();
        }

        return new Promise((resolve, reject) => {
            let body = '';
            getHttpClient(ROOTFS_SHA256_URL).get(ROOTFS_SHA256_URL, response => {
                if (response.statusCode !== 200) {
                    reject(new Error(`Checksum download failed with status ${response.statusCode}`));
                    response.resume();
                    return;
                }

                response.setEncoding('utf8');
                response.on('data', chunk => {
                    body += chunk;
                });
                response.on('end', () => {
                    const checksum = body.trim().split(/\s+/)[0]?.toLowerCase();
                    if (!checksum) {
                        reject(new Error('Checksum file malformed'));
                        return;
                    }
                    resolve(checksum);
                });
            }).on('error', reject);
        });
    }

    async calculateSHA256(filePath) {
        return new Promise((resolve, reject) => {
            const hash = crypto.createHash('sha256');
            const stream = fs.createReadStream(filePath);

            stream.on('data', (chunk) => hash.update(chunk));
            stream.on('end', () => resolve(hash.digest('hex')));
            stream.on('error', reject);
        });
    }

    async extractTarGz(tarGzFile, destDir) {
        console.log(`[Installer] Extracting ${tarGzFile} to ${destDir}`);

        return new Promise((resolve, reject) => {
            const tarArgs = tarGzFile.endsWith('.tar.xz') || tarGzFile.endsWith('.txz')
                ? ['-xJf', tarGzFile, '-C', destDir]
                : ['-xzf', tarGzFile, '-C', destDir];
            const child = spawn('tar', tarArgs, { stdio: 'inherit' });
            child.on('error', error => reject(new Error(`Failed to start tar extraction: ${error.message}`)));
            child.on('close', code => {
                if (code === 0) {
                    console.log('[Installer] Extraction complete');
                    resolve();
                    return;
                }
                reject(new Error(`Extraction failed: tar exited with ${code}`));
            });
        });
    }

    async createUserAccount() {
        console.log(`[Installer] Creating user account: ${this.username}`);

        const debianHome = path.join(this.debianDir, 'home', this.username);
        const bashrcPath = path.join(debianHome, '.bashrc');
        const bashProfilePath = path.join(debianHome, '.bash_profile');

        // Create home directory
        fs.mkdirSync(debianHome, { recursive: true });

        // Create basic .bashrc
        const bashrcContent = `# .bashrc for DevPocket
export TERM=xterm-256color
export COLORTERM=truecolor
export PATH=/usr/local/bin:$PATH

# Basic aliases
alias ls='ls --color=auto'
alias ll='ls -la --color=auto'
alias grep='grep --color=auto'

# Prompt
PS1='\\u@\\h:\\w\\$ '
`;
        fs.writeFileSync(bashrcPath, bashrcContent);
        fs.chmodSync(bashrcPath, 0o644);

        // Create .bash_profile
        const bashProfileContent = `# .bash_profile for DevPocket
if [ -f ~/.bashrc ]; then
    source ~/.bashrc
fi
`;
        fs.writeFileSync(bashProfilePath, bashProfileContent);
        fs.chmodSync(bashProfilePath, 0o644);

        console.log(`[Installer] User account created: ${this.username}`);
    }

    async writeManifest(checksum) {
        const manifest = {
            version: '1.0.0',
            rootfsVersion: ROOTFS_VERSION,
            installedAt: new Date().toISOString(),
            rootfsPath: this.debianDir,
            checksum: checksum,
            extractedSuccessfully: true,
            user: this.username
        };

        fs.writeFileSync(this.manifestFile, JSON.stringify(manifest, null, 2));
        console.log('[Installer] Manifest written');
    }

    publishProgress(phase, current, total) {
        const percent = total > 0 ? Math.floor((current / total) * 100) : 0;
        const message = {
            type: 'progress',
            phase,
            currentBytes: current,
            totalBytes: total,
            percentComplete: percent,
            timestamp: new Date().toISOString()
        };

        if (this.progressCallback) {
            this.progressCallback(message);
        }

        console.log(`[Installer] ${phase} ${percent}% (${current}/${total} bytes)`);
    }

    cleanup() {
        console.log('[Installer] Cleaning up temporary files');
        try {
            fs.rmSync(this.tempDir, { recursive: true, force: true });
        } catch (error) {
            console.error('[Installer] Cleanup failed:', error.message);
        }
    }

    uninstall() {
        console.log('[Installer] Uninstalling Debian environment');
        try {
            [this.debianDir, this.tempDir, this.manifestFile].forEach(item => {
                if (fs.existsSync(item)) {
                    fs.rmSync(item, { recursive: true, force: true });
                }
            });
            console.log('[Installer] Uninstall successful');
            return true;
        } catch (error) {
            console.error('[Installer] Uninstall failed:', error.message);
            return false;
        }
    }
}

// CLI Interface
async function main() {
    const args = new Map();
    process.argv.slice(2).forEach((arg, i, arr) => {
        if (arg.startsWith('--')) {
            const [key, value] = arg.substring(2).split('=');
            args.set(key, value || arr[i + 1]);
        }
    });

    const baseDir = args.get('base-dir') || process.cwd();
    const username = args.get('username') || 'devpocket';
    const command = args.get('command') || 'install';

    const installer = new BootstrapInstaller(baseDir, username, null);

    try {
        switch (command) {
            case 'install':
                const success = await installer.install();
                installer.cleanup();
                process.exit(success ? 0 : 1);
                break;

            case 'uninstall':
                const uninstallSuccess = installer.uninstall();
                process.exit(uninstallSuccess ? 0 : 1);
                break;

            default:
                console.error(`Unknown command: ${command}`);
                process.exit(1);
        }
    } catch (error) {
        console.error('Fatal error:', error.message);
        process.exit(1);
    }
}

main();
