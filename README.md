# Omni Player

A media player embedded right in your JetBrains IDE. Omni Player talks directly to the
**Windows System Media Transport Controls** (the same system layer behind the media keys and the
Windows volume overlay), so it controls the currently active media session reliably — and shows you
what's playing without leaving your editor.

> ⚠️ **Windows only.** Omni Player relies on the WinRT `Windows.Media.Control` API and works on
> Windows 10 / 11. It has no effect on macOS or Linux.

## Features

- **Now playing at a glance** — album art, track title and artist, shown in a tool window.
- **Live progress** — a progress bar with elapsed / remaining time that advances in real time.
- **Real playback controls** — play / pause, next and previous, targeting the active session
  directly (no global media-key broadcast).
- **Accurate state** — the play/pause button reflects what's actually happening, and the title
  scrolls on hover when it's too long to fit.
- **Theme-aware** — uses built-in IDE icons and colors, so it fits light and dark themes.

## How it works

Omni Player calls the WinRT
[`GlobalSystemMediaTransportControlsSessionManager`](https://learn.microsoft.com/en-us/uwp/api/windows.media.control.globalsystemmediatransportcontrolssessionmanager)
API from the JVM through a small JNA/COM bridge. It reads the current session's metadata, playback
status, timeline and thumbnail, and sends `TryTogglePlayPause` / `TrySkipNext` / `TrySkipPrevious`
to control playback. Any app that integrates with the Windows media controls (Spotify, Apple Music,
browsers, Windows Media Player, etc.) is supported.

## Usage

1. Install the plugin.
2. Open the **Music Player** tool window (left tool window bar).
3. Start playing music in any media app — the track, cover and progress appear automatically, and
   the buttons control that session.

## Building from source

```bash
./gradlew buildPlugin   # produces the plugin ZIP in build/distributions
./gradlew runIde        # launches a sandbox IDE with the plugin for testing
```

**Requirements:** JDK 21, IntelliJ Platform 2025.1+ (build 251+).

## Compatibility

| | |
|---|---|
| OS | Windows 10 / 11 |
| IDE | IntelliJ Platform 2025.1+ (`since-build` 251) |
| Runtime | JDK 21 |

## Publishing

The build is configured to sign and upload the plugin to the
[JetBrains Marketplace](https://plugins.jetbrains.com). Secrets are read from environment variables
(or `gradle.properties`) and are never committed.

```bash
# Marketplace upload token (Marketplace → profile → My Tokens)
export PUBLISH_TOKEN=...

# Optional but recommended: plugin signing
export CERTIFICATE_CHAIN=/path/to/chain.crt
export PRIVATE_KEY=/path/to/private.pem
export PRIVATE_KEY_PASSWORD=...

./gradlew publishPlugin     # builds, signs (if keys set) and uploads
```

For the very first upload you can also just build the ZIP and upload it manually on the Marketplace
website:

```bash
./gradlew buildPlugin       # build/distributions/omni-player-<version>.zip
```

See the JetBrains guide on
[signing](https://plugins.jetbrains.com/docs/intellij/plugin-signing.html) and
[publishing](https://plugins.jetbrains.com/docs/intellij/publishing-plugin.html) for how to generate
the certificate/key pair and the token.

## License

Released under the [MIT License](LICENSE).

## Status

Actively in development — more features to come. Feedback and ideas are welcome.
