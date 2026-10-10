# 🎧 ZeniMusic

Aplikasi pemutar musik Android native — modern, bersih, dan ringan. Dibangun dari nol dengan Kotlin + Android Views klasik (tanpa Compose), mengambil audio dari YouTube via NewPipe Extractor, dengan background playback via Media3.

![version](https://img.shields.io/badge/version-2.4.2-violet)
![platform](https://img.shields.io/badge/platform-Android-blue)
![language](https://img.shields.io/badge/language-Kotlin-orange)

## ✨ Fitur

- 🔍 **Cari & putar** lagu dari YouTube
- 📻 **Radio otomatis** — setelah lagu selesai, lanjut ke lagu se-genre (tidak acak)
- 🎙️ **Podcast** — channel podcast Indonesia & English, episode auto-update
- 📋 **Playlist** — buat dan kelola playlist sendiri
- ❤️ **Favorit**, 🕘 **Riwayat**, ⬇️ **Download offline**
- 🔀 Shuffle & 🔁 repeat
- 🌙 **Soft Dark** theme — modern, clean, soft, simple
- ▶️ Mini player minimalis

## 📱 Download

Ambil APK terbaru di halaman [Releases](https://github.com/z3ni-cat/zenimusic/releases).

> Install sebagai update — signature sama, data tidak hilang.

## 🖼️ Screenshot

_(segera — menyusul dari device)_

## 🛠️ Build manual (tanpa Gradle)

Project ini di-build tanpa Gradle memakai toolchain manual di `tools/`:

```bash
# Kalau ada perubahan di app/src/main/res/:
python3 tools/merge_res.py && python3 tools/link_res.py

# Kalau ada perubahan kode Kotlin:
bash tools/compile.sh

# Selalu:
bash tools/dex.sh && bash tools/add-services.sh && bash tools/sign.sh
```

Hasil: `~/workspace/zenimusic-apk/ZeniMusic-vX.Y.Z.apk`

> **Catatan:** file signing (`*.keystore`) TIDAK ada di repo ini — simpan secara terpisah dan jangan pernah di-commit.

## 🧱 Arsitektur

```
app/src/main/java/com/zenimusic/app/
├── MainActivity.kt        # UI utama (programmatic Views)
├── PlayerActivity.kt      # Layar full player
├── WelcomeActivity.kt     # Intro pertama kali
├── data/
│   ├── db/                # SQLite manual (ZeniDb)
│   ├── model/             # Track
│   └── repo/              # MusicRepository (NewPipe), Podcast
├── net/                   # OkHttpDownloader
├── player/                # PlayerManager, PlaybackService (Media3)
└── ui/                    # AppViewModel, adapter, UiKit
```

**Prinsip:** `android.app.Activity` + Views programmatic. Tanpa Compose / ComponentActivity / Coil / Fragment — itu penyebab crash saat launch di versi awal.

## 📄 Lisensi

Private project — all rights reserved.
