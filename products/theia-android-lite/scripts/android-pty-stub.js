// Android/local PTY stub: choose the correct pty.node at runtime.
const path = require("path");
const fs = require("fs");

function loadPty() {
    const isAndroidLite = process.env.THEIA_ANDROID_LITE === "1" || process.platform === "android";
    console.log("[android-pty-stub] THEIA_ANDROID_LITE:", process.env.THEIA_ANDROID_LITE, ", process.platform:", process.platform, ", isAndroidLite:", isAndroidLite);
    console.log("[android-pty-stub] __dirname:", __dirname);
    
    const candidates = isAndroidLite
        ? [path.join(__dirname, "native", "pty.node")]
        : [
            path.resolve(__dirname, "..", "..", "..", "..", "node_modules", "node-pty", "build", "Release", "pty.node"),
            path.resolve(__dirname, "..", "..", "..", "..", "node_modules", "node-pty", "build", "Debug", "pty.node")
        ];
    
    console.log("[android-pty-stub] Candidate paths:", candidates);
    
    let lastError;
    for (const candidate of candidates) {
        console.log("[android-pty-stub] Trying:", candidate, ", exists:", fs.existsSync(candidate));
        try {
            const result = __non_webpack_require__(candidate);
            console.log("[android-pty-stub] Successfully loaded:", candidate);
            return result;
        } catch (error) {
            console.log("[android-pty-stub] Failed to load", candidate, ":", error.message, error.code ? "code:" + error.code : "");
            lastError = error;
        }
    }
    console.log("[android-pty-stub] Unable to resolve pty.node, throwing last error");
    throw lastError || new Error("Unable to resolve pty.node runtime binding");
}

module.exports = loadPty();