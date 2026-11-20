# Phone To MCP

Kotlin and Jetpack Compose rewrite of the Android MCP device server. It runs a
local MCP endpoint at `http://127.0.0.1:9009/mcp` and keeps device data on the
phone unless a user explicitly configures an external tunnel.

## Status

The project is being rebuilt around the retained capabilities in
[`PHONE_TO_MCP_CHANGE_PLAN.md`](PHONE_TO_MCP_CHANGE_PLAN.md): MCP, bounded
filesystem operations, JSON editing, Android/Termux terminal backends,
notifications, native Android text-to-speech, URL downloads, diagnostics, and
optional Cloudflare Tunnel support. Removed legacy tools are intentionally not
re-registered.

## Requirements

- Android 8.0 (API 26) or newer
- JDK 17 for local builds
- Android SDK 36

The app is intended for private sideloading, not Google Play distribution.
Shared-storage tools and external runtime logs require the appropriate Android
storage access. Terminal execution is disabled until explicitly enabled.

## Build

```sh
./gradlew assembleDebug
```

GitHub Actions builds a non-debuggable, debug-signed APK with code minification
and resource shrinking enabled, then publishes it as the `phone-to-mcp-debug`
workflow artifact. The bundled arm64 Cloudflare Tunnel
executable is about 28 MiB and cannot be reduced by R8/resource shrinking.
After pushing a commit, the local watcher waits for the build and installs the
APK at `/root/apk/mcp-debug.apk`:

```sh
./scripts/watch-build.sh
```

Pass a run ID to watch a specific build: `./scripts/watch-build.sh <run-id>`.

## Security

Bearer authentication is enabled by default. Keep write, terminal, and external
communication tools disabled unless needed. The MCP server binds only to
loopback; a public tunnel expands access and should only be enabled deliberately.
Application logs rotate under `/sdcard/Android/media/rh.mcp.phone/` and must not
contain bearer tokens or tool payloads.

## License

MIT; see [LICENSE](LICENSE).
