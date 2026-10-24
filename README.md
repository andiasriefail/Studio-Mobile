# Studio Mobile

IDE Android yang berjalan **langsung di HP**: menulis kode, build APK/AAB, menjalankan script, dan menandatangani aplikasi tanpa komputer.

- Aplikasi: `minSdk 26` (Android 8.0+), `targetSdk 35`, dibuild dengan `compileSdk 37`
- Bisa membuild project dengan `compileSdk` / `targetSdk` sampai **API 37 (Android 17)**
- Bahasa utama aplikasi hasil build: **Java dan Kotlin** (layar, logika, resource XML)

## Daftar isi

1. [Ringkasan kemampuan](#ringkasan-kemampuan)
2. [Dua build engine](#dua-build-engine)
    - [1. Build lokal (default, ringan)](#1-build-lokal-default-ringan)
    - [2. Build Gradle + JDK](#2-build-gradle--jdk)
    - [Pemeriksaan sebelum build (build lokal)](#pemeriksaan-sebelum-build-build-lokal)
    - [Build berjalan di latar belakang](#build-berjalan-di-latar-belakang)
3. [Java dan JDK](#java-dan-jdk)
4. [Gradle dan AGP](#gradle-dan-agp)
5. [Bahasa lain](#bahasa-lain)
    - [Python (arm64-v8a saja)](#python-arm64-v8a-saja)
    - [Dart (arm64-v8a saja)](#dart-arm64-v8a-saja)
    - [Flutter (arm64-v8a saja, tahap awal)](#flutter-arm64-v8a-saja-tahap-awal)
    - [C/C++ (arm64-v8a saja)](#cc-arm64-v8a-saja)
    - [JavaScript dan web](#javascript-dan-web)
6. [Menjalankan kode di editor (Run)](#menjalankan-kode-di-editor-run)
7. [Membuat project baru](#membuat-project-baru)
8. [Struktur project multi-bahasa](#struktur-project-multi-bahasa)
    - [Contoh file kunci](#contoh-file-kunci)
9. [Preview (XML dan WebView)](#preview-xml-dan-webview)
    - [Mode yang dipilih otomatis](#mode-yang-dipilih-otomatis)
    - [Preview layout XML](#preview-layout-xml)
    - [Preview WebView](#preview-webview)
    - [Preview mengikuti perubahan](#preview-mengikuti-perubahan)
10. [Dependensi, Repository URL, dan JitPack](#dependensi-repository-url-dan-jitpack)
    - [Contoh: JitPack (Groovy)](#contoh-jitpack-groovy)
    - [Contoh: JitPack (Kotlin DSL)](#contoh-jitpack-kotlin-dsl)
    - [Contoh: repository URL lain](#contoh-repository-url-lain)
    - [Contoh: batasi repository dengan `content`](#contoh-batasi-repository-dengan-content)
    - [Yang tidak didukung (akan muncul sebagai peringatan di log build)](#yang-tidak-didukung-akan-muncul-sebagai-peringatan-di-log-build)
    - [Verifikasi file library (SHA-1 dan SHA-256)](#verifikasi-file-library-sha-1-dan-sha-256)
    - [Keamanan unduhan](#keamanan-unduhan)
11. [Keamanan jaringan: HTTP diblokir, hanya HTTPS](#keamanan-jaringan-http-diblokir-hanya-https)
12. [Debug Mode](#debug-mode)
    - [Cara menyalakan](#cara-menyalakan)
    - [Cara kerja](#cara-kerja)
    - [Penting sebelum membagikan aplikasi](#penting-sebelum-membagikan-aplikasi)
    - [Batasan](#batasan)
    - [Debug Mode untuk Studio Mobile sendiri](#debug-mode-untuk-studio-mobile-sendiri)
13. [Wallpaper editor (foto dan video live)](#wallpaper-editor-foto-dan-video-live)
14. [Keystore dan signing key](#keystore-dan-signing-key)
    - [Keystore bawaan (default)](#keystore-bawaan-default)
    - [Keystore sendiri (own key)](#keystore-sendiri-own-key)
    - [Perlindungan layar (FLAG_SECURE)](#perlindungan-layar-flag_secure)
    - [Export AAB dan key](#export-aab-dan-key)
    - [Tempat penyimpanan dan pencadangan](#tempat-penyimpanan-dan-pencadangan)
15. [GitHub Build dan unduh repo ZIP](#github-build-dan-unduh-repo-zip)
    - [1. Personal access token](#1-personal-access-token)
    - [2. Memilih repo](#2-memilih-repo)
    - [3. Branch dan tag](#3-branch-dan-tag)
    - [4. Workflow dan input](#4-workflow-dan-input)
    - [5. Menjalankan dan memantau](#5-menjalankan-dan-memantau)
    - [6. Unduh repo ZIP](#6-unduh-repo-zip)
    - [7. Masalah umum](#7-masalah-umum)
16. [MCP dan Relay Server](#mcp-dan-relay-server)
    - [1. Jalankan relay](#1-jalankan-relay)
    - [2. Variabel lingkungan relay](#2-variabel-lingkungan-relay)
    - [3. Hubungkan HP](#3-hubungkan-hp)
    - [4. Hubungkan AI](#4-hubungkan-ai)
    - [5. Tool yang tersedia untuk AI](#5-tool-yang-tersedia-untuk-ai)
    - [6. Contoh perintah ke AI](#6-contoh-perintah-ke-ai)
    - [7. Keamanan](#7-keamanan)
    - [8. Masalah umum](#8-masalah-umum)
17. [Fitur IDE](#fitur-ide)
18. [Keterbatasan](#keterbatasan)
19. [Lisensi](#lisensi)

**Panduan cepat: mau melakukan apa?**

| Kebutuhan | Baca bagian |
|---|---|
| Membuat project baru dari nol | [Membuat project baru](#membuat-project-baru) |
| Membuild APK di HP | [Dua build engine](#dua-build-engine) |
| Build gagal, mencari penyebabnya | [Pemeriksaan sebelum build](#pemeriksaan-sebelum-build-build-lokal), [Build Error Logs](#fitur-ide) |
| Memilih versi Java, Gradle, atau AGP | [Java dan JDK](#java-dan-jdk), [Gradle dan AGP](#gradle-dan-agp) |
| Menjalankan satu file Python, JS, Dart, Java, atau Kotlin | [Menjalankan kode di editor (Run)](#menjalankan-kode-di-editor-run) |
| Memakai library dari JitPack atau repository lain | [Dependensi, Repository URL, dan JitPack](#dependensi-repository-url-dan-jitpack) |
| Menambah SHA-256 pin untuk library | [Verifikasi file library](#verifikasi-file-library-sha-1-dan-sha-256) |
| Melihat crash aplikasi hasil build | [Debug Mode](#debug-mode) |
| Membuat dan memasang token GitHub | [GitHub: Personal access token](#1-personal-access-token) |
| Memilih repo sendiri dari daftar | [GitHub: Memilih repo](#2-memilih-repo) |
| Memilih branch atau tag | [GitHub: Branch dan tag](#3-branch-dan-tag) |
| Menjalankan workflow GitHub Actions | [GitHub: Workflow dan input](#4-workflow-dan-input), [Menjalankan dan memantau](#5-menjalankan-dan-memantau) |
| Membaca log dan error build GitHub | [GitHub: Log](#5-menjalankan-dan-memantau) |
| Mengunduh repo sebagai ZIP ke folder Download | [GitHub: Unduh repo ZIP](#6-unduh-repo-zip) |
| Menghubungkan AI (Claude, ChatGPT, Gemini) | [MCP dan Relay Server](#mcp-dan-relay-server) |
| Menandatangani aplikasi dengan key sendiri | [Keystore dan signing key](#keystore-dan-signing-key) |
| Mencadangkan atau mengimpor keystore (`.p12`) | [Tempat penyimpanan dan pencadangan](#tempat-penyimpanan-dan-pencadangan) |
| Memahami proteksi screenshot di halaman Keystore | [Perlindungan layar (FLAG_SECURE)](#perlindungan-layar-flag_secure) |
| Export AAB untuk Google Play | [Export AAB dan key](#export-aab-dan-key) |
| Mengganti wallpaper editor | [Wallpaper editor](#wallpaper-editor-foto-dan-video-live) |
| Mengetahui batas dan hal yang belum didukung | [Keterbatasan](#keterbatasan) |


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
| Dart | 3.13.5, **arm64-v8a saja** (Dart murni, tanpa pub) |
| Flutter | SDK 3.47.7, **arm64-v8a saja**. Tahap awal: SDK dipasang dan dicek di HP, **APK Flutter belum bisa dibuat** |
| C/C++ | NDK r29 (clang) di HP, **arm64-v8a saja** |
| JavaScript | **Node.js 24.x asli**, semua ABI (arm64-v8a, armeabi-v7a, x86_64, x86) |
| JSX / TSX / TS | Diubah ke JavaScript saat build (Sucrase lewat QuickJS) |
| Run dari editor | Python, JavaScript, Dart, Java, dan Kotlin (satu file, output langsung) |
| Preview | Layout XML dan halaman HTML/WebView, tanpa build APK |
| Repository | Google, Maven Central, **JitPack**, dan URL Maven HTTPS lain |
| Annotation processor | Dagger, AutoValue, Glide, ButterKnife, MapStruct, dan sejenisnya, **untuk sumber Java** (tanpa kapt) |
| Pemeriksaan crash | Build lokal berhenti bila ada crash yang pasti terjadi, sebelum APK dibuat |
| Project baru | Wizard 4 langkah (ikon, identitas, versi, versi Android) dengan file awal siap build |
| Wallpaper editor | Foto, atau **video live maksimal 60 detik**, di belakang code editor |
| GitHub Build | Pilih repo dari daftar (atau tempel URL), pilih branch/tag dan workflow, jalankan di GitHub Actions, lihat log, unduh hasilnya, dan pasang APK |
| Unduh repo ZIP | Repo sendiri atau repo orang lain diunduh langsung ke folder **Download** |
| Debug Mode | Penangkap crash di aplikasi hasil build, dan untuk Studio Mobile sendiri |
| MCP | Relay milik sendiri, untuk Claude, ChatGPT, dan Gemini CLI |
| Keystore | Satu key per project: key bawaan untuk uji coba, atau key sendiri (buat baru atau import `.p12`), halaman Keystore dilindungi `FLAG_SECURE` dari screenshot dan rekam layar |
| Output | APK (tertandatangani), AAB untuk Google Play |

---

## Dua build engine

Studio Mobile memilih engine otomatis untuk setiap project. Project **Flutter** dikenali lebih dulu dan memakai jalur terpisah (lihat [Flutter](#flutter-arm64-v8a-saja-tahap-awal)).

### 1. Build lokal (default, ringan)

Semua berjalan **di dalam aplikasi**, tanpa JDK dan tanpa Gradle:

ECJ (Java) + compiler Kotlin → D8/R8 → aapt2 → zipalign → tanda tangan APK (apksig) → verifikasi.

- Cocok untuk project sederhana yang hanya memakai plugin `com.android.application`, `com.android.library`, `kotlin-android`, `java`, `java-library`, `org.jetbrains.kotlin.jvm`
- Membaca `build.gradle` / `build.gradle.kts` dengan parser sendiri (bukan menjalankan Gradle). Tulis `compileSdk`, `targetSdk`, `minSdk` sebagai angka biasa, bukan variabel
- Mendukung modul library, AAR, view binding, BuildConfig, manifest merging, dan code shrinking (R8) mengikuti `minifyEnabled`
- Menjalankan **annotation processor** Java di HP (Dagger, AutoValue, Glide, ButterKnife, MapStruct, ...): processor diubah ke DEX, dimuat lewat `DexClassLoader`, lalu dijalankan oleh ECJ dengan `-proc:only`. Kode hasil generate ikut dikompilasi. Hanya sumber Java yang dibaca; Kotlin yang butuh kapt tidak didukung
- Menjalankan [pemeriksaan sebelum build](#pemeriksaan-sebelum-build-build-lokal)
- **Export AAB** didukung: bundle dipaketkan langsung oleh Studio Mobile (tanpa bundletool) dan ditandatangani dengan key project

### 2. Build Gradle + JDK

Dipakai otomatis bila project:

- punya `gradlew` / `gradle-wrapper.properties` (project Android Studio)
- punya folder `buildSrc` atau `build-logic`
- memakai `apply from`, `includeBuild`, task kustom, atau plugin selain daftar di atas

Studio Mobile memilih **Gradle dan JDK sekaligus** berdasarkan kebutuhan project (versi wrapper, AGP, level Java) dan tabel kompatibilitas AGP/Gradle/JDK. Versi Gradle dari wrapper dicoba lebih dulu; versi lain hanya dipakai bila tidak bisa dijalankan.

Task yang dijalankan: `assembleDebug`. Saat **Export AAB**, task `bundleRelease` ikut dijalankan pada proses Gradle yang sama:

- Yang dibuild adalah varian **release**, bukan debug, karena Google Play menolak bundle yang debuggable. Build type `release` dari file Gradle project dipakai apa adanya (minify, resource, dan signing)
- Key yang dipilih di halaman **Keystore** diteruskan ke Gradle lewat properti signing sementara (sama seperti *Generate Signed Bundle* di Android Studio)
- Bila tidak ada key yang dipilih **dan** `build.gradle` tidak punya `signingConfig` untuk release, bundle tidak ditandatangani dan Google Play akan menolaknya; log memberi peringatan
- APK debug tetap dibuild pada run yang sama, dan hasil bundle disimpan di `Studio Mobile/Export AAB/<nama app>.aab`
- Bila Gradle selesai tetapi tidak ada file `.aab` di `<modul>/build/outputs/bundle/release`, build dinyatakan gagal. Pastikan modul aplikasi memakai plugin `com.android.application`

### Pemeriksaan sebelum build (build lokal)

Setelah kompilasi, build lokal mencari masalah yang **lolos kompilasi tetapi pasti membuat aplikasi crash**, supaya APK rusak tidak pernah dibuat. Hanya kasus yang pasti yang dilaporkan, tanpa menebak. Build **dihentikan** dengan pesan *"App would crash at runtime; build stopped, no APK was generated."* bila:

- compiler melaporkan *Null pointer access* yang pasti terjadi (bentuk *Potential null pointer access* diabaikan)
- `<activity>`, `<service>`, `<receiver>`, atau `<provider>` di manifest menunjuk class yang tidak ada di kode hasil kompilasi
- sebuah Activity dipanggil lewat `Intent` tetapi belum dideklarasikan di `AndroidManifest.xml`
- sebuah komponen punya `intent-filter` tetapi tanpa `android:exported`, padahal `targetSdk` 31 ke atas mewajibkannya (APK tidak bisa dipasang)

Satu pemeriksaan hanya **peringatan** di log dan tidak menghentikan build: kode memakai kelas jaringan (`HttpURLConnection`, `Socket`, ...) tetapi manifest tidak punya izin `INTERNET`.

### Build berjalan di latar belakang

Build dijalankan di sebuah *foreground service*, jadi tetap lanjut walau aplikasi ditinggalkan atau layar mati. Selama build tampil notifikasi berjalan berisi langkah saat ini, bar progres, waktu berjalan, dan tombol **Stop**; saat selesai diganti notifikasi hasil. Aplikasi meminta sekali agar dikecualikan dari penghematan baterai, dan pilihannya ada di dialog sistem.

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
- Hanya core library Dart. **Tidak ada pub package**. Untuk project Flutter lihat bagian berikutnya

### Flutter (arm64-v8a saja, tahap awal)

- Project dianggap Flutter bila punya `pubspec.yaml` dengan dependensi `flutter: sdk: flutter` **dan** folder `android/` berisi file Gradle. Project Dart murni (tanpa dependensi Flutter) tetap lewat jalur Dart. Bila `android/` belum ada, buat di komputer dengan `flutter create --platforms=android .` lalu pindahkan project lagi
- Hanya HP **arm64-v8a**. Di HP lain build berhenti dengan pesan bahwa Flutter butuh HP arm64
- Pertama kali dipakai, Flutter SDK 3.47.7 (arsip sekitar 550 MB, butuh sekitar 2 GB ruang kosong) diunduh sekali dan SHA-256-nya diverifikasi. Tiga executable (`dart`, `gen_snapshot`, `impellerc`) ikut di dalam APK sebagai `libflutterdart.so`, `libgensnapshot.so`, dan `libimpellerc.so`, karena Android 10+ hanya mengizinkan eksekusi dari folder native library aplikasi
- **Status sekarang:** build memasang SDK lalu mengecek apakah tool Flutter bisa berjalan di HP. Hasil cek ada di Build Logs pada baris `[flutter-check]`. **Langkah pembuatan APK belum tersedia**, jadi build project Flutter masih berakhir dengan status gagal beserta penjelasan ini

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

## Menjalankan kode di editor (Run)

Studio Mobile bisa **menjalankan satu file langsung di HP**, tanpa membuild APK. Hasilnya tampil live di jendela output yang punya tombol **Stop**.

**Cara:** di pohon file editor, tekan tombol **⋮** di samping nama file → pilih **Run with ...**. Simpan file lebih dulu; file yang belum disimpan akan ditolak dengan pesan *"Save the file before running it"*.

| File | Menu | Dijalankan dengan | Batas waktu | Catatan |
|---|---|---|---|---|
| `.py` | Run with Python | CPython 3.14.8 | 120 detik | **arm64-v8a saja**. Run pertama mengunduh standard library sekali |
| `.js`, `.mjs` | Run with JavaScript | Node.js 24.x asli (`libnodeexe.so`) | 120 detik | Semua ABI. `.js` = CommonJS, `.mjs` = ES module |
| `.dart` | Run with Dart | Dart VM 3.13.5 | 180 detik | **arm64-v8a saja**. Hanya core library, tanpa pub dan tanpa Flutter |
| `.java` | Run with Java | ECJ → D8 → `main()` di proses terpisah | 120 detik | File harus punya `public static void main(String[] args)` |
| `.kt` | Run with Kotlin | Compiler Kotlin → D8 → `main()` di proses terpisah | 120 detik | File harus punya fungsi `main` |

Aturan umum:

- **Satu Run pada satu waktu.** Bila Run kedua dimulai saat yang pertama masih jalan, yang kedua langsung dibatalkan. Tekan **Stop** pada yang pertama untuk menjalankan lagi
- Bila melewati batas waktu, proses dihentikan dan jendela output menulis `[timed out after 120 s]`
- Saat selesai, output menulis `[process finished with exit code N]`
- **C/C++ tidak punya tombol Run.** Kode C/C++ dibuild menjadi `lib<nama>.so` bersama aplikasi dan dipanggil lewat JNI (lihat contoh di bawah)
- File lain (misalnya `.lua`, `.rs`, `.sh`) hanya bisa diedit dengan syntax highlighting, tanpa menu Run
- Python, Dart, dan Node.js yang dijalankan dari editor **adalah mode script**. Hanya `src/main/dart/main.dart` yang ikut dikemas ke APK, dan JavaScript ikut APK lewat `assets` (WebView). Python tidak dikemas ke APK

**Contoh file untuk dicoba**

`hello.py`

```python
import sys
print("Halo dari Python", sys.version.split()[0])
```

`hello.js`

```javascript
console.log("Halo dari Node.js", process.version);
```

`main.dart`

```dart
void main() {
  print('Halo dari Dart');
}
```

`Hello.java`

```java
public class Hello {
    public static void main(String[] args) {
        System.out.println("Halo dari Java");
    }
}
```

`hello.kt`

```kotlin
fun main() {
    println("Halo dari Kotlin")
}
```

Contoh output di jendela Run untuk `hello.py`:

```
$ python hello.py
Halo dari Python 3.14.8

[process finished with exit code 0]
```

---

## Membuat project baru

Tombol buat project di Home membuka wizard **4 langkah**:

| Langkah | Isi |
|---|---|
| 1. App icon | Pilih gambar; dipotong persegi dari tengah. Ukuran ikon mdpi, hdpi, xhdpi, dan xxhdpi bisa diatur 16 sampai 512 px (bawaan 48, 72, 96, 144; xxxhdpi dihitung otomatis) |
| 2. Identity | Nama project, nama aplikasi, dan package. Package kosong menjadi `com.example.<nama project>` |
| 3. Version | `versionCode` (bawaan 1) dan `versionName` (bawaan `1.0`) |
| 4. Android versions | Min SDK dan Target SDK, API 21 sampai 37. Bawaan 23 dan 35. Min SDK tidak bisa melebihi Target SDK. Ringkasan tampil di bagian bawah |

Semua pilihan wizard ditulis ke `app/build.gradle` apa adanya (`minSdk`, `targetSdk`, `versionCode`, `versionName`), sehingga daftar project, editor, dan build membaca nilai yang sama. `compileSdk` bernilai 37, atau lebih tinggi bila Target SDK lebih tinggi.

**File yang dibuat** (file yang sudah ada tidak pernah ditimpa; bila ada langkah yang gagal, semua yang sudah ditulis dihapus lagi):

```
settings.gradle
build.gradle
gradle/libs.versions.toml        (AGP 8.13.1)
app/build.gradle                 (Groovy DSL, Java 17)
app/proguard-rules.pro
app/src/main/AndroidManifest.xml (tanpa atribut package; namespace ada di build.gradle)
app/src/main/java/<package>/MainActivity.java
app/src/main/res/values/strings.xml
app/src/main/res/values/themes.xml
app/src/main/res/mipmap-*/ic_launcher.png   (bila ikon dipilih)
```

Tidak dibuat: `gradlew` dan `gradle/wrapper`, `gradle.properties`, `.gitignore`, `local.properties`. Project baru dibuild oleh **build lokal**.

Bila ikon dipilih, ikon toko **512 x 512 px** (PNG, siap untuk Google Play Console) juga disimpan di `Studio Mobile/Icon Google Play Console/<nama project>/ic_launcher-playstore.png`. Folder itu selalu dibuat, dan kosong bila tidak ada ikon.

---

## Struktur project multi-bahasa

Satu project bisa memuat **Java, Kotlin, XML, C/C++, Dart, JavaScript/JSX, dan Python sekaligus**. Berikut contoh `My Project/` yang menggabungkan semuanya:

```
My Project/
├── settings.gradle
├── build.gradle
├── gradle/
│   └── checksums.properties            # (opsional) pin SHA-256 library
├── scripts/                            # (bebas) script yang dijalankan dari editor
│   ├── hello.py                        # Python: Run with Python, TIDAK ikut APK
│   └── tool.js                         # Node.js: Run with JavaScript
└── app/
    ├── build.gradle
    ├── libs/                           # (opsional) .jar lokal
    └── src/main/
        ├── AndroidManifest.xml
        ├── java/com/contoh/app/
        │   ├── MainActivity.java       # Java: layar dan logika
        │   └── NativeBridge.java       # Java: deklarasi fungsi JNI
        ├── kotlin/com/contoh/app/
        │   └── Helper.kt               # Kotlin: berdampingan dengan Java
        ├── res/
        │   ├── layout/activity_main.xml    # XML: tampilan (bisa di-Preview)
        │   └── values/strings.xml
        ├── assets/                     # Web: dimuat di WebView
        │   ├── index.html
        │   ├── App.jsx                 # diubah otomatis ke App.js saat build
        │   └── style.css
        ├── cpp/                        # C/C++: NDK r29, arm64-v8a
        │   ├── CMakeLists.txt
        │   └── native-lib.cpp
        ├── dart/
        │   └── main.dart               # Dart: titik masuk, dikemas ke APK
        └── jniLibs/                    # (opsional) .so prebuilt, per ABI
            └── arm64-v8a/
```

Peran tiap bagian:

| Folder | Bahasa | Masuk APK? | Cara dipakai |
|---|---|---|---|
| `src/main/java`, `src/main/kotlin` | Java, Kotlin | Ya | Logika aplikasi |
| `src/main/res` | XML | Ya | Tampilan; bisa di-Preview |
| `src/main/assets` | HTML, CSS, JS, JSX/TSX/TS | Ya | Dimuat di `WebView`; JSX/TSX/TS diubah ke JS |
| `src/main/cpp` | C/C++ | Ya (`lib<nama>.so`) | Dipanggil lewat JNI |
| `src/main/dart/main.dart` | Dart | Ya (kernel + Dart VM) | Dipanggil lewat `studio.dart.StudioDart` |
| `src/main/jniLibs/<abi>` | `.so` prebuilt | Ya | Untuk HP non-arm64 (hasil GitHub Actions) |
| `scripts/` (nama bebas) | Python, Node.js | **Tidak** | Dijalankan dari editor |

> Catatan: Python, Dart, dan C/C++ hanya untuk **arm64-v8a**. Di HP lain, bagian itu tidak bisa dibuild atau dijalankan di perangkat; pakai `.so` prebuilt di `jniLibs/<abi>/` untuk C/C++.

### Contoh file kunci

`app/build.gradle`

```groovy
plugins {
    id 'com.android.application'
    id 'org.jetbrains.kotlin.android'
}

android {
    namespace 'com.contoh.app'
    compileSdk 36            // tulis sebagai angka biasa, bukan variabel

    defaultConfig {
        applicationId 'com.contoh.app'
        minSdk 26
        targetSdk 35
        versionCode 1
        versionName '1.0'
    }

    compileOptions {
        sourceCompatibility JavaVersion.VERSION_17
        targetCompatibility JavaVersion.VERSION_17
    }
}

dependencies {
    implementation 'androidx.appcompat:appcompat:1.7.0'
}
```

`app/src/main/cpp/CMakeLists.txt`

```cmake
cmake_minimum_required(VERSION 3.22.1)
project(native-lib)

add_library(native-lib SHARED native-lib.cpp)
```

`app/src/main/cpp/native-lib.cpp`

```cpp
#include <jni.h>

extern "C" JNIEXPORT jstring JNICALL
Java_com_contoh_app_NativeBridge_halo(JNIEnv* env, jclass) {
    return env->NewStringUTF("Halo dari C++");
}
```

`app/src/main/java/com/contoh/app/NativeBridge.java`

```java
package com.contoh.app;

public final class NativeBridge {
    static { System.loadLibrary("native-lib"); }
    public static native String halo();
}
```

`app/src/main/dart/main.dart` (membaca satu baris dari Java lewat stdin dan membalas lewat stdout)

```dart
import 'dart:io';

void main() {
  stdin.listen((data) {
    final text = String.fromCharCodes(data).trim();
    print('Dart menerima: $text');
  });
}
```

`app/src/main/java/com/contoh/app/MainActivity.java` (menggabungkan layout XML, WebView, C++, Dart, dan Kotlin)

```java
package com.contoh.app;

import android.app.Activity;
import android.os.Bundle;
import android.util.Log;
import android.webkit.WebView;
import android.widget.TextView;
import java.io.IOException;
import studio.dart.StudioDart;

public class MainActivity extends Activity {
    private StudioDart dart;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);                       // XML

        TextView title = findViewById(R.id.title);
        title.setText(NativeBridge.halo());                           // C++ lewat JNI
        title.append("\n" + Helper.INSTANCE.greet("Kotlin"));         // Kotlin dari Java

        WebView web = findViewById(R.id.web);                         // Web
        web.getSettings().setJavaScriptEnabled(true);
        web.loadUrl("file:///android_asset/index.html");

        try {                                                         // Dart
            dart = StudioDart.start(this, new StudioDart.Listener() {
                @Override public void onLine(String line) { Log.d("Dart", line); }
                @Override public void onExit(int code) { Log.d("Dart", "exit " + code); }
            });
            dart.send("ping");
        } catch (IOException e) {
            Log.e("Dart", "Tidak bisa jalan: " + e.getMessage());     // mis. HP bukan arm64
        }
    }
}
```

`app/src/main/kotlin/com/contoh/app/Helper.kt`

```kotlin
package com.contoh.app

object Helper {
    fun greet(name: String) = "Halo dari $name"
}
```

`scripts/hello.py` dan `scripts/tool.js` bisa langsung dijalankan dari menu **⋮ → Run with Python / JavaScript**, tanpa build.

---

## Preview (XML dan WebView)

Preview menampilkan layar project **tanpa membuild APK**. Buka dari editor lewat tombol **Preview**. Tampilannya berupa bingkai HP dengan status bar, navigation bar, dan title bar yang mengikuti tema serta kode project.

> **Penting:** kode Java/Kotlin project **tidak dijalankan** di preview. Kode hanya *dibaca* untuk mengetahui apa yang terjadi saat sebuah view disentuh (buka Activity lain, tampilkan Toast, tutup layar). Logika, jaringan, dan database tidak aktif.

### Mode yang dipilih otomatis

| Yang dibuka | Yang tampil |
|---|---|
| `res/layout/*.xml` | **Mode Layout**: XML di-inflate dengan tema dan style milik project |
| `assets/*.html` | **Mode Web**: halaman dimuat di WebView asli |
| File lain / tidak ada file terbuka | Layout milik Activity launcher di manifest, atau halaman web di `assets` |
| Kode memanggil `loadUrl("https://...")` | Halaman HTTPS itu dimuat di WebView |

Kedua mode bisa digabung, misalnya layout yang berisi `WebView` untuk memuat `assets/index.html`.

### Preview layout XML

Hanya tahap AAPT2 (compile dan link) yang berjalan, tanpa Java, DEX, atau tanda tangan, jadi cepat.

Widget yang tidak bisa di-inflate langsung diganti versi preview yang membaca atribut `app:` dan `tools:`:

- `ConstraintLayout`: anchor parent/sibling/guideline, bias, rasio dimensi, chain. Belum ada: barrier, group, flow, posisi melingkar
- `RecyclerView`, `ListView`, `GridView`, `ViewPager` (isi contoh dari `tools:listitem`)
- `TabLayout`, `BottomNavigationView`, `FloatingActionButton`, `TextInputLayout`, `Chip`, `DrawerLayout`, `AppBarLayout`
- `MaterialButton`, `CardView`, `MaterialCardView`, serta `srcCompat` dan tint
- View custom digambar sebagai class induknya; fragment menampilkan layout yang di-inflate
- `VideoView` dan ExoPlayer/Media3 `PlayerView` memutar video asli (hanya HTTPS, `res/raw`, atau `assets`)
- Picture-in-picture ditiru bila manifest berisi `android:supportsPictureInPicture="true"` (tampilan saja, video tidak diputar)

**Contoh** `app/src/main/res/layout/activity_main.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:tools="http://schemas.android.com/tools"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:orientation="vertical"
    android:padding="16dp">

    <!-- tools:text hanya tampil di preview dan hanya mengisi yang kosong -->
    <TextView
        android:id="@+id/title"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:textSize="24sp"
        tools:text="Halo Studio Mobile" />

    <Button
        android:id="@+id/btnNext"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:text="Buka layar kedua" />
</LinearLayout>
```

**Pindah layar di preview.** Bila `MainActivity` berisi kode berikut, menyentuh tombol di preview membuka `SecondActivity`. Tombol Back di navigation bar preview kembali ke layar sebelumnya:

```java
findViewById(R.id.btnNext).setOnClickListener(v ->
        startActivity(new Intent(this, SecondActivity.class)));
```

### Preview WebView

Halaman di `assets/` dimuat di WebView asli. Perilakunya mengikuti kode project, seperti di perangkat:

- JavaScript dan DOM storage **hanya aktif bila kode menyalakannya** (`setJavaScriptEnabled(true)`, `setDomStorageEnabled(true)`)
- Bila kode memuat `file:///android_asset/...`, preview memakai skema yang sama, sehingga `fetch()` ke file lokal gagal seperti di perangkat
- Autoplay media hanya jalan bila kode memanggil `setMediaPlaybackRequiresUserGesture(false)`
- Halaman `http://` (tanpa S) **tidak dimuat**; preview menjelaskan alasannya. Pakai HTTPS
- `.jsx`, `.tsx`, `.ts` diubah ke `.js` otomatis. Import relatif (`./App`), `import './style.css'`, dan `import data from './data.json'` ikut dilayani

**Contoh** `app/src/main/assets/index.html`:

```html
<!doctype html>
<html>
  <head>
    <meta name="viewport" content="width=device-width, initial-scale=1">
    <title>Halo</title>
  </head>
  <body>
    <h1>Halo dari WebView</h1>
    <button onclick="document.body.style.background='#cde'">Ubah warna</button>
  </body>
</html>
```

**Contoh** `MainActivity.java` yang memuatnya:

```java
WebView web = findViewById(R.id.web);
web.getSettings().setJavaScriptEnabled(true);   // tanpa ini JS mati, di preview maupun di HP
web.loadUrl("file:///android_asset/index.html");
```

### Preview mengikuti perubahan

Preview dibangun dari isi file di disk saat itu. Bila asisten AI mengubah file lewat MCP, preview yang sedang terbuka ikut diperbarui. Tombol Refresh memuat ulang manual.

---

## Dependensi, Repository URL, dan JitPack

Library diambil dari repository Maven yang **dideklarasikan di project**, ditambah **Google Maven** dan **Maven Central** sebagai cadangan (project yang tidak menuliskannya tetap jalan), atau dari `libs/*.jar`. Library yang belum ada di cache gagal bila HP offline.

Studio Mobile membaca blok `repositories { }` dari `settings.gradle(.kts)`, `build.gradle(.kts)` di root, lalu tiap modul, sesuai urutan itu.

### Contoh: JitPack (Groovy)

`settings.gradle`:

```groovy
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven { url 'https://jitpack.io' }
    }
}
```

`app/build.gradle`:

```groovy
dependencies {
    // format JitPack: com.github.<user>:<repo>:<tag atau commit>
    implementation 'com.github.bumptech:glide:v4.16.0'
}
```

### Contoh: JitPack (Kotlin DSL)

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }
}

// app/build.gradle.kts
dependencies {
    implementation("com.github.bumptech:glide:v4.16.0")
}
```

### Contoh: repository URL lain

Semua bentuk di bawah ini dikenali:

```groovy
maven { url 'https://repo.example.com/releases' }
maven { url = uri("https://repo.example.com/releases") }
maven { setUrl("https://repo.example.com/releases") }
maven("https://repo.example.com/releases")
maven(url = "https://repo.example.com/releases")
```

### Contoh: batasi repository dengan `content`

Supaya JitPack hanya dipakai untuk grup tertentu (lebih cepat dan lebih aman):

```groovy
repositories {
    google()
    mavenCentral()
    maven {
        url 'https://jitpack.io'
        content {
            includeGroup 'com.github.user'
            includeGroupAndSubgroups 'com.github.other'
        }
    }
}
```

Filter yang dikenali: `includeGroup`, `includeGroupAndSubgroups`, `includeGroupByRegex`, `includeModule`, bentuk `exclude...` yang sepadan, dan `exclusiveContent { forRepository { ... } filter { ... } }`.

### Yang tidak didukung (akan muncul sebagai peringatan di log build)

| Deklarasi | Perlakuan |
|---|---|
| `http://...` | **Diblokir demi keamanan**, dilewati. Hanya **HTTPS** ([alasannya](#keamanan-jaringan-http-diblokir-hanya-https)) |
| Alamat dari variabel, misalnya `url "$repoUrl"` | Dilewati. Tulis alamat sebagai teks biasa |
| `credentials { ... }` | Repository tetap dicoba **tanpa** kredensial, jadi hanya file publik yang bisa diunduh |
| `flatDir`, `ivy` | Dilewati (bukan repository Maven) |
| `mavenLocal()` | Dilewati (tidak ada di HP) |
| Versi `SNAPSHOT` | Tidak didukung, pakai versi rilis |
| `oss.sonatype.org` | Sudah dipensiunkan Sonatype (2025). Pakai Maven Central |
| `jcenter()` | Sudah ditutup. Dipakai hanya bila repository sebelumnya tidak punya file itu |

Catatan: JitPack harus **dideklarasikan** di project, tidak aktif otomatis seperti Google dan Maven Central. Build pertama sebuah library JitPack bisa lama karena JitPack membuild dari sumbernya di sisi server.

### Verifikasi file library (SHA-1 dan SHA-256)

Setiap file library yang diunduh diperiksa sebelum dipakai:

1. **SHA-1 dari repository.** File dibandingkan dengan `.sha1` yang diterbitkan repository di sebelahnya. Tidak cocok berarti file **tidak dipakai**.
2. **Tanpa `.sha1` dan tanpa pin, file ditolak.** Studio Mobile menolak memakai file yang tidak bisa diverifikasi (pesan *"File cannot be verified"*).
3. **Pin SHA-256 buatan Anda (disarankan).** SHA-1 dari server berasal dari server yang sama dengan filenya, jadi tidak membuktikan bahwa itu file yang Anda maksud. Pin SHA-256 berasal dari *luar* server (misalnya halaman rilis) dan **menentukan sendiri**: bila ada pin, `.sha1` server tidak diperlukan dan file yang tidak cocok tidak pernah dipakai, dari repository mana pun.

Pin ditulis di `gradle/checksums.properties` di root project, satu baris per library, format `group:artifact:version=SHA-256`:

```properties
# gradle/checksums.properties
# SHA-256 dari file utama library (.jar, atau .aar untuk library Android).
# Salin dari halaman rilis. Nilai di bawah hanya contoh bentuk, BUKAN hash asli.

# Library dari JitPack
com.github.User:Repo:1.0.0=9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08

# Awalan "sha256:" boleh dipakai
com.github.Other:Lib:2.3.1=sha256:9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08
```

Aturan file pin:

| Aturan | Keterangan |
|---|---|
| Baris | `group:artifact:version=nilai`, tepat 3 bagian sebelum `=` |
| Nilai | Tepat **64 karakter heksadesimal** (huruf besar atau kecil, otomatis dijadikan huruf kecil) |
| Komentar | Diawali `#`, boleh di baris sendiri atau di akhir baris |
| Duplikat | Library yang sama dengan dua nilai berbeda ditolak |
| Salah format | Build berhenti dengan pesan baris mana yang salah |
| Cache | Pin yang ditambahkan setelah file ada di cache tetap diperiksa; file cache yang tidak cocok dihapus |

Build yang memakai pin menandai library itu dengan `[SHA-256 pinned]` di log.

**Cara mendapatkan SHA-256**

- Dari halaman rilis library (cara terbaik, karena nilainya datang dari sumber lain)
- Hitung sendiri dari file yang Anda unduh dan percayai:

```bash
# Linux / Termux
sha256sum library-1.0.0.aar

# macOS
shasum -a 256 library-1.0.0.aar

# Windows (PowerShell / cmd)
certutil -hashfile library-1.0.0.aar SHA256
```

- Atau biarkan Studio Mobile menunjukkannya. Bila library belum dipin dan repository tidak menerbitkan `.sha1`, build gagal dan log memuat baris siap tempel:

```
Library: com.github.User:Repo:1.0.0
File: Repo-1.0.0.aar
From: https://jitpack.io/

This repository publishes no checksum for the file, and gradle/checksums.properties has no SHA-256 for it.

Received SHA-256:
9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08

Compare it with the SHA-256 on the release page. If it is the same, add this line to gradle/checksums.properties and build again:
com.github.User:Repo:1.0.0=9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08
```

> **Jangan menempel baris itu begitu saja.** Bandingkan dulu nilainya dengan SHA-256 di halaman rilis. Bila berbeda, file mungkin sudah diubah dan **jangan dipakai**.

Contoh kegagalan saat pin tidak cocok:

```
SHA-256 of Repo-1.0.0.aar (com.github.User:Repo:1.0.0) from https://jitpack.io/ does not match gradle/checksums.properties.
Expected <nilai di pin>, received <nilai file yang diunduh>. The file was not used.
```

Bila library belum dipin tetapi `.sha1` server ada dan cocok, build tetap jalan dan log memberi **peringatan** berisi SHA-256 serta baris yang bisa ditambahkan ke `gradle/checksums.properties`, karena SHA-1 dari server saja tidak membuktikan bahwa itu file yang Anda harapkan.

Pin ini dibaca oleh resolver **build lokal**. Build Gradle + JDK memakai Gradle sungguhan dengan mekanisme verifikasinya sendiri.

### Keamanan unduhan

- Semua unduhan komponen (NDK, JDK, Python, Dart, Gradle) memakai **HTTPS dan diverifikasi SHA-256**
- Catalog tool dipin dengan hash di dalam aplikasi (contoh: `tools/dart-sdk.json` memuat `"sha256": "a467fe1d..."` untuk Dart SDK)
- Ekstraksi ZIP/TAR dilindungi dari zip-slip dan zip bomb
- Import ZIP berpassword (ZipCrypto dan AES) memunculkan dialog password

---

## Keamanan jaringan: HTTP diblokir, hanya HTTPS

Studio Mobile **tidak mengizinkan HTTP biasa (`http://`)**. Semua koneksi harus memakai **HTTPS (`https://`)**. Ini disengaja demi keamanan: lewat HTTP, pihak di tengah jaringan (Wi-Fi publik, router, penyedia internet) bisa membaca atau **mengubah** file yang lewat. Untuk IDE yang membuat aplikasi, library yang diubah di jalan bisa menyisipkan kode berbahaya ke APK Anda.

Di mana aturan ini berlaku:

| Bagian | Perilaku bila `http://` |
|---|---|
| Repository Maven di `build.gradle` / `settings.gradle` | **Dilewati** dengan peringatan *"is not HTTPS; only HTTPS repositories are supported. Skipped."* |
| Redirect dari repository | Hanya diikuti bila tujuannya **HTTPS**, maksimal 3 kali |
| Unduhan komponen (NDK, JDK, Python, Dart, Gradle) | Hanya HTTPS, plus verifikasi SHA-256 |
| Relay URL di layar MCP | **Ditolak** (*"Enter a valid https:// relay address"*) |
| Halaman yang dimuat di Preview (`loadUrl("http://...")`) | **Tidak dimuat**; preview menjelaskan alasannya |
| Video di Preview | Hanya HTTPS |
| Aplikasi Studio Mobile sendiri | `usesCleartextTraffic="false"` di manifest |

Pemeriksaan tambahan pada koneksi HTTPS:

- Nama host dikirim sebagai SNI dan **sertifikat server harus valid untuk nama host itu**. Sertifikat yang hanya cocok untuk alamat IP atau nama lain ditolak
- DNS diselesaikan lewat Google DNS lalu Cloudflare DNS, bukan DNS bawaan HP. Bila tidak ada jawaban DNS sama sekali, unduhan berhenti

**Contoh perbaikan**

```groovy
// Diblokir: http
maven { url 'http://repo.example.com/releases' }

// Benar: https
maven { url 'https://repo.example.com/releases' }
```

Bila server repository Anda hanya menyediakan HTTP, pilihannya: pindahkan ke HTTPS, atau unduh file `.jar` / `.aar` dari sumber tepercaya lalu taruh di `app/libs/`. Bandingkan SHA-256 file itu dengan yang diterbitkan sumbernya sebelum memakainya.

Catatan: aturan ini berlaku untuk Studio Mobile. Untuk aplikasi buatan Anda sendiri, izin lalu lintas HTTP diatur di manifest dan kode aplikasi itu.

---

## Debug Mode

Debug Mode menangkap **crash aplikasi hasil build** dan menampilkannya langsung di layar, sehingga aplikasi tidak menutup diam-diam. Log bisa disalin lalu dikirim ke asisten AI.

### Cara menyalakan

**Dari aplikasi:** buka project di editor → menu **Debug Mode** → nyalakan **Enable Debug Mode**.

**Dari AI lewat MCP:** panggil tool `set_debug_mode`.

```json
{ "name": "set_debug_mode", "arguments": { "enabled": true } }
```

Tanpa argumen `enabled`, tool hanya melaporkan status saat ini. Bawaannya **mati**. Pengaturan ini berlaku di aplikasi Studio Mobile, bukan per project, dan tidak bisa diubah bila MCP dalam mode read-only.

### Cara kerja

1. Saat build, Studio Mobile menyalin project ke folder build sementara.
2. Di **salinan itu saja** (file project Anda tidak berubah) ditambahkan dua class dan dua entri manifest:
   - `StudioDebugProvider`: sebuah `ContentProvider` dengan authority `<applicationId>.studiodebug`. Android membuatnya sebelum `Application.onCreate()`, jadi penangkap crash terpasang lebih dulu daripada kode aplikasi.
   - `StudioCrashActivity`: halaman putih layar penuh di proses terpisah (`:studio_debug`), sehingga tetap hidup setelah proses aplikasi dimatikan.
3. Saat terjadi exception yang tidak tertangani, stack trace disimpan dan halaman **"The app crashed"** muncul.
4. Tekan **Copy log**, lalu tempel ke asisten AI. Tekan **Close** untuk menutup.

Contoh isi alur: aplikasi memanggil `Integer.parseInt("abc")` → halaman crash menampilkan `java.lang.NumberFormatException: For input string: "abc"` lengkap dengan stack trace dan baris kode penyebab.

### Penting sebelum membagikan aplikasi

> **Matikan Debug Mode sebelum APK/AAB dibagikan atau diunggah.** Dengan Debug Mode menyala, aplikasi hasil build memuat halaman crash dan log stack trace. Aplikasi untuk rilis harus dibuild dengan Debug Mode mati.

### Batasan

- Menangkap crash dari exception Java/Kotlin yang tidak tertangani. Crash native (C/C++) atau aplikasi yang membeku (ANR) mungkin tidak tertangkap. Bila log kosong, minta AI menganalisis lebih lanjut dengan Build Error Logs
- Bila `AndroidManifest.xml` tidak punya elemen `<application>` atau package name tidak bisa ditentukan, Debug Mode dilewati dan alasannya ditulis di log build
- Ini tidak sama dengan **Build Error Logs**, yaitu log kegagalan *saat build*. Debug Mode untuk crash *saat aplikasi berjalan*

### Debug Mode untuk Studio Mobile sendiri

Bagian di atas untuk aplikasi **hasil build**. Studio Mobile juga punya penangkap crash untuk **dirinya sendiri**: nyalakan atau matikan di **Settings** (bawaannya **menyala**).

- Terpasang sebelum `Application.onCreate()`, sehingga crash di mana pun ikut tertangkap: editor, compiler, unduhan dependensi, dan proses `:javarun`
- Stack trace disimpan dan ditampilkan di halaman layar penuh pada proses terpisah
- Penutupan yang tidak bisa dilihat handler Java (sistem mematikan aplikasi karena memori, crash native, ANR) dipulihkan saat aplikasi dibuka lagi lewat `ApplicationExitInfo`, **Android 11 ke atas**

---

## Wallpaper editor (foto dan video live)

Buka dari **Settings → Change Wallpaper**. Gambar atau video tampil di belakang **code editor**.

**Foto**

- Dipilih dari galeri; bila HP tidak punya aplikasi galeri, dipakai pemilih file
- Dikecilkan hingga sisi terpanjang maksimal 2048 px, rotasi EXIF diterapkan, lalu disimpan sebagai JPEG kualitas 90 di penyimpanan privat aplikasi. Wallpaper tetap jalan walau file aslinya dipindah atau izinnya hilang

**Video live**

- Tombol **Choose video**. Durasi maksimal **60 detik** (toleransi 0,5 detik) dan ukuran file maksimal 500 MB. Video lebih panjang ditolak dengan pesan *"The video is longer than 60 seconds. Choose a shorter one."*
- Diputar **tanpa suara** dan **berulang terus**; tidak mengambil audio focus, jadi musik yang sedang diputar tidak terganggu
- Hanya diputar di **code editor**. Berhenti di Home, Preview, dan Build, serta jeda saat Anda keluar dari aplikasi, lalu lanjut dari posisi terakhir saat kembali
- Frame pertama disimpan sebagai gambar diam dan dipakai sebagai pratinjau serta tampilan awal sebelum video siap

**Pengaturan**

- Slider **Darkness** 0 sampai 85% (bawaan 55%): lapisan biru malam di atas gambar supaya kode tetap terbaca di atas gambar yang terang
- Tombol **Remove** menghapus wallpaper. Hanya satu wallpaper aktif: memilih foto menggantikan video, dan sebaliknya

---

## Keystore dan signing key

Setiap aplikasi harus ditandatangani sebelum bisa dipasang. Studio Mobile memakai **satu key per project**, dan key itu dipakai untuk APK dan AAB project tersebut.

### Keystore bawaan (default)

Project yang belum diberi key sendiri ditandatangani dengan **key bawaan Studio Mobile**.

- Dibuat otomatis saat aplikasi pertama kali dibuka (file `studio-mobile-debug.p12`, alias `studio-mobile-debug`, tipe PKCS12)
- Key ini **sama untuk semua pemakai Studio Mobile**, dan sandinya sudah tertanam di aplikasi, jadi siapa pun bisa menandatangani dengan key yang sama
- Cocok untuk **uji coba di HP sendiri**. **Jangan dipakai untuk rilis** ke Google Play atau dibagikan ke orang lain
- Di halaman Keystore, tipenya tampil sebagai **Studio Mobile default**
- Build Gradle untuk project tanpa key sendiri memakai signing bawaan Gradle/AGP, bukan key bawaan Studio Mobile ini

### Keystore sendiri (own key)

Key sendiri disimpan per project dan tampil di halaman Keystore sebagai **Own key**. Ada dua cara mendapatkannya: membuat baru atau mengimpor `.p12` yang sudah ada.

**Membuka halaman Keystore:** di daftar project di Home, tekan **⋮** pada project → **Keystore**.

Halaman Keystore menampilkan nama key, tipe, alias, store password dan key password (disembunyikan, tekan **Show** untuk melihat), pemilik (owner), tanggal berlaku, dan sidik jari SHA-256.

| Tombol | Fungsi |
|---|---|
| **Copy** | Menyalin detail key ke clipboard: nama, alias, kedua sandi, owner, tanggal berlaku, SHA-256, SHA-1, dan isi keystore dalam Base64. Di Android 13+ clipboard ditandai sensitif supaya tidak muncul di pratinjau keyboard |
| **Import** | Mengimpor file keystore **`.p12` (PKCS12)**. Isi dulu alias, store password, dan key password (boleh kosong bila sama dengan store password), lalu pilih file. Alias dan sandi diperiksa lebih dulu; bila salah, key tidak diganti |
| **Export** | Menyimpan key sebagai file `.p12` ke lokasi pilihanmu. Alias dan sandi diperiksa dulu sebelum file disimpan. Key bawaan diekspor sebagai `<nama project>-debug.p12` |
| **Change Key** | Membuat key baru untuk project ini (lihat di bawah) |

**Membuat key baru (Change Key)**

| Kolom | Aturan |
|---|---|
| Alias | Wajib |
| Password dan konfirmasi | Wajib, minimal **6 karakter**, dan keduanya harus sama |
| Company (CN) | Wajib |
| Organization (O) | Wajib |
| Unit (OU), City, State | Boleh kosong |
| Country | Wajib, **2 huruf** (kode negara, misalnya `ID`) |

Key yang dibuat memakai RSA 2048 bit dan berlaku **30 tahun**, sehingga memenuhi syarat masa berlaku minimal Google Play (2033).

> **Peringatan:** aplikasi yang sudah terpasang dan ditandatangani key lama **tidak bisa diperbarui** oleh versi yang ditandatangani key baru. Pengguna harus mencopot pemasangan lama lebih dulu. Untuk aplikasi yang sudah dirilis, **jangan ganti key**.

### Perlindungan layar (FLAG_SECURE)

Halaman Keystore menampilkan sandi dan isi key, jadi Studio Mobile memasang flag keamanan Android **`FLAG_SECURE`** untuk mencegah tangkapan layar dan perekaman layar:

| Bagian | Perlindungan |
|---|---|
| Halaman **Keystore** | `FLAG_SECURE` menyala begitu halaman dibuka, dan **dimatikan lagi** saat pindah ke layar lain. Layar lain (editor, build, dan lainnya) tetap bisa di-screenshot seperti biasa |
| Dialog **Import** dan **Export** key (alias dan sandi) | Dilindungi |
| Form **Change Key** (membuat key baru), termasuk saat dipanggil dari Export AAB | Dilindungi |
| Dialog **Key changed** setelah key dibuat dari Export AAB | Dilindungi |

Akibatnya: screenshot gagal atau menampilkan layar hitam, rekaman layar menampilkan layar hitam, dan pratinjau aplikasi di daftar aplikasi terbaru (*recents*) tidak menampilkan isi halaman.

Batasannya perlu kamu tahu:

- Flag ini **tidak menghalangi foto layar dengan kamera** atau orang yang melihat dari belakang bahu
- Tombol **Copy** tetap menaruh sandi dan isi keystore di clipboard. Di Android 13+ clipboard ditandai sensitif, tetapi aplikasi lain yang punya akses clipboard tetap bisa membacanya. Kosongkan clipboard setelah dipakai
- File `.p12` hasil **Export** adalah file biasa di lokasi yang kamu pilih, di luar perlindungan flag ini. Simpan di tempat aman

### Export AAB dan key

Google Play butuh key milik developer. Saat memilih **Export AAB** (menu **⋮** project):

- Bila project masih memakai key bawaan, form pembuatan key muncul lebih dulu. Setelah key dibuat, ada pilihan **Export AAB** atau **Import** key lain
- Bila project sudah punya key sendiri, build AAB langsung dimulai
- Hasilnya disimpan di `Studio Mobile/Export AAB/<nama app>.aab`

Cara key dipakai tiap engine: build lokal menandatangani dengan key project; build Gradle menerima key yang sama lewat properti signing sementara (lihat [Gradle dan AGP](#gradle-dan-agp)).

### Tempat penyimpanan dan pencadangan

- Key sendiri disimpan di penyimpanan **privat aplikasi** (`project-keys/<nama project>/`), bukan di folder `Studio Mobile/`
- Mengganti nama project ikut memindahkan key-nya
- **Menghapus project juga menghapus key-nya**, dan mencopot Studio Mobile menghapus semua key
- **Cadangkan key sendiri** dengan tombol **Export**, lalu simpan file `.p12` dan sandinya di tempat aman. Key yang hilang berarti aplikasi di Google Play tidak bisa diperbarui lagi

---

## GitHub Build dan unduh repo ZIP

Fitur ini menjalankan **GitHub Actions** dari HP: Studio Mobile membaca workflow sebuah repo, kamu memilih branch dan input, workflow dijalankan di server GitHub, lalu hasilnya (misalnya APK) diunduh ke HP. Ada juga tombol untuk **mengunduh seluruh repo sebagai ZIP**.

Semua koneksi ke GitHub memakai **HTTPS** (`api.github.com`). Token tidak pernah dikirim ke alamat selain GitHub.

### 1. Personal access token

Saat pertama membuka GitHub Build, aplikasi meminta token. Tombol **Create token** membuka halaman pembuatan token di GitHub. Token dicek ke GitHub lebih dulu; bila ditolak, pesan error tampil di dialog dan token tidak disimpan. Token yang valid disimpan **terenkripsi** (AES-256-GCM, kunci di Android Keystore) hanya di HP ini. Tombol **Delete Token** di pojok kanan atas halaman Github Build menghapus token dan kuncinya.

Dua jenis token didukung. Pilih salah satu.

| | Fine-grained token (disarankan) | Classic token |
|---|---|---|
| Halaman | `github.com/settings/personal-access-tokens` | `github.com/settings/tokens` → *Tokens (classic)* |
| Awalan token | `github_pat_...` | `ghp_...` |
| Akses repo | Pilih **All repositories** atau **Only select repositories** | Semua repo yang bisa diakses akun |
| Izin yang dibutuhkan | **Actions: Read and write** dan **Contents: Read-only** (Metadata: Read-only otomatis) | Scope **`repo`** (wajib). Scope `workflow` boleh dicentang tetapi tidak diperlukan untuk menjalankan workflow |
| Cakupan | Satu pemilik saja (satu akun **atau** satu organisasi) | Semua akun dan organisasi tempat kamu menjadi anggota atau kolaborator |
| Masa berlaku | Wajib punya tanggal kedaluwarsa | Bisa tanpa kedaluwarsa (tidak disarankan) |

Catatan penting:

- **Pilih izin seminimal mungkin.** Jangan mencentang scope seperti `delete_repo`, `admin:org`, atau `admin:enterprise`; Studio Mobile tidak memakainya. Beri tanggal kedaluwarsa dan hapus token di GitHub bila tidak dipakai lagi
- **Jangan membagikan token** ke siapa pun, termasuk ke asisten AI atau lewat tangkapan layar
- Tanpa **Contents (read)** (fine-grained), isi file workflow tidak bisa dibaca. Workflow tetap bisa dijalankan, tetapi **tanpa input**, dan halaman memberi catatan *"Inputs unavailable: the token needs Contents (read)"*
- Token ditolak (error 401) berarti salah, kedaluwarsa, atau sudah dihapus. Hapus token di aplikasi lalu masukkan yang baru
- Error 404 berarti alamat repo salah atau token tidak punya akses ke repo itu

### 2. Memilih repo

Halaman Github Build punya dua cara memilih repo.

**Daftar repo sendiri (dropdown)**

Tombol **Select repository** membuka daftar repo yang bisa diakses token, diurutkan dari yang terakhir diubah, dengan kolom pencarian. Repo private diberi tanda 🔒. Daftar dimuat otomatis di latar belakang saat halaman dibuka, dan memuat sampai **1000 repo**.

| Token | Yang muncul di daftar |
|---|---|
| Fine-grained, **All repositories** | Semua repo milik pemilik token itu |
| Fine-grained, **Only select repositories** | Hanya repo yang dipilih, misalnya satu repo saja |
| Classic | Semua repo milik sendiri, repo tempat menjadi kolaborator, dan repo organisasi |

Setelah memilih repo, muncul dua pilihan: **Build with workflow** dan **Download ZIP**. Bila daftar gagal dimuat, tombol berubah menjadi *"Could not load repositories. Tap to retry"*.

**Tempel alamat repo**

Untuk repo orang lain atau repo di luar daftar. Bentuk yang dikenali:

```
https://github.com/owner/repo
github.com/owner/repo
owner/repo
git@github.com:owner/repo.git
https://github.com/owner/repo/tree/nama-branch
```

Bila alamat memuat `/tree/<branch>`, branch itu dipakai sebagai awal. Bila tidak, dipakai branch default.

### 3. Branch dan tag

Setelah repo dibaca, halaman repo menampilkan status (**Public** atau **Private**) dan branch default. Tombol **Use workflow from** membuka dialog *Switch branches/tags*:

- Tab **Branches** dan **Tags**, dengan kolom **Find a branch or tag**
- Branch default ditandai `(default)`, pilihan aktif ditandai `✓`
- Memuat sampai 500 branch dan 500 tag
- Mengganti branch membaca ulang semua workflow **pada branch itu**, karena isi workflow dan input bisa berbeda antar branch

Workflow dijalankan **pada branch atau tag yang dipilih**.

### 4. Workflow dan input

Semua workflow repo ditampilkan sebagai kartu (nama dan path file). Sebuah workflow **bisa dijalankan** bila statusnya aktif **dan** punya pemicu manual `workflow_dispatch`. Bila tidak, kartu diberi catatan:

| Catatan | Arti |
|---|---|
| `Disabled on GitHub` | Workflow dinonaktifkan di GitHub |
| `Not found on <branch>` | File workflow tidak ada di branch itu |
| `No manual trigger (workflow_dispatch)` | Tidak punya `workflow_dispatch`. Tambahkan `on: workflow_dispatch:` di file workflow |
| `Inputs unavailable: the token needs Contents (read)` | File tidak bisa dibaca; workflow jalan tanpa input |

Input yang didefinisikan di `workflow_dispatch.inputs` tampil sebagai form: `string` dan `number` sebagai kolom teks, `boolean` sebagai kotak centang, `choice` sebagai pilihan. Input wajib diberi tanda `*`; bila kosong, aplikasi menolak menjalankan. Nilai bawaan dari file workflow diisi otomatis, dan nilai yang sudah kamu ubah dipertahankan saat berganti workflow selama masih valid.

Contoh workflow yang bisa dijalankan dari Studio Mobile:

```yaml
name: Build APK
on:
  workflow_dispatch:
    inputs:
      variant:
        description: Build variant
        type: choice
        options: [debug, release]
        default: debug

jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - run: ./gradlew assemble${{ github.event.inputs.variant == 'release' && 'Release' || 'Debug' }}
      - uses: actions/upload-artifact@v4
        with:
          name: app-apk
          path: app/build/outputs/apk/**/*.apk
```

### 5. Menjalankan dan memantau

Tombol **Run workflow** mengirim perintah ke GitHub, lalu halaman run menampilkan:

- Judul run (`#nomor`), branch, status (*Queued*, *Running*, *Succeeded*, *Failed*, *Cancelled*, *Timed out*), dan waktu berjalan
- **Steps**: daftar tahap dengan penanda `✓` berhasil, `✕` gagal, `●` berjalan, `○` menunggu
- **Log** dan tombol **Stop** (membatalkan run di GitHub) serta **Open on GitHub**
- Status diperbarui tiap 3 detik. Bila koneksi putus, aplikasi mencoba lagi sendiri. Bila terkena batas laju GitHub (403/429), jeda 20 detik
- Link **Resume last run** di halaman utama membuka kembali run terakhir, misalnya setelah aplikasi ditutup

**Log**

- Log dibaca dari job yang sedang berjalan (atau job yang gagal) dan menyimpan **5000 baris terakhir**
- Kotak log bisa digulir ke atas dan ke bawah, ada tombol **Top**, **Bottom**, dan **Full screen**. Tampilan layar penuh bisa digulir dan teksnya bisa disalin, serta ikut diperbarui selama build
- Bila run gagal, muncul kartu **Errors found in the log** yang merangkum baris error (misalnya `error:`, `FAILURE:`, `What went wrong`, `Exception`), supaya penyebab tidak tertimbun oleh log pembersihan di akhir
- Log job yang gagal disimpan utuh di `Studio Mobile/Build Error Logs/<repo>-run<nomor>.log`

**Hasil (Result)**

- Run yang berhasil menampilkan artifact-nya (nama dan ukuran). Bila hanya ada **satu** artifact, langsung diunduh otomatis; bila lebih, tekan **Download** pada yang diinginkan. Unduhan punya progres dan tombol **Cancel**
- ZIP disimpan di `Studio Mobile/Github Application Zip/<repo>-run<nomor>-<artifact>.zip`
- Bila ZIP berisi file `.apk`, muncul tombol **Install**. Android akan meminta izin *Install unknown apps* untuk Studio Mobile pada pemakaian pertama
- Artifact yang sudah kedaluwarsa di GitHub ditandai *Expired on GitHub*

### 6. Unduh repo ZIP

Mengunduh seluruh isi repo sebagai satu file ZIP, **tanpa perlu workflow**. Bisa untuk repo sendiri maupun repo orang lain (repo private butuh token yang punya akses).

Tiga tempat untuk memulainya:

| Dari mana | Cara | Branch yang diunduh |
|---|---|---|
| Halaman utama | Isi alamat repo, tekan **Download repository ZIP** | Branch dari `/tree/<branch>` bila ada, kalau tidak branch default |
| Dropdown repo sendiri | Pilih repo → **Download ZIP** | Branch default |
| Halaman repo | Tombol **Download ZIP of <branch/tag>** | Branch atau tag yang sedang dipilih |

Hasil:

- Disimpan langsung di folder **Download** HP sebagai `<repo>-<branch>.zip`. Bila nama sudah ada, Android menambahkan angka di belakangnya. Tidak butuh izin penyimpanan tambahan
- Selama mengunduh tampil dialog progres dengan tombol **Cancel**. Bila dibatalkan atau gagal, file setengah jadi dihapus otomatis
- Isi ZIP adalah satu folder induk bernama `<owner>-<repo>-<kode commit>/` berisi semua file pada branch itu
- Ini ZIP dari GitHub: **tanpa folder `.git`** (tanpa riwayat commit), submodule tidak ikut, dan file Git LFS hanya berupa penunjuk (bukan isi aslinya)
- Folder tujuannya berbeda dengan hasil build: ZIP repo masuk ke **Download**, sedangkan artifact hasil workflow masuk ke `Studio Mobile/Github Application Zip/`

Untuk memakai ZIP repo sebagai project di Studio Mobile, gunakan fitur import project dari ZIP.

### 7. Masalah umum

| Pesan / gejala | Penyebab dan solusi |
|---|---|
| `The token was rejected...` | Token salah atau kedaluwarsa. Tekan **Delete Token**, lalu masukkan token baru |
| `Not found. Check the address, or the token may not have access...` | Alamat salah, atau token tidak mencakup repo itu. Untuk fine-grained, tambahkan repo ke daftar akses token |
| Repo tidak muncul di dropdown | Token fine-grained hanya mencakup satu pemilik dan repo yang dipilih. Tempel alamat repo secara manual, atau buat token baru |
| `This repository has no GitHub Actions workflows.` | Repo belum punya workflow. Unduh sebagai ZIP, atau tambahkan file di `.github/workflows/` |
| Tombol Run redup, workflow tidak bisa dipilih | Workflow tidak punya `workflow_dispatch`, atau nonaktif. Lihat catatan di kartu |
| `The workflow was started, but its run could not be found.` | GitHub terlambat membuat run (cek 60 detik). Tekan **Open on GitHub** atau pakai **Resume last run** |
| Log tidak lengkap | Hanya 5000 baris terakhir yang ditampilkan. Buka **Open on GitHub** untuk log utuh, atau lihat file di `Build Error Logs/` bila run gagal |
| `Blocked an unsafe redirect.` | GitHub mengarahkan unduhan ke alamat non-HTTPS atau terlalu banyak pengalihan. Unduhan dibatalkan demi keamanan |

---

## MCP dan Relay Server

MCP memungkinkan asisten AI (Claude, ChatGPT, Gemini CLI) **membaca, mengedit, dan membuild project** dari HP.

HP tidak punya alamat publik, jadi ada **relay server** buatan Anda sendiri di tengah. HP yang menghubungi relay (long-poll), bukan sebaliknya:

```
AI  --POST /s/<secret>/mcp-->  RELAY  <--long-poll GET /s/<secret>/phone/poll--  HP
                                      <--POST /s/<secret>/phone/reply/<id>------  HP
```

Alamat relay **tidak ditanam di aplikasi**. Anda mengetiknya sendiri di layar MCP dan tersimpan hanya di HP.

### 1. Jalankan relay

Kode ada di folder `relay/`: satu file `server.js`, **Node.js 18+**, **tanpa dependensi**.

```bash
cd relay
# buat secret panjang dan acak: ini adalah "kunci" menuju HP Anda
export SECRET=$(openssl rand -hex 24)
echo "Secret Anda: $SECRET"

PORT=8787 node server.js
# Studio Mobile MCP relay listening on :8787
```

Cek relay hidup:

```bash
curl http://localhost:8787/health
# Studio Mobile MCP relay is running.
```

Untuk dipakai dari internet, relay **wajib di belakang HTTPS** (aplikasi menolak `http://`). Contoh paling sederhana memakai reverse proxy, misalnya Caddy (sertifikat HTTPS otomatis):

```
# Caddyfile
relay.contoh.com {
    reverse_proxy localhost:8787
}
```

Relay juga bisa dipasang di layanan hosting mana pun yang menjalankan Node 18+ dan menyediakan HTTPS. Alamat akhirnya akan seperti `https://relay.contoh.com/s/<secret>`.

### 2. Variabel lingkungan relay

| Variabel | Bawaan | Fungsi |
|---|---|---|
| `PORT` | `8787` | Port server |
| `RELAY_SECRETS` | kosong | Daftar secret yang diizinkan, dipisah koma. Kosong berarti secret apa pun sepanjang 16 sampai 128 karakter (`A-Z a-z 0-9 _ -`) diterima |
| `AI_TIMEOUT_SECONDS` | `120` | Berapa lama permintaan AI menunggu jawaban HP |
| `AI_TOKEN` | kosong | Bila diisi, setiap permintaan AI ke `/mcp` wajib membawa `Authorization: Bearer <token>` atau `X-API-Key: <token>` |
| `PHONE_TOKEN` | kosong | Bila diisi, HP wajib mengirim token yang sama |

Contoh produksi yang lebih aman:

```bash
export RELAY_SECRETS="$SECRET"
export AI_TOKEN=$(openssl rand -hex 24)
export PHONE_TOKEN=$(openssl rand -hex 24)
node server.js
```

> `AI_TOKEN` hanya cocok untuk klien yang bisa mengirim header (Claude Code, Gemini CLI). **Jangan isi `AI_TOKEN` bila relay dipakai ChatGPT**, karena form connector ChatGPT tidak punya kolom header.

Batas relay: body maksimal 8 MB, antrean maksimal 50 permintaan, HP dianggap online bila terlihat dalam 45 detik terakhir.

### 3. Hubungkan HP

1. Buka Studio Mobile → **MCP**.
2. Isi **Relay URL**, misalnya `https://relay.contoh.com/s/ISI_SECRET_ANDA`. Boleh dengan akhiran `/mcp` atau tanpa `https://`; aplikasi merapikannya. Tidak boleh `http://`, tidak boleh ada `?` atau `#`.
3. Bila relay memakai `PHONE_TOKEN`, isi kolom **Phone token**.
4. Tekan **Connect** dan tunggu status **Connected**.
5. Salin **alamat AI** (`.../mcp`) lewat tombol **Copy address**.

### 4. Hubungkan AI

Ganti `ALAMAT_AI` dengan `https://relay.contoh.com/s/ISI_SECRET_ANDA/mcp`.

**Claude (claude.ai / aplikasi desktop):** Customize → Connectors → **+** → *Add custom connector*. Nama: `Studio Mobile`. Remote MCP server URL: `ALAMAT_AI`. Kosongkan sign-in dan pengaturan lanjutan. Aktifkan connector di chat, lalu coba: *"Tampilkan daftar project Studio Mobile saya."*

**Claude Code:**

```bash
claude mcp add --transport http studio-mobile ALAMAT_AI

# bila relay memakai AI_TOKEN
claude mcp add --transport http studio-mobile ALAMAT_AI \
  --header "Authorization: Bearer TOKEN_AI_ANDA"
```

Atau lewat `.mcp.json`:

```json
{
  "mcpServers": {
    "studio-mobile": {
      "type": "http",
      "url": "ALAMAT_AI",
      "headers": { "Authorization": "Bearer TOKEN_AI_ANDA" }
    }
  }
}
```

**Gemini CLI:**

```bash
gemini mcp add --transport http --scope user studio-mobile ALAMAT_AI
```

Atau di `~/.gemini/settings.json` (pakai `httpUrl`, **bukan** `url`, karena `url` berarti SSE):

```json
{ "mcpServers": { "studio-mobile": { "httpUrl": "ALAMAT_AI" } } }
```

Ketik `/mcp` di Gemini CLI untuk memastikan `studio-mobile` terhubung.

**ChatGPT:** Settings → Apps & Connectors → Advanced settings → nyalakan *Developer mode* (butuh paket yang mendukung). Buat connector `Studio Mobile`, MCP server URL `ALAMAT_AI`, Authentication: *No authentication*, centang *I trust this application*. Di chat baru pilih **+** → More → Developer mode → Studio Mobile.

**Klien yang hanya mendukung stdio:**

```bash
npx -y mcp-remote ALAMAT_AI
```

### 5. Tool yang tersedia untuk AI

| Kelompok | Tool |
|---|---|
| Info | `get_studio_limits`, `get_toolchain_info`, `workspace_overview` |
| Baca | `list_projects`, `list_files`, `read_file`, `search_text`, `find_files`, `check_local_libs` |
| Periksa | `validate_project`, `check_syntax` |
| Build | `get_build_status`, `wait_for_build`, `get_build_log` |
| Tulis | `write_file`, `edit_file`, `multi_edit`, `replace_lines`, `insert_lines`, `append_file` |
| Kelola file | `copy_path`, `create_directory`, `move_path`, `delete_path` |
| Debug | `set_debug_mode` |

Tool kelompok **Tulis**, **Kelola file**, dan `set_debug_mode` hanya muncul bila **Read-only mode dimatikan**.

### 6. Contoh perintah ke AI

- *"Daftar semua project di Studio Mobile."*
- *"Baca `MainActivity.java` di project Kalkulator dan jelaskan alurnya."*
- *"Ubah warna tombol di `activity_main.xml` jadi biru, lalu build."*
- *"Nyalakan Debug Mode, build, dan beri tahu saya bila ada crash."*
- *"Cari semua pemakaian `Toast` di project ini."*

Saat AI mengubah file, editor dan preview yang sedang terbuka ikut diperbarui. Perubahan beruntun digabung (sinkronisasi 300 ms setelah perubahan terakhir, paling lambat 1,5 detik setelah yang pertama). Bila Anda punya edit yang belum disimpan di file yang sama, edit Anda **tidak ditimpa**.

### 7. Keamanan

- Siapa pun yang tahu Relay URL dapat menjangkau HP Anda. **Jaga kerahasiaannya** dan pakai secret panjang acak (`openssl rand -hex 24`)
- Biarkan **Read-only mode menyala** kecuali Anda memang mengizinkan AI mengubah, memindah, menghapus file, mengubah file Gradle, dan menjalankan build
- Pakai `RELAY_SECRETS`, `AI_TOKEN`, dan `PHONE_TOKEN` pada relay yang bisa diakses publik
- Selalu sajikan relay lewat HTTPS

### 8. Masalah umum

| Pesan / gejala | Penyebab dan solusi |
|---|---|
| `Studio Mobile is not connected to this relay` | HP belum Connect atau mati koneksi. Buka aplikasi → MCP → Connect |
| `The phone did not answer in time` | HP tidak membalas dalam `AI_TIMEOUT_SECONDS`. Pastikan aplikasi tidak dihentikan sistem dan internet aktif |
| `Missing or wrong AI token` / `phone token` | Token header tidak sama dengan `AI_TOKEN` / `PHONE_TOKEN` |
| `This secret is not allowed on this relay` | Secret tidak ada di `RELAY_SECRETS`, atau kurang dari 16 karakter |
| `The phone has too many pending requests` | Antrean penuh (50). Tunggu atau mulai ulang koneksi |
| Aplikasi menolak Relay URL | Harus `https://`, tanpa `?` atau `#` |

---

## Fitur IDE

- Editor dengan syntax highlighting dan **preview** (layout XML, WebView, React/JSX)
- Import project dari ZIP (termasuk berpassword) atau folder; **Export Project To Zip** dengan picker bawaan Android
- **Keystore** per project: key bawaan untuk uji coba, atau key sendiri (buat baru, import, export, copy); halaman dan dialog key dilindungi `FLAG_SECURE`; Export AAB untuk Google Play
- **Wallpaper editor**: foto atau video live (maks. 60 detik) di belakang editor
- **Wizard project baru** dengan ikon, versi, dan SDK pilihan Anda, plus ikon Google Play 512 x 512
- **Debug Mode** (penangkap crash di aplikasi hasil build, dan untuk Studio Mobile sendiri)
- Build berjalan di latar belakang dengan notifikasi progres dan tombol Stop
- **GitHub Build**: daftar repo sendiri, pilih branch/tag dan workflow, jalankan di GitHub Actions, log dengan ringkasan error, dan pasang APK hasilnya
- **Unduh repo ZIP** langsung ke folder Download, untuk repo sendiri atau repo orang lain
- **MCP relay** agar asisten AI bisa membaca, mengedit, dan membuild project dari HP
- Build Error Logs per aplikasi: `<nama app>-erors-logs.txt`

**Struktur folder `Studio Mobile/`**

```
My Project/        semua project
My Application/    APK hasil "Save APK"
Export AAB/        AAB untuk Google Play
Icon Google Play Console/  ikon toko 512 x 512 per project
Local/local_libs/  library hasil unduhan
Build Error Logs/  satu log error per aplikasi (termasuk log run GitHub yang gagal)
Github Application Zip/  artifact hasil GitHub Actions
```

ZIP repo dari fitur **Download repo ZIP** disimpan di folder **Download** milik Android, bukan di folder ini.

Butuh izin "All files access" (Android 11+) agar folder berada di penyimpanan bersama. Tanpa izin, struktur yang sama dibuat di penyimpanan privat aplikasi.

**Izin yang diminta aplikasi**

| Izin | Untuk apa |
|---|---|
| `INTERNET` | Mengunduh komponen build dan dependensi, serta MCP dan GitHub |
| `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_SPECIAL_USE`, `WAKE_LOCK` | Build tetap jalan saat layar mati |
| `POST_NOTIFICATIONS` | Notifikasi progres dan hasil build (Android 13+) |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | Build panjang tidak dimatikan sistem; ditanyakan sekali dan Anda yang memutuskan |
| `REQUEST_INSTALL_PACKAGES` | Membuka installer sistem untuk APK yang baru dibuild |
| `MANAGE_EXTERNAL_STORAGE` | Folder `Studio Mobile/` di penyimpanan bersama (Android 11+). Di Android 8 dan 9 dipakai izin penyimpanan klasik |

---

## Keterbatasan

- **Python, Dart, Flutter, dan C/C++ hanya untuk arm64-v8a.** Tidak jalan di ARM 32-bit (armeabi-v7a), x86, dan x86_64 yang tidak bisa menjalankan kode arm64
- **Build Gradle butuh HP ARM** (JDK hanya tersedia untuk arm64-v8a dan armeabi-v7a)
- `compileSdk` / `targetSdk` di atas **API 37** tidak didukung
- Export AAB lewat Gradle selalu memakai varian **release** dari file Gradle project. Tanpa key di Keystore dan tanpa `signingConfig` release, bundle tidak tertandatangani
- Satu APK per build: tanpa build variant dan product flavor kustom
- Jetpack Compose tidak didukung. Annotation processor hanya untuk sumber Java; kapt (Kotlin) tidak tersedia di HP
- Plugin Gradle sembarang hanya bisa lewat build engine Gradle; build lokal tidak menjalankannya
- Manifest merging tidak mendukung `tools:node` dan `tools:replace`
- Dart tanpa package pub; Python tidak dikemas ke APK
- **Flutter masih tahap awal**: hanya arm64-v8a, SDK dipasang dan dicek, tetapi **belum menghasilkan APK**
- Tidak ada unit test atau instrumented test; hanya `assembleDebug` (ditambah `bundleRelease` untuk Export AAB di build Gradle)
- Wizard hanya membuat file awal minimal (satu `MainActivity` berisi satu `TextView`, manifest, `strings.xml`, `themes.xml`). Tidak ada layout XML atau kode contoh lain, dan project yang diimpor tidak diubah
- Pemeriksaan risiko crash sebelum build hanya ada di **build lokal**
- Wallpaper video maksimal **60 detik** dan 500 MB, hanya diputar di code editor
- Key bawaan Studio Mobile dibuat otomatis, sama untuk semua pemakai, dan **tidak boleh dipakai untuk rilis**; gunakan key sendiri. Import key hanya mendukung format `.p12` (PKCS12). `FLAG_SECURE` tidak menghalangi foto layar dengan kamera
- Komponen besar (NDK, JDK, Gradle, standard library Python) diunduh saat pertama dipakai dan butuh internet serta ruang penyimpanan
- **Preview tidak menjalankan kode** Java/Kotlin: logika, jaringan, dan database tidak aktif; `ConstraintLayout` belum mendukung barrier, group, dan flow
- **Repository** hanya HTTPS; kredensial, variabel, `flatDir`, `mavenLocal()`, dan SNAPSHOT tidak didukung
- **Debug Mode** harus dimatikan sebelum aplikasi dibagikan
- **GitHub Build**: hanya workflow dengan `workflow_dispatch` yang bisa dijalankan; daftar repo dibatasi 1000, branch dan tag masing-masing 500; log menampilkan 5000 baris terakhir; token fine-grained hanya mencakup satu pemilik
- **Repo ZIP** tidak memuat riwayat Git (`.git`), submodule, atau isi file Git LFS

---

## Lisensi

Studio Mobile dirilis di bawah **PolyForm Noncommercial License 1.0.0** (`PolyForm-Noncommercial-1.0.0`). Ketentuan lengkapnya ada di <https://polyformproject.org/licenses/noncommercial/1.0.0>, dan ringkasan pemberitahuan ada di berkas [LICENSE](LICENSE).

| Boleh | Tidak boleh tanpa izin tertulis |
|---|---|
| Dipakai untuk keperluan pribadi, belajar, dan riset | Dijual atau dimasukkan ke produk berbayar |
| Dipakai organisasi non-profit | Dipakai perusahaan untuk kegiatan bisnis |
| Diubah, termasuk memperbaiki bug | Disebarkan sebagai bagian dari layanan atau produk komersial |
| Disebarkan ulang untuk tujuan non-komersial | |

Untuk izin pemakaian komersial, hubungi pemilik proyek.

Catatan:

- Ini lisensi **source-available**, bukan open source menurut definisi OSI, karena melarang pemakaian komersial
- Salinan yang diperoleh sebelum 2026-10-24 di bawah BSD 3-Clause tetap berlaku di bawah lisensi itu untuk salinan tersebut
- Komponen pihak ketiga yang dipakai Studio Mobile (misalnya Node.js, CPython, Dart, NDK, dan library lain) tetap memakai lisensinya masing-masing
- Cara berkontribusi dan syaratnya (dalam bahasa Inggris) ada di [CONTRIBUTING.md](CONTRIBUTING.md)
