# Studio Mobile

IDE Android yang berjalan **langsung di HP**: menulis kode, build APK/AAB, menjalankan script, dan menandatangani aplikasi tanpa komputer.

- Aplikasi: `minSdk 26` (Android 8.0+), `targetSdk 35`, dibuild dengan `compileSdk 37`
- Bisa membuild project dengan `compileSdk` / `targetSdk` sampai **API 37 (Android 17)**
- Bahasa utama aplikasi hasil build: **Java dan Kotlin** (layar, logika, resource XML)

---

## Ringkasan kemampuan

| Area | Dukungan |
|---|---|
| Java | **8 sampai 25** (disarankan 17) |
| Kotlin | Ya (compiler 2.4.20, in-process) |
| Gradle Plugin (AGP) | Ya, lewat build engine Gradle (AGP 7.0 sampai 9.4) |
| Build lokal | Ya, tanpa JDK dan tanpa Gradle |
| Build Gradle + JDK | Ya, JDK **11, 17, 21, 25, 27** |
| Python | CPython 3.14.8, **arm64-v8a saja** |
| Dart | 3.13.5, **arm64-v8a saja** (tanpa Flutter dan tanpa pub) |
| C/C++ | NDK r29 (clang) di HP, **arm64-v8a saja** |
| JavaScript | **Node.js 24.x asli**, semua ABI (arm64-v8a, armeabi-v7a, x86_64, x86) |
| JSX / TSX / TS | Diubah ke JavaScript saat build (Sucrase lewat QuickJS) |
| Output | APK (tertandatangani), AAB untuk Google Play |

---

## Dua build engine

Studio Mobile memilih engine otomatis untuk setiap project.

### 1. Build lokal (default, ringan)

Semua berjalan **di dalam aplikasi**, tanpa JDK dan tanpa Gradle:

ECJ (Java) + compiler Kotlin → D8/R8 → aapt2 → zipalign → tanda tangan APK (apksig) → verifikasi.

- Cocok untuk project sederhana yang hanya memakai plugin `com.android.application`, `com.android.library`, `kotlin-android`, `java`, `java-library`, `org.jetbrains.kotlin.jvm`
- Membaca `build.gradle` / `build.gradle.kts` dengan parser sendiri (bukan menjalankan Gradle). Tulis `compileSdk`, `targetSdk`, `minSdk` sebagai angka biasa, bukan variabel
- Mendukung modul library, AAR, view binding, BuildConfig, manifest merging, dan code shrinking (R8) mengikuti `minifyEnabled`
- **Satu-satunya engine yang bisa Export AAB**

### 2. Build Gradle + JDK

Dipakai otomatis bila project:

- punya `gradlew` / `gradle-wrapper.properties` (project Android Studio)
- punya folder `buildSrc` atau `build-logic`
- memakai `apply from`, `includeBuild`, task kustom, atau plugin selain daftar di atas

Studio Mobile memilih **Gradle dan JDK sekaligus** berdasarkan kebutuhan project (versi wrapper, AGP, level Java) dan tabel kompatibilitas AGP/Gradle/JDK. Versi Gradle dari wrapper dicoba lebih dulu; versi lain hanya dipakai bila tidak bisa dijalankan.

Task yang dijalankan: `assembleDebug`.

---

## Java dan JDK

**Level bahasa Java**

- Didukung **Java 8 sampai Java 25**. Project tanpa deklarasi dibuild dengan Java 25
- Level di atas 25 (`sourceCompatibility`, `targetCompatibility`, `jvmTarget`, `jvmToolchain`) **ditolak sebelum kompilasi**, begitu juga library lokal di `libs/` yang dikompilasi untuk Java lebih baru
- Level di bawah 8 hanya memberi peringatan dan dikompilasi sebagai Java 8

**JDK untuk build Gradle** (diunduh sekali, SHA-256 diverifikasi, arsitektur **arm64-v8a dan armeabi-v7a**):

| JDK | Keterangan |
|---|---|
| 11 | Untuk AGP 7.x |
| 17 | Minimum AGP 8.x dan 9.x |
| 21 | Satu-satunya versi yang sudah ditandai *verified* di `tools/gradle-jdk.json` |
| 25 | Didukung Gradle 9.1+ |
| 27 | Tersedia untuk diunduh, tetapi tabel kompatibilitas saat ini hanya sampai JDK 26, jadi belum dipasangkan dengan Gradle mana pun |

Tidak ada JDK untuk x86 dan x86_64, jadi build Gradle hanya jalan di HP ARM.

---

## Gradle dan AGP

**Versi Gradle yang tersedia** (diunduh dari `services.gradle.org`):

`7.6.6`, `8.13`, `8.14.6`, `9.5.0`, `9.8.1` (`9.8.1` wajib ada)

**Tabel kompatibilitas AGP** (`assets/build-tools/gradle-compat.json`, bisa diedit tanpa mengubah kode):

| AGP | Gradle minimum | Gradle maksimum | JDK minimum |
|---|---|---|---|
| 7.0 sampai 7.4 | 7.0.2 sampai 7.5 | di bawah 8.0 | 11 |
| 8.0 sampai 8.13 | 8.0 sampai 8.13 | di bawah 9.6 | 17 |
| 9.4 | 9.6.0 | bebas | 17 |

Catatan: baris AGP 7.x ditandai `verify: true`, belum dicocokkan dengan tabel resmi.

**JDK maksimum per Gradle:** 7.6 → JDK 19, 8.5 → 21, 8.10 → 23, 8.14 dan 9.0 → 24, 9.1 → 25, 9.4+ → 26.

---

## Bahasa lain

### Python (arm64-v8a saja)

- CPython 3.14.8. Interpreter ikut di dalam APK, standard library diunduh sekali saat pertama dipakai
- Menjalankan **satu file `.py`** dari editor, untuk script, otomasi, dan olah data
- Python **tidak dikemas ke APK** dan tidak bisa membuat tampilan aplikasi

### Dart (arm64-v8a saja)

- Dart SDK 3.13.5. `src/main/dart/main.dart` dikompilasi ke kernel dan dikemas ke APK bersama Dart VM
- Dipanggil dari Java/Kotlin lewat kelas `studio.dart.StudioDart`
- Hanya core library Dart. **Tidak ada pub package, tidak ada Flutter**

### C/C++ (arm64-v8a saja)

- NDK r29 (clang) diunduh sekali, membuild `lib<nama>.so` untuk dipanggil lewat JNI
- Mode CMake + Ninja bila ada, atau clang langsung untuk subset CMake yang dikenali
- Library 64-bit di-link dengan alignment 16 KB (syarat Google Play)
- Di HP non-arm64, project tetap bisa memakai file `.so` prebuilt di `src/main/jniLibs/<abi>/` (buat lewat GitHub Actions, ada template `native-build.yml`)

### JavaScript dan web

- `.js` dan `.mjs` dijalankan dengan **binary Node.js asli** yang ikut di APK per ABI (`libnodeexe.so`), dibuild dari [andiasriefail/node-android-build](https://github.com/andiasriefail/node-android-build)
- File `.js` diperlakukan sebagai CommonJS (`require`). Untuk ES modules pakai `.mjs`
- Cek sintaks memakai `node --check`
- `.jsx`, `.tsx`, `.ts` di `src/main/assets` diubah otomatis ke `.js` saat build (React di WebView)

---

## Dependensi dan keamanan

- Library diambil dari **Google Maven**, **Maven Central**, atau `libs/*.jar`. Library yang belum ada di cache gagal bila HP offline
- Semua unduhan komponen (NDK, JDK, Python, Dart, Gradle) memakai **HTTPS dan diverifikasi SHA-256**
- Catalog tool dipin dengan hash di dalam aplikasi
- Ekstraksi ZIP/TAR dilindungi dari zip-slip dan zip bomb
- Import ZIP berpassword (ZipCrypto dan AES) memunculkan dialog password

---

## Fitur IDE

- Editor dengan syntax highlighting dan preview (layout dan React/JSX)
- Import project dari ZIP (termasuk berpassword) atau folder; **Export Project To Zip** dengan picker bawaan Android
- Keystore: buat key sendiri atau import `.p12`; Export AAB untuk Google Play
- Debug Mode (penangkap crash di aplikasi hasil build)
- **MCP relay** agar asisten AI bisa membaca, mengedit, dan membuild project dari HP
- Build Error Logs per aplikasi: `<nama app>-erors-logs.txt`

**Struktur folder `Studio Mobile/`**

```
My Project/        semua project
My Application/    APK hasil "Save APK"
Export AAB/        AAB untuk Google Play
Local/local_libs/  library hasil unduhan
Build Error Logs/  satu log error per aplikasi
```

Butuh izin "All files access" (Android 11+) agar folder berada di penyimpanan bersama. Tanpa izin, struktur yang sama dibuat di penyimpanan privat aplikasi.

---

## Keterbatasan

- **Python, Dart, dan C/C++ hanya untuk arm64-v8a.** Tidak jalan di ARM 32-bit (armeabi-v7a), x86, dan x86_64 yang tidak bisa menjalankan kode arm64
- **Build Gradle butuh HP ARM** (JDK hanya tersedia untuk arm64-v8a dan armeabi-v7a)
- `compileSdk` / `targetSdk` di atas **API 37** tidak didukung
- **Export AAB hanya untuk build lokal**, belum untuk project yang dibuild lewat Gradle
- Satu APK per build: tanpa build variant dan product flavor kustom
- Jetpack Compose dan annotation processor tidak didukung (menurut batas bawaan aplikasi)
- Plugin Gradle sembarang hanya bisa lewat build engine Gradle; build lokal tidak menjalankannya
- Manifest merging tidak mendukung `tools:node` dan `tools:replace`
- Dart tanpa Flutter dan tanpa package pub; Python tidak dikemas ke APK
- Tidak ada unit test atau instrumented test; hanya `assembleDebug`
- Studio Mobile tidak membuat `MainActivity`, layout, atau kode contoh sendiri: project kosong tetap kosong
- Kunci debug lokal dibuat otomatis dan **tidak boleh dipakai untuk rilis**; gunakan key sendiri
- Komponen besar (NDK, JDK, Gradle, standard library Python) diunduh saat pertama dipakai dan butuh internet serta ruang penyimpanan

---

## Build Studio Mobile sendiri

Jalankan workflow **Build Studio Mobile APK** di GitHub Actions. Workflow ini mengunduh dan memverifikasi aapt2 (4 ABI), NDK clang (arm64), interpreter Python (arm64), Dart VM, dan Node.js (4 ABI), lalu mengemasnya ke `jniLibs`.

## Lisensi

Lihat berkas [LICENSE](LICENSE).
