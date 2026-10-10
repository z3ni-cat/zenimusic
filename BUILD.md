# ZeniMusic Native v2 — Build Manual

APK release dibangun TANPA Gradle (Gradle tidak bisa jalan di sandbox ini:
proses Java tidak bisa membuka koneksi TCP keluar, sehingga IPC Gradle daemon
gagal). Sebagai gantinya dipakai toolchain langsung:

| Tahap | Tool |
|---|---|
| Download dependency (110 artefak) | `tools/resolve.py` (parser POM + Gradle module metadata, via curl) |
| Ekstrak AAR/JAR | `tools/prep.py` |
| Merge resources antar-library | `tools/merge_res.py` |
| Compile + link resources (aapt2) | `tools/link_res.py` (juga generate R.java per-library) |
| Compile Kotlin + Compose plugin | `tools/compile.sh` (kotlinc embeddable 2.0.21) |
| Dex | `tools/dex.sh` (D8 dari build-tools 35.0.1 — D8 34.0.0 crash pada beberapa class androidx.core) |
| zipalign + sign | `tools/sign.sh` (keystore asli v1.0.0) |

## Perintah

Build penuh dari nol:
```
python3 tools/resolve.py && python3 tools/prep.py && python3 tools/merge_res.py \
  && python3 tools/link_res.py
./tools/build-all.sh   # compile.sh + dex.sh + sign.sh
```

Rebuild cepat setelah edit kode Kotlin:
```
./tools/build-all.sh
```

Output: `~/workspace/zenimusic-apk/ZeniMusic-v2.0.0.apk`

## Catatan penting

- `ANDROID_HOME=~/workspace/android-sdk`, `JAVA_HOME=~/workspace/jdk/jdk-17.0.20.1+1`
  (sudah di `~/workspace/android-sdk/env.sh`).
- Gradle butuh proxy; file `/root/.gradle/gradle.properties` berisi kredensial
  proxy sandbox (bukan password keystore).
- Password keystore TIDAK PERNAH ditulis ke file/log — `sign.sh` membaca dari
  `~/workspace/zenimusic-apk/ZeniMusic-signing-key-info.txt` ke env var saja.
- Room diganti SQLiteOpenHelper manual (`data/db/ZeniDb.kt`) karena KSP tidak
  bisa jalan tanpa Gradle. Skema & perilaku sama.
- Jika tambah dependency baru: tambahkan ke `WANTED` di `tools/resolve.py`,
  lalu jalankan ulang pipeline penuh.
