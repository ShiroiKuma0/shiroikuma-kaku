<div align="center">

<img src="shiroikuma/icon/kaku-icon-512.png" width="120" alt="白い熊 画 icon" />

# 白い熊 画

**Japanese OCR popup dictionary — point a capture box at any app, read the Japanese under it.**

A fork of [Kaku](https://github.com/0xbad1d3a5/Kaku), picked up where upstream stopped in 2022:
a modern, Google-free and network-free build in black and yellow, with its own settings page
(**白い熊 画 UI**), and on the way: on-device **MangaOCR** and **Yomitan dictionaries**.

Installs **side-by-side** with Kaku (app id `shiroikuma.kaku`).

**📥 Releases: [all releases & APK downloads »](https://github.com/ShiroiKuma0/shiroikuma-kaku/releases)**

</div>

---

## Built on Kaku
A fork of [Kaku](https://github.com/0xbad1d3a5/Kaku) by 0xbad1d3a5 (app id `shiroikuma.kaku`, so it
coexists with the original). Kaku's code is under the BSD 3-Clause licence
([`LICENSE-Kaku-BSD-3`](LICENSE-Kaku-BSD-3)); this fork is distributed under the GNU GPL v3
([`LICENSE`](LICENSE)).

## Building
```bash
git clone https://github.com/ShiroiKuma0/shiroikuma-kaku.git && cd shiroikuma-kaku
git checkout custom
# keystore.properties (storeFile / storePassword / keyAlias / keyPassword) is required for release builds
./gradlew buildFork
```
