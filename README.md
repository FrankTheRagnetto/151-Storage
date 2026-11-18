# 151 Storage

An Android file manager and universal viewer for your phone and your own cloud (WebDAV). No ads, no tracking.

Part of The 151 Universe family of apps.

## What it does
- **Media** – photos and videos by album, newest/oldest/largest, from the phone and from the cloud, with thumbnails made on the phone.
- **Docs** – opens almost any file offline: PDF, Word, Excel, PowerPoint, OpenDocument, EPUB, comics (CBZ/CBR), Markdown (Obsidian-style), code, archives (ZIP, RAR, 7z), mail (EML/MSG), e-invoices, diagrams, GeoJSON and more.
- **Phone** – the phone's folders like a classic file manager: select (folders too), sort, search, rename, move, copy, info, open with, favourites, trash.
- **Cloud** – browse any WebDAV service (Nextcloud and others): stream videos, download, keep folders "on the phone" for offline use, delete to the cloud's trash.
- **Upload** – sends photos to a folder named after the month they were taken, skips duplicates, and keeps its queue across restarts.

## Build
Requirements: JDK 21 and the Android SDK (compileSdk 36).

```
./gradlew assembleDebug        # app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest    # unit tests on the JVM
```

Signing is optional: put `keystore.properties` (`storeFile`, `storePassword`, `keyAlias`, `keyPassword`) and your keystore in `.signing/` (ignored by git) to sign debug and release with your key. Without it, debug uses the Android debug key and release is unsigned.

## Tests
- `tools/web-test/` – the document viewer pages, in a real Chromium (Playwright): `PW_CORE=<path to playwright-core> bash tools/web-test/run-all.sh`. The first run makes the synthetic samples (needs Ghostscript and Pillow) and downloads the public sample files listed in `tools/web-test/fetch-real-samples.sh`.
- `tools/emu-test/` – cloud, uploads and thumbnails on an Android emulator against a throwaway Apache WebDAV server in Docker.
- `bash tools/test-all.sh` runs both and exits non-zero if anything fails or the emulator is missing.

All test files are synthetic (`tools/web-test/make-samples*.py`, `tools/emu-test/make-*-fixtures.py`). Code comments are in Italian.

## License
The app code is released under the MIT License (see `LICENSE`).

Bundled third-party code keeps its own license, each next to it:
- `app/src/main/assets/pdfjs/` – PDF.js (Apache 2.0) and its fonts and decoders.
- `app/src/main/assets/lib/*/LICENSE` – docx-preview, DOMPurify, fflate, highlight.js, JSZip, KaTeX, libarchive.js, mammoth, markdown-it (+ footnote, mark), Mermaid, UTIF, SheetJS.
- Fonts Archivo, EB Garamond and JetBrains Mono – SIL Open Font License, see `app/src/main/assets/viewer/fonts/OFL-*.txt` (the same fonts are in `app/src/main/res/font/`).
- AndroidX, Material Components, OkHttp, JSch – fetched by Gradle under their own licenses.

A personal project, shared as is: no support, but issues are welcome.
