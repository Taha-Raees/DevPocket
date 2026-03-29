# DevPocket

**DevPocket** is a powerful, self-contained mobile Integrated Development Environment (IDE) built for Android.

Leveraging the robust architecture of Eclipse Theia as its backend engine, DevPocket brings a fully-featured desktop-grade development experience directly to your mobile device or tablet.

## Features

- **Full Native Terminal**: Run a real, native `bash` environment with PTY support right on your Android device. 
- **Rich Code Editor**: Syntax highlighting, code completion, and advanced editing features powered by Monaco.
- **Extensions**: Support for standard VS Code extensions.
- **File Management**: Direct access to your Android device's storage (e.g., `/sdcard/Download`) from within the IDE.
- **Self-Contained**: No external applications like Termux are required. Everything is bundled into a single APK.

## Architecture

DevPocket runs entirely locally on your Android device:
- The **Frontend** runs in a high-performance webview.
- The **Backend** is a Node.js server running natively on Android ARM64, executing within the app's sandbox.

*(Note: The core backend framework is forked from Eclipse Theia, customized heavily for native Android execution.)*

## Run Locally
```bash
kill -9 $(lsof -t -i:3100) || true; npm run dev
```
https://vscode.dev/tunnel/taha/home/muhammad-taha/Downloads/DevPocket/DevPocket App
