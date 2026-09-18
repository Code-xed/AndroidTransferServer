# Android Transfer Server — Core Milestone

Turns the phone into a LAN file server: start the server, open the shown URL from any
browser on the same Wi-Fi, upload/download files. No cloud, no client app required on
the other device.

This is **milestone 1 of the full spec** — the HTTP core, storage abstraction, and a
working browser UI, streaming real files end-to-end. WebDAV verbs, ZIP, auth, and QR
are the next milestones (see "Deferred" below); the architecture is built so they slot
in without a rewrite.

## What actually works right now

- Raw-socket HTTP/1.1 server (`server/HttpServer.kt`): one coroutine per connection,
  keep-alive, streamed request/response bodies via a fixed 64 KB buffer — never loads
  a whole file into memory regardless of size.
- **GET/HEAD** with real **Range** support (206 Partial Content, single-range,
  suffix-range `-N` supported) for resumable/seekable downloads of any file size.
- **PUT** upload streamed straight to storage (no temp copy, no Base64, no JSON
  wrapping).
- **DELETE**.
- Storage abstraction (`storage/StorageProvider.kt`) with two implementations:
  - `LocalFileStorageProvider` — plain `java.io.File`, used for the web root.
  - `SafStorageProvider` — real SAF-backed provider for the user's picked shared
    folder, using `ParcelFileDescriptor` + `FileChannel.position()` for genuine
    Range-seek support (falls back to `skip()` if a provider ever hands back a
    non-seekable descriptor — see comments in that file).
- Foreground service (`service/TransferForegroundService.kt`) hosting the server with
  a persistent notification and a Stop action; survives app backgrounding.
- Custom, fully replaceable web root: `assets/webroot/{index.html,style.css,app.js}`
  are copied into `filesDir/webroot` on first run — edit those three files (or drop in
  a totally different site) without touching app code.
- Functional default frontend: multi-file select (`<input multiple>`), real upload
  progress via `XMLHttpRequest` (chosen over `fetch()` specifically because upload
  progress via fetch is unreliable, especially on iOS Safari), bounded-concurrency
  uploads (3 at a time), live file listing via `/api/list`, click-to-download.
- `/api/list` and `/api/info` — small JSON endpoints backing the UI, per the "WebDAV
  stays the filesystem layer, the API is just UX sugar on top" principle.
- Defensive by construction: every request is parsed and routed inside per-connection
  try/catch (`RequestRouter.handle`) — a malformed request returns 400/403/500 and the
  *connection* ends; it never takes down the server or other clients. Path traversal
  (`../`, encoded variants, absolute paths) is rejected in both storage providers by
  re-validating the resolved path against the root.

## What's deliberately deferred (not stubbed — genuinely not built yet)

- WebDAV verbs: OPTIONS/PROPFIND/MKCOL/COPY/MOVE. `HttpMethod` already declares them
  so the router/storage signatures don't need to change when they're added — only new
  handler methods.
- Streaming ZIP endpoint (`/api/zip`) for multi-select and folder downloads.
- Authentication (Basic auth toggle).
- QR code generation on the native screen (URL + copy/share works today; add a QR
  library — e.g. zxing-android-embedded — as the only new dependency this needs).
- Transfer queue/state tracking on the *server* side (today's progress is purely
  client-side, from XHR events) — needed for the native UI's "2 connected clients /
  118 MB/s" panel and for showing downloads-in-progress, not just uploads.
- Network-change handling (IP display updates only on `onResume` right now, not via a
  live `NetworkCallback`).
- Folder upload (`webkitdirectory`) and drag-and-drop as progressive enhancement.
- Settings screen (port, auth, concurrency limits are currently hardcoded constants).

## Key design decisions (and why)

- **Hand-rolled sockets, not NanoHTTPD or a servlet container.** Existing lightweight
  Android HTTP libraries either don't support WebDAV's XML methods at all or wrap
  streams in ways that make guaranteeing zero-buffering behavior hard to verify.
  Full control over the buffer/copy path was worth the extra code.
- **One coroutine per connection**, not a selector/reactor. This app expects tens, not
  thousands, of concurrent LAN clients — a thread/coroutine-per-connection model is
  simpler to reason about and debug, and coroutine cancellation gives clean shutdown.
- **SAF seek via `ParcelFileDescriptor.getFileDescriptor()` + `FileChannel.position()`**,
  not `InputStream.skip()` by default. This is genuinely fast (a real `lseek`) on the
  common case (local storage, most on-device DocumentsProvider implementations) and
  only falls back to the slower `skip()` loop if the provider ever returns something
  non-seekable. This claim is verifiable by reading `SafStorageProvider.openRead`.
- **No "zero-copy" claims.** Every copy loop uses a real userspace buffer
  (`COPY_BUFFER_SIZE = 64 KB`) and a `read`/`write` loop — data does cross into JVM
  heap-adjacent buffers once per hop. `transferTo`-style kernel-level zero-copy isn't
  reliably available across Android API levels for arbitrary SAF-backed descriptors,
  so it wasn't used and isn't claimed.
- **Separate web root vs. shared storage as two `StorageProvider` instances**, not one
  provider with a flag. `RequestRouter` picks by path prefix (`/files/...` → shared
  storage, everything else → web root), keeping the "these are conceptually different
  things" requirement enforced at the type level, not just by convention.

## Project structure

```
app/src/main/java/com/localtransfer/
  server/    HttpServer, request/response, router, static + shared-storage handlers
  storage/   StorageProvider interface, LocalFileStorageProvider, SafStorageProvider
  service/   TransferForegroundService
  ui/        MainActivity
app/src/main/assets/webroot/   default index.html / style.css / app.js
```

## Building

Standard Android Studio project (Gradle Kotlin DSL, AGP 8.6, minSdk 26, target 35).
Open the root folder in Android Studio and run — no manual project-file surgery
needed. This was authored and reviewed outside of an environment with the Android
SDK/emulator available, so it has **not** been compiled or run here; expect the
normal round of Gradle-sync/lint fixes an unbuilt project needs on first import
(likely nothing structural, since every class is self-contained and dependencies are
minimal — but flagging it plainly rather than claiming a build I couldn't verify).

## Trying it

1. Run the app, tap **Select shared folder** (SAF picker), pick a folder.
2. Tap **Start server**, note the shown URL.
3. From any device on the same Wi-Fi, open that URL in a browser.
4. Select files → Upload. Tap a listed file to download it (Range-capable, so a
   large video will scrub/resume correctly in browsers that request Range).

## Next milestone

Say the word and the WebDAV verbs (PROPFIND/MKCOL/COPY/MOVE) plus the streaming ZIP
endpoint are the natural next slice — they reuse every class built here unchanged.
