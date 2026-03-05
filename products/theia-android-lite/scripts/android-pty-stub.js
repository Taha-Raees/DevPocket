// Android PTY stub: load Termux pty.node from native/ dir at runtime.
const path = require("path");
module.exports = __non_webpack_require__(path.join(__dirname, "native", "pty.node"));