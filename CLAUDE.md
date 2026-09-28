# CLAUDE.md — shiroikuma-kaku

**白い熊 画** — 白い熊's fork of [Kaku](https://github.com/0xbad1d3a5/Kaku) (BSD-3), the Android
Japanese OCR popup dictionary: a capture box floating over any app, OCR of what is under it, and a
dictionary lookup of the recognised text. Package **`shiroikuma.kaku`**, installable side-by-side with
Kaku.

Why this fork exists: Kaku was the best on-screen Japanese OCR dictionary for Android, but its
upstream stopped in May 2022 (last release 1.3.78/1.3.81, targetSdk 31) and it was pulled from Google
Play in January 2025. The installed 1.3.81 was verified identical to upstream `master` (02ee884), so
the fork starts there. Goals, in order: a modern, Google-free, network-free build in the family's
black-yellow look with a full **白い熊 画 UI** settings page; then **MangaOCR** (on-device ONNX,
Tesseract as fallback) instead of Tesseract 3; then **Yomitan dictionaries** instead of the bundled
2019 JMdict DB.

## Read this first

- **`.claude/skills/build-apk/SKILL.md`** — identity, build, signing, versioning.
- **`.claude/skills/publish-version/SKILL.md`** — GitHub release with our changelog.
- There is **no `upstream-new-version` skill**: upstream is dead. If it ever moves again, add one
  modelled on the sister forks.

## Branch & remote model

| Branch | Role |
| --- | --- |
| `master` | Upstream's `master` (02ee884). No fork work here. |
| `custom` | All our work; the GitHub default branch. |

- `origin` = `git@github.com:ShiroiKuma0/shiroikuma-kaku.git` (ssh, push here; a GitHub fork of
  0xbad1d3a5/Kaku).
- `upstream` = `https://github.com/0xbad1d3a5/Kaku.git` (https, **fetch only** — push URL `DISABLED`).
- **Never rename the code namespace** `ca.fuwafuwa.kaku`. Only the installed `applicationId` differs.

## Identity

| What | Value | Where |
| --- | --- | --- |
| applicationId | `shiroikuma.kaku` | `shiroikuma/fork.gradle` |
| App label | `白い熊 画` | `res/values/strings.xml` `app_name` |
| Our settings page | **`白い熊 画 UI`** — every configurable item of the fork; opened by a **long-press on the Settings cog** of the main screen (a tap opens it too — Kaku has no settings page of its own) | `shiroikuma/kaku/KakuUiActivity.java` (see *The UI page* below) |
| File prefix | `shiroikuma-kaku_` — APKs `shiroikuma-kaku_<ver>+NNN_arm64-v8a.apk`, exports `shiroikuma-kaku_<yyyy-MM-dd_HH-mm-ss>.zip` | |
| Launcher icon | Kaku's 画 tile traced in the house style: the tile as a yellow rounded outline, 画 solid yellow, on black (the `tile` variant, confirmed by 白い熊 2026-09-28); the notification icon is 画 alone | `shiroikuma/icon/trace-icon.py` → `kaku-icon.svg` / `kaku-notification.svg` → `gen-icons.py` writes every icon resource in `app/src/main/res` |
| Keystore | `~/.android-keystores/shiroikuma-kaku.jks`, alias `kaku` | `keystore.properties` (gitignored) |
| Licence | Fork: GPL-3.0 (`LICENSE`); upstream's BSD-3 notice kept in `LICENSE-Kaku-BSD-3` | |

## Build (summary — details in `build-apk`)

```bash
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ANDROID_HOME=/home/shiroikuma/android-sdk
./gradlew buildFork < /dev/null   # signed APK → ~/tmp/shiroikuma-kaku_<version>_arm64-v8a.apk, bumps the counter
```

- **Own versioning** in `shiroikuma/fork.properties`: `versionName = "<VERSION_NAME>+<BUILD_NUMBER
  padded to 3>"`, started at `0.0.0+001`. `BUILD_NUMBER` bumps with every build and never resets.
  **`VERSION_NAME` moves only with 白い熊's OK** — propose a bump when a major change lands, then wait.
- `versionCode = major*100000000 + minor*1000000 + patch*10000 + BUILD_NUMBER`.
- Delivered via the global `/after-build`.

## The fork layer — where our changes live

Upstream is dead, so we edit its files freely; still keep our own code recognisable:

- `shiroikuma/` — `fork.gradle` (app id, signing, version, `buildFork`; applied by the last line of
  `app/build.gradle`), `fork.properties` (version + counter), `icon/` (icon tracing pipeline).
- `app/src/main/java/shiroikuma/kaku/` — `KakuFork` (name, GitHub links), `ProjectionConsentActivity`
  (fresh screen-capture consent once Android 14 has spent the previous one), `Insets.kt`
  (edge-to-edge padding for targetSdk 35).
- Toolchain: AGP 8.13, Gradle 8.14, Kotlin 2.2, JDK 17 bytecode, compile/targetSdk 35, minSdk 24;
  OCR on Tesseract 5 via Tesseract4Android (jitpack), initialised with the legacy engine
  (`OEM_TESSERACT_ONLY`) so the kanji-choice window keeps per-character alternatives.
- New fork code lives under `app/src/main/java/shiroikuma/kaku/`.

## The UI page, the skin, Export / Import

All in `app/src/main/java/shiroikuma/kaku/` (ported from shiroikuma-doksho's kit):

- **`KakuUi`** — every setting with its default (prefs file `kaku_ui`); pure `#000000` grounds and
  `#FFFF00` ink / borders. `stamp()` bumps on every change.
- **`KakuUiActivity`** — the page, built in code in the **kxkb UI page format**: 36 / 54 / 72 / 90 dp
  indents, bold headings underlined as wide as their text, a 1 px rule between top-level groups,
  tight 4–5 dp rows. Sections: **Export / Import** (first), **Fork behaviour** (reset), **Colours**
  (App screens · Lookup windows · Dictionary text · Recognised characters · Kanji choice · Capture
  box · Handwriting editor · Dialogs), **Borders & shapes** (windows: width 0–8 dp, corners 0–40 dp,
  opacity; word highlight width; dialogs), **Fonts** (Dictionary text · Recognised characters ·
  Interface text · Page headings), **About**. Every group ends in a live preview.
- **`ColorPickerDialog`** (one-click remembered swatches, preview, A/R/G/B sliders),
  **`FontPickerDialog`** + **`KakuFonts`** (every font drawn in its own glyphs; imports copied into
  `files/fonts`), **`KakuViews`** (bordered dialogs, pills, toast).
- **`KakuSkin`** — paints the overlay windows from `KakuUi` (each `Window` repaints in `applySkin()`
  when the stamp changed, via `refreshSkin()` in `show()`), builds the capture-box frames, and paints
  every activity (`KakuApp` lifecycle hook). `DictText` styles dictionary results (headword, reading,
  part of speech, meanings). The capture box keeps its fixed 80 % opacity and its red ready line.
- **`ExportImportPanel`** + **`backup/ShiroikumaExport`** — SAF export directory (device-local
  `kaku_eximport`), ZIP `shiroikuma-kaku_<yyyy-MM-dd_HH-mm-ss>.zip` written as `.part` and renamed;
  categories **ui** (page prefs + fonts), **settings** (the app's prefs minus device state),
  **ocr** (`files/tessdata`), **dictionary** (`files/*.db`). Import merges prefs with `commit()` and
  replaces data files atomically. Dialog chain: export success OK / import 「Later」 close info +
  panel + page; 「Restart now」 restarts; failures leave the panel open.

- **`automation/*`** — the 保存復元 contract v2 (from
  `~/git/shiroikuma-jiyusagyoban/sister-app-contract-backup-automation-hand-off.md`, revision
  2026-09-05 08:19), ported from shiroikuma-doksho's Java set: `StateExportReceiver`
  (`shiroikuma.kaku.action.EXPORT_STATE` / `LIST_CATEGORIES` / `CANCEL_EXPORT`), `AutomationProvider`
  (`shiroikuma.kaku.automation`: describe / export / import / cancel, callers pinned by package, uid
  and certificate in `AutomationCallers`), `AutomationDataService` (dataSync foreground service, partial
  wakelock), `AutomationAuth` (prefs `shiroikuma_automation`, never exported; switch ON, token OFF,
  every write `commit()`), `AutomationProgress` (§3, 区分 counts + 20 s heartbeat), `AutomationJobs`,
  `AutomationForeground`. **Difference from doksho:** the §1 export runs in `AutomationDataService`,
  not in the receiver's `goAsync()` — the archive carries the OCR data and the dictionary (tens of
  MB), too long for a broadcast window. The app does not declare `MANAGE_EXTERNAL_STORAGE`: a `path`
  extra is ignored when the SAF directory is set, else `ERROR:no-storage-access`. The three rows
  (switch · token switch · token) sit in the UI page's Export / Import section. No preference this
  app restores is security-relevant; `requires_permissions` is `[]` (own prefs and files only).

## OCR engines

- **MangaOCR** (default) — `shiroikuma/kaku/ocr/MangaOcr.kt`: kha-white/manga-ocr-base as
  onnx-community/manga-ocr-base-ONNX's int8 `encoder_model_quantized.onnx` + `decoder_model_quantized.onnx`
  and kha-white's `vocab.txt`, on ONNX Runtime Android 1.27 (CPU, 4 threads). Files imported by hand
  on the UI page (OCR section; URLs copyable) into `files/tessdata` as `mangaocr-encoder.onnx`,
  `mangaocr-decoder.onnx`, `mangaocr-vocab.txt` (`OcrImport`: `.part`, checked in ORT / 6144 lines,
  then renamed) — the Export / Import OCR category carries them. Pipeline verified on the desktop
  against the PyTorch model (ORT 1.27 and 1.30, identical text): gray → RGB, bilinear 224², (v/255−0.5)/0.5,
  greedy decode from 2 to 3, ≤ 300 tokens, whole prefix per step (no KV cache). Top-8 softmax
  candidates per character feed the kanji-choice window; no positions, so each character's picture
  is the whole capture. It reads the unfiltered capture.
- **Tesseract 5** (Tesseract4Android, legacy engine, bundled `jpn.traineddata`) — chosen on the UI
  page, and the fallback while MangaOCR is not imported or fails (`OcrRunnable.recognize`).
- Native libraries are packed compressed (`useLegacyPackaging`): ONNX Runtime is 28 MB raw.

## Yomitan dictionaries

`shiroikuma/kaku/dict/`: imported Yomitan zips replace the bundled 2019 JMdict as soon as one is
switched on (`Searcher` → `YomitanTask`; else upstream's `JmTask`).
- `DictDb` — `files/yomitan.db` (memento's shape: dictionaries · terms · term_meta · kanji ·
  kanji_meta · tags; glossaries deflated JSON; lookup indices dropped during an import and rebuilt).
  A dictionary is replaced by one of the same title **or the same indexUrl** (JMdict's title carries
  its date). Incomplete dictionaries (a dead import) are deleted at start-up (`KakuApp`).
- `YomitanReader` (streaming, format 3 and legacy rows) → `DictImport` (batched transactions) in
  `DictImportService` (dataSync foreground service, progress notification; files picked on the UI
  page's Dictionaries section, which also lists / orders / disables / deletes them and shows copyable
  URLs). Verified on the real JMdict (527,223 terms), KANJIDIC and JPDB by `YomitanReaderTest`
  (`KAKU_DICT_DIR`).
- `Deinflector` — Yomitan's `language-transformer.js` engine over `japanese-transforms.js`, exported
  to `assets/yomitan/japanese-transforms.json` by `shiroikuma/yomitan/export-transforms.mjs`
  (re-run after a Yomitan update); `DeinflectorTest` checks it against Yomitan's own output
  (`reference-deinflections.mjs`). GPL-3 code, hence the fork's GPL-3.
- `DictLookup` — Yomitan-style scan (prefixes ≤ 20 chars, script variants, deinflection, parts of
  speech must agree), grouped per word, ranked (length → deinflection → frequency → score → order),
  frequencies / pitch from term_meta, kanji from KANJIDIC. `YomitanText` renders it (structured
  content flattened to styled text) through `DictText`.

## 白い熊 画 カメラ — OCR through the camera

`shiroikuma/kaku/camera/CameraActivity.kt` (approach A, agreed 2026-09-28): a full-screen CameraX
preview under the ordinary capture box, which reads the screen as over any app — so recognition,
result windows, candidates and dictionary are the shared ones. Freeze (preview bitmap shown, camera
released) / Unfreeze, tap to focus, pinch zoom, light, Live (a 320×240 `ImageAnalysis` stream; once
the picture has been still for `KakuUi.CAM_LIVE_SETTLE` ms and changed since the last read,
`MainService.recognizeInCaptureBox()` → `CaptureWindow.recognizeNow()`: fresh screenshot, instant
OCR, popup beside the box; skipped while the full result window is open). Starts the capture
service itself (consent prompt) or un-hides the box. Five launch routes: the activity-alias
`CameraLauncherAlias` (icon variant "b — camera body", `shiroikuma/icon/camera-icon.py`, switched by
`KakuUi.CAM_LAUNCHER`, applied in `KakuApp`), the static shortcut `res/xml/shortcuts.xml`, the
intent `shiroikuma.kaku.action.CAMERA`, the start screen's camera button, and the notification's
Camera action (it took the Image filter slot; that switch, instant mode and text direction are now
in the UI page's Capture section).

**Fully unfolded, the camera view is landscape — by EMUI, not by us (白い熊 accepted, 2026-09-28).**
EMUI locks the display to its natural orientation (landscape, 3184×2232, rotation 0) for any app
while it has the camera open (`dumpsys window`: `mCameraRotationMode`; Yomiwa's task snapshot shows
the same). No orientation request is honoured then (tried `fullSensor` and unspecified). Turning the
picture inside the view is wrong (the picture is already right; +015 did that and was reverted in
+016). Folded and half-folded follow the phone normally.

## Hard rules of this app

- **No network.** No `INTERNET` permission, no downloads, no analytics, no ads, no Google Play
  Services. OCR models and dictionaries are **imported from local files**; the app shows the URLs
  where they can be obtained (copy-paste into a browser).
- **Look:** black `#000000` ground, yellow `#FFFF00` text and borders by default; every colour, font,
  size, border and corner is configurable on the 白い熊 画 UI page, always with a live preview.
- The capture box's 1 px **red** ready-border is read back from the screenshot by
  `CaptureWindow.checkScreenshotIsReady` — do not theme it without rewriting that check.

## Changelog

`CHANGELOG.md` carries **only our changes** (upstream keeps none). Every release adds its section at
the top; `publish-version` publishes it.

## Working rules (override harness defaults where noted)

- **No `Co-Authored-By: Claude` / "Generated with Claude" trailer** in commits or PR bodies — end the
  message at the last line of the body. (Global rule, `~/.claude/CLAUDE.md`.)
- **Never commit or push until 白い熊 says "Push".** "Push" = commit + `git push origin custom`.
- **Build when a change is finished** (global `/after-build` standing authorization) and deliver via
  `/after-build` — never ask how to transfer. Never `adb install` / `adb uninstall`; 白い熊 installs
  from `/sdcard/tmp/`. `adb` always unsandboxed.
- Git, `gh`, Gradle and keystore commands run with the sandbox disabled.
- Never delete or overwrite a built APK (global rule). `buildFork` refuses to overwrite one.
- `~/tmp` holds only what 白い熊 looks at (`<name>_<yyyy-MM-dd_HH-mm-ss>.<ext>`); scratch work goes
  to `.scratch/` (gitignored) or the session scratchpad.
- If unsure about anything, ask 白い熊 — don't guess.
- Device: Huawei Mate XT tri-fold (EMUI) — see the global `mate-xt-folded-screen` skill.
