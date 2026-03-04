# Theia Android Lite — Android App

Native Kotlin Android app that embeds and runs Theia IDE on-device.

## What This Contains

- Kotlin sources for the Android shell (WebView, backend service, asset extraction)
- Gradle build configuration (standalone, no Capacitor)
- Layout and resource files

## Building

```bash
# From repo root:
./scripts/build-android.sh debug
```

See [docs/ANDROID_BUILD.md](../docs/ANDROID_BUILD.md) for full instructions.

## Runtime Assets

The `app/src/main/assets/runtime/` directory is populated at build time by:
```bash
cd products/theia-android-lite && npm run prepare:android-assets
```

This directory is **not** checked into git — it contains the built Theia artifacts and Node.js binary.
