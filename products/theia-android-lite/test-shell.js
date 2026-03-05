const os = require('os');
const cp = require('child_process');

console.log('--- ENV ---');
console.log('SHELL:', process.env.SHELL);
console.log('npm_config_shell:', process.env.npm_config_shell);

console.log('\n--- OS INFO ---');
try {
    console.log('userInfo().shell:', os.userInfo().shell);
} catch (e) {
    console.error('userInfo error:', e.message);
}

console.log('\n--- EXECA MOCK (child_process shell: true) ---');
try {
    const rc = cp.spawnSync('node', ['-v'], { shell: true });
    console.log('spawnSync status:', rc.status);
    console.log('spawnSync stdout:', rc.stdout ? rc.stdout.toString() : 'null');
    console.log('spawnSync stderr:', rc.stderr ? rc.stderr.toString() : 'null');
    if (rc.error) console.error('spawnSync error:', rc.error);
} catch (e) {
    console.error('spawnSync hard error:', e.message);
}
