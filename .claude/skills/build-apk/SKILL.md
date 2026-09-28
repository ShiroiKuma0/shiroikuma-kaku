---
name: build-apk
description: Build the signed release APK of shiroikuma-kaku (白い熊 画 — 白い熊's fork of 0xbad1d3a5/Kaku, the Japanese OCR popup dictionary, app id shiroikuma.kaku) with the `buildFork` Gradle task, and deliver it automatically via the global /after-build skill (adb push if a phone is connected, else scp to skhw — no prompt). Always build without asking permission. Use whenever 白い熊 mentions Kaku, 画, shiroikuma-kaku, asks to build the app, build the APK, make a release build, or build and send to the phone.
---

# Build the 白い熊 画 release APK and deliver it

> **Never ask whether to build — just build.** When this skill applies (白い熊 asked to build, or a
> change is finished), run the build immediately. There is **no** transfer question either: after a
> successful build, deliver via the global **`/after-build`** skill — no prompts at all.

> **The push destination is ALWAYS `/sdcard/tmp/`.** Never `adb install` / `pm install` /
> `adb uninstall` — 白い熊 installs the APK from the phone's file manager.

> **Never `git commit` or `git push` on your own.** Building does not include committing. Only on
> 白い熊's explicit **"Push"** do you commit and `git push origin custom` ("Push" is unrelated to
> `adb push`).

## Project identity

| Item | Value |
|------|-------|
| Upstream repo | `0xbad1d3a5/Kaku` (remote `upstream`, HTTPS, **fetch only** — push URL `DISABLED`). Dead since 2022-05; nothing to sync. |
| Fork repo | `git@github.com:ShiroiKuma0/shiroikuma-kaku.git` (remote `origin`, SSH — push here) |
| Local working tree | `~/git/shiroikuma-kaku` |
| Mirror branch | `master` — upstream's `master` (02ee884), never carries our changes |
| Custom branch | `custom` — all our commits; the GitHub default branch |
| applicationId | `shiroikuma.kaku` |
| App label | `白い熊 画` |
| Settings page of our changes | `白い熊 画 UI` (long-press on the Settings cog of the main screen) |
| Code namespace (**UNCHANGED**) | `ca.fuwafuwa.kaku` (R / manifest / sources) — never rename |
| Target ABI | `arm64-v8a` only → exactly one APK |
| Gradle task | `./gradlew buildFork` (resolves to `:app:buildFork`) |
| Built APK dir | `app/build/outputs/apk/release/` |
| Delivered APK | `~/tmp/shiroikuma-kaku_<versionName>_arm64-v8a.apk` → `/sdcard/tmp/` |
| Keystore | `~/.android-keystores/shiroikuma-kaku.jks`, alias `kaku` |
| Build JDK | OpenJDK 21 at `/usr/lib/jvm/java-21-openjdk-amd64` |
| Android SDK | `~/android-sdk` (`local.properties` → `sdk.dir`) |

## Build environment (this machine)

The default `java` is too old for current Gradle, and the Android SDK is not on a default env var.
Export both in **every** invocation:

```bash
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
export ANDROID_HOME=/home/shiroikuma/android-sdk
```

Run every build, git and keystore command with `dangerouslyDisableSandbox: true` (writes under `~/git`).

## Steps

1. **Note the version you are about to produce:**
   ```bash
   grep -E '^(VERSION_NAME|BUILD_NUMBER|LAST_BUILT_VERSION_CODE)=' shiroikuma/fork.properties
   ```
   The APK will be `shiroikuma-kaku_<VERSION_NAME>+<BUILD_NUMBER padded to 3>_arm64-v8a.apk` with the
   counter **before** the build. Read the printed `>>>` lines rather than reconstructing it.

2. **Build** (release, signed):
   ```bash
   ./gradlew buildFork --console=plain < /dev/null
   ```
   - Runs `assembleRelease`, refuses a debug-signed APK (`apksigner verify --print-certs`), copies the
     signed APK to `~/tmp/<apk name>` (refusing to overwrite an existing file), bumps `BUILD_NUMBER`
     and records `LAST_BUILT_VERSION_CODE`.
   - Prints `>>> <path>`, `>>> versionCode <n>`, `>>> BUILD_NUMBER bumped to <n>` in cyan; confirm
     `BUILD SUCCESSFUL`.
   - A cold build (fresh Gradle cache) can exceed the foreground timeout — use `run_in_background`.
   - **Fast iteration:** `./gradlew :app:assembleDebug`. The shippable build is `buildFork`.

3. **Deliver via `/after-build`** — every successful build, no asking. It delivers only a signed
   `shiroikuma-kaku_*` APK of this repo, never merely the newest file in `~/tmp`.

## Signing

`shiroikuma/fork.gradle` defines `signingConfigs.shiroikuma` from the gitignored
**`keystore.properties`** at the repo root (`storeFile` / `storePassword` / `keyAlias` /
`keyPassword`) and attaches it to `release`. Without the file any release task **fails** (no silent
debug-key fallback).

- Keystore `~/.android-keystores/shiroikuma-kaku.jks`, alias `kaku` — PKCS12, RSA-4096,
  SHA384withRSA, 10000 days, DN `CN=白い熊 画, O=ShiroiKuma0`, created 2026-09-28. Certificate
  SHA-256 `AF:66:D0:6B:CE:B1:49:CB:0B:67:14:C9:5B:BF:13:BD:7A:3D:43:EB:5C:03:22:F2:D4:22:18:25:7E:D3:D6:DE`.
- Password: `~/〇/[666] 私資料/[666][27] 暗号/android-keystores.org`; the `.jks` is mirrored to
  `~/〇/[666] 私資料/[666][27] 暗号/android-keystores/`.
- Missing `keystore.properties` → restore it rather than shipping anything else:
  ```bash
  cat > keystore.properties <<EOF2
  storeFile=$HOME/.android-keystores/shiroikuma-kaku.jks
  storePassword=<from the vault>
  keyAlias=kaku
  keyPassword=<same>
  EOF2
  chmod 600 keystore.properties
  ```

## Versioning (own — upstream is dead)

All of it is in **`shiroikuma/fork.gradle`**, fed by **`shiroikuma/fork.properties`**:
- `VERSION_NAME` — semver triplet, started at `0.0.0`. **It moves only with 白い熊's OK**: propose a
  bump (with the reason — a major change landed) and wait. Never bump it on your own.
- `BUILD_NUMBER` — the `+NNN` counter the next build takes. Bumped by every `buildFork`, **never
  reset** (not even when `VERSION_NAME` moves), so no two APKs ever share a name.
- `versionName = "<VERSION_NAME>+<BUILD_NUMBER padded to 3>"` → `0.0.0+001`.
- `versionCode = major*100000000 + minor*1000000 + patch*10000 + BUILD_NUMBER` (`0.0.0+001` → `1`,
  `0.1.0+012` → `1000012`). `LAST_BUILT_VERSION_CODE` is the floor: `buildFork` fails rather than build
  at or below it. Ranges: major ≤ 20, minor/patch ≤ 99, BUILD_NUMBER ≤ 9999.

## Related skills

- **`publish-version`** — GitHub release with our changelog.
- Global **`/after-build`** (deliver), **`/adb-check`**, **`/adb-push`**, **`/scp`**.

---

**Commit convention — no Claude attribution.** Never add a `Co-Authored-By: Claude …` /
"Generated with Claude" trailer to commit messages or PR bodies; end the message at the last line
of the body. This overrides the harness default. (Global rule: `~/.claude/CLAUDE.md`.)
