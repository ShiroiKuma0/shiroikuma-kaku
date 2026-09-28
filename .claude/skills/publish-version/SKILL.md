---
name: publish-version
description: Publish the latest built 白い熊 画 (shiroikuma.kaku, fork of 0xbad1d3a5/Kaku) APK as a GitHub release on ShiroiKuma0/shiroikuma-kaku — a no-"v" tag equal to the APK's version (0.1.0+012), the signed APK attached, the release section of CHANGELOG.md (ONLY our changes — upstream is dead and keeps no changelog) as notes, a refreshed fork-style README, and the default branch set to `custom`. Use whenever 白い熊 says "publish", "/publish-version", "publish a version", "publish the latest build", "cut a release", "make a GitHub release", or "tag a release" in this repo.
---

# Publish the latest build as a GitHub release

Takes the most recent APK already built into `~/tmp/` and publishes it as a GitHub Release on
`ShiroiKuma0/shiroikuma-kaku`. The global `~/.claude/skills/publish-version/SKILL.md` is the family
rulebook; this file pins what is specific to this repo.

> **Outward-facing and public.** 白い熊 asking to publish IS the go-ahead. Otherwise confirm first.
> Never delete or overwrite an existing release or tag, and never delete an APK.

> **No Claude/Anthropic attribution** anywhere — commits, README, changelog, release notes.

## Preconditions

- On branch `custom`, working tree committed, `custom` pushed (`git log origin/custom..custom` empty).
  If not, stop and tell 白い熊.
- `gh auth status` → `ShiroiKuma0`. Run `gh` and `git push` with `dangerouslyDisableSandbox: true`.
- A signed build in `~/tmp/shiroikuma-kaku_<VERSION>_arm64-v8a.apk` (from `build-apk`). If none, build.

## Steps

1. **Repo and build.**
   ```bash
   OWNER_REPO=ShiroiKuma0/shiroikuma-kaku      # a fork: bare gh calls would hit 0xbad1d3a5/Kaku
   gh repo set-default "$OWNER_REPO"
   APK=$(ls -t ~/tmp/shiroikuma-kaku_*_arm64-v8a.apk | head -1)
   TAG=$(basename "$APK" | sed -E 's/^shiroikuma-kaku_(.*)_arm64-v8a\.apk$/\1/')   # e.g. 0.1.0+012
   git tag -l "$TAG"                                                                 # must be empty
   ```
   **Tag = exactly the version in the file name**: no leading `v`, counter zero-padded to three
   digits. Pass `--repo "$OWNER_REPO"` to every `gh` call.

2. **Changelog — `CHANGELOG.md`, only our changes.** Upstream keeps no changelog and will never
   release again, so the file is ours alone: `# Changelog` header note, then releases newest first as
   `## 白い熊 画 <TAG> — <YYYY-MM-DD>`, grouped under `###` headings (e.g. Appearance & UI page ·
   OCR · Dictionaries · Backup & automation · Android compatibility · Fixes · Packaging).
   - **The first release lists everything the fork adds over Kaku 1.3.81** — walk
     `git log --no-merges --reverse master..custom` and `CLAUDE.md`.
   - **Later releases list every change since the previous tag** — `git log --no-merges <prev-tag>..HEAD`.
   - Specific, user-facing bullets; never "various improvements".
   - Extract the new section to a notes file in the scratchpad (literal match — the `+` in the tag
     breaks regexes): `awk -v h="## 白い熊 画 $TAG" 'index($0,h)==1{p=1;print;next} p&&/^## /{exit} p' CHANGELOG.md`.

3. **README.md** — keep the fork-style structure (centered icon + name + tagline, "A fork of Kaku
   with major additions: …", side-by-side note, **📥 Latest release: [`<TAG>`](…/releases/latest)**,
   emoji feature sections, "Built on Kaku" credit + licences, Building). Update the latest-release
   line; add a section for any newly shipped major feature.

4. **Commit and push:** `git add README.md CHANGELOG.md` (only those) →
   `git commit -m "Release <TAG>: README + changelog"` → `git push origin custom`.

5. **Default branch:** `gh repo edit "$OWNER_REPO" --default-branch custom` (idempotent).

6. **Tag and push it:** `git tag "$TAG" && git push origin "$TAG"`.

7. **Release:**
   ```bash
   gh release create "$TAG" "$APK" --repo "$OWNER_REPO" --title "$TAG" --notes-file <notes> --latest
   ```

8. **Verify + report:** `gh release view "$TAG" --repo "$OWNER_REPO" --json url -q .url` — give 白い熊
   the URL.

## Rules

- Tags never carry a leading `v`; they match the APK file name byte for byte.
- The changelog is exhaustive and specific; the README sells only the highlights.
- `CHANGELOG.md` holds **only our changes**.
- Never retag, clobber or delete a published release without 白い熊's explicit word.
