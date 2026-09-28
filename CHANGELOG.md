# Changelog

白い熊 画 (`shiroikuma.kaku`) is a fork of [Kaku](https://github.com/0xbad1d3a5/Kaku) 1.3.81.
Upstream keeps no changelog and stopped in 2022, so this file carries the fork's own history alone,
newest release first.

## 白い熊 画 0.1.0+024 — 2026-09-28

The first release of the fork, built on Kaku 1.3.81 (upstream `master` 02ee884, 2022). Everything
below is what 白い熊 画 adds over Kaku.

### Identity & packaging
- Own app id `shiroikuma.kaku`, label **白い熊 画**, own signing key — installs side-by-side with Kaku.
- Own versioning from `0.0.0+001` with a never-reset, zero-padded build counter; arm64-v8a only.
- **No network at all:** no `INTERNET` permission, no ads (AdMob removed), no Google Play Services,
  no analytics, nothing downloaded. OCR models and dictionaries are imported from local files; the
  app shows the download URLs with a Copy URL button.
- **Nothing bundled:** upstream's 2019 JMdict database and Tesseract data are gone from the APK
  (59 MB → 21 MB), as is the unused kuromoji tokenizer.
- Removed: the fan-art gallery with its pixiv/twitter links, the tutorial videos (the tutorial is
  text now), the Beta screen that opened on every launch, the Play-rating dialogs and the feedback
  e-mail.
- Licence: GPL-3.0 (Yomitan's deinflector is ported); Kaku's BSD-3 notice is kept.

### Appearance
- Traced black-yellow launcher icon (Kaku's 画 tile in the house style) with an adaptive and a
  monochrome layer, a vector notification icon, and a camera-body variant for the camera launcher.
- **Black-yellow everywhere:** start screen, tutorial, permission dialog, result window, instant
  popup, recognised characters, kanji-choice window, handwriting editor, capture box, dialogs and
  toasts — all painted from one settings table.
- Toasts are black-yellow bordered pills drawn as a small overlay, so they keep the look on
  Android 11+ where custom toasts are otherwise refused in the background.

### 白い熊 画 UI — the settings page
- A full settings page opened from a new Settings cog on the start screen (tap or long-press).
- Sections: Export / Import, Fork behaviour (reset), OCR, Dictionaries, Capture, Camera, Colours,
  Borders & shapes, Fonts, About; every group ends in a live preview.
- Colour picker with A/R/G/B sliders and remembered swatches; font picker drawing each font in its
  own glyphs; import of external fonts; sliders for sizes, weights, border widths, corners and
  window opacity.
- A first-run guide on the start screen while no OCR data or no dictionary is imported, pointing to
  the right sections.

### Export / Import and backup automation
- Export to a chosen folder (Storage Access Framework) as `shiroikuma-kaku_<date_time>.zip`,
  written as `.part` and renamed when complete; import restores what the archive carries.
- Categories: UI settings (with imported fonts), app settings, OCR data (MangaOCR and Tesseract
  files) and dictionary data (the dictionary database and its images) — a new phone is restored
  without importing anything by hand.
- Backup automation for 保存復元 / 自由作業盤 (sister-app contract v2): broadcast export, a content
  provider for describe / export / import / cancel with pinned callers, a foreground service with
  progress and cancel, and the enable-automation (on) and use-token (off) switches in Export / Import.

### OCR
- **MangaOCR on the device** (kha-white/manga-ocr-base as the int8 ONNX export, on ONNX Runtime),
  the default engine: far better on stylised, vertical and manga text. Its three files are imported
  on the UI page's OCR section, checked before they replace anything.
- Each recognised character carries MangaOCR's top-8 candidates for the kanji-choice window.
- Tesseract 5 (Tesseract4Android, replacing the dead tess-two) stays selectable and is the fallback;
  its Japanese data is imported on the OCR section too, checked by loading it in Tesseract first.
- Clear messages when no OCR data or no dictionary is imported yet.

### Dictionaries
- **Yomitan dictionaries** (JMdict, Jitendex, KANJIDIC, JPDB frequencies, pitch dictionaries …)
  imported from their zips: streaming import in a foreground service with a live progress dialog on
  the UI page, replace-on-update by title or index URL, order / disable / delete, and copyable
  download URLs. Half-finished imports are cleaned up at start.
- **Yomitan's own deinflector** ported (its language-transformer engine over its Japanese rules),
  matching Yomitan's output exactly; lookups scan like Yomitan (longest prefix, script variants,
  part-of-speech agreement) and rank by match length, deinflection, frequency and dictionary order.
- **Rich result window:** entries rendered as Yomitan renders them, in a WebView — each dictionary's
  own layout and stylesheet (sense groups, example sentences with furigana, notes, "See also"
  boxes, tables), recoloured to the theme; headword with furigana; chips for deinflection, tags and
  frequencies; a pitch-accent graph; KANJIDIC readings, meanings and stats.
- **Dictionary pictures** are imported with the dictionary and shown: illustrations, and Jitendex's
  rare-kanji glyphs drawn in the text colour.
- **"See also" links** look the word up in place, with a back chip along the chain.
- **Pinch-to-zoom** in the result window and the popup, remembered, also as a slider.
- **Instant popup** with the same display in compact form: three senses per word, no example or note
  boxes, pictures kept, sized to its content.

### Capture & reading
- Recognised characters run like ordinary text: larger (40 dp by default, adjustable), left-aligned,
  edge to edge.
- Kanji choice: a brief swipe down opens the candidates and they stay open; a tap picks one, a tap on
  the character's own image restores the original recognition, a tap elsewhere closes. Swipes are
  judged by direction; releasing never picks.
- **Instant mode fires at any box size** (upstream only read a box narrower than about 45 dp, too
  narrow for one column of larger text); a slider can limit it again.
- The Capture section holds instant mode, the black-and-white filter and the text direction.
- The notification has four buttons: Camera, Instant mode, Image filter, Shut down.

### 白い熊 画 カメラ — OCR through the camera
- A full-screen camera view under the ordinary capture box: Freeze / Unfreeze (the still shown
  undistorted), tap to focus, pinch zoom, light, and Live — once the picture holds still, it is read
  automatically into the instant popup.
- Five ways in: its own launcher icon (switchable), the app shortcut 「カメラ」, an intent, a
  camera button on the start screen and the notification's Camera button.

### Android 13–15, rotation and folding
- Modern toolchain: AGP 8.13, Gradle 8.14, Kotlin 2.2, target SDK 35 (Android 15), min SDK 24.
- Notification permission requested; the capture service is a typed media-projection foreground
  service; services and receivers no longer exported; edge-to-edge insets.
- The single-use screen-capture consent of Android 14 is handled: hiding the box pauses capture
  instead of stopping it, and a spent consent is renewed with one prompt instead of failing silently.
- Rotation and unfolding re-fit the capture surface and every window (the Mate XT's folds included);
  the main screen and tutorial are no longer locked to portrait.

### Fixes
- The asset copy checked only the first file; old screenshots are actually purged.
- Kotlin 2 and API 33 signature fixes throughout.
