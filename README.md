# Studio Mobile

IDE Android yang berjalan **langsung di HP**: menulis kode, build APK/AAB, menjalankan script, dan menandatangani aplikasi tanpa komputer.

- Aplikasi: `minSdk 26` (Android 8.0+), `targetSdk 35`, dibuild dengan `compileSdk 37`
- Bisa membuild project dengan `compileSdk` / `targetSdk` sampai **API 37 (Android 17)**
- Bahasa utama aplikasi hasil build: **Java dan Kotlin** (layar, logika, resource XML)

## Daftar isi

1. [Ringkasan kemampuan](#ringkasan-kemampuan)
2. [Dua build engine](#dua-build-engine)
3. [Java dan JDK](#java-dan-jdk)
4. [Gradle dan AGP](#gradle-dan-agp)
5. [Bahasa lain](#bahasa-lain)
6. [Menjalankan kode di editor (Run)](#menjalankan-kode-di-editor-run)
7. [Struktur project multi-bahasa](#struktur-project-multi-bahasa)
8. [Preview (XML dan WebView)](#preview-xml-dan-webview)
9. [Dependensi, Repository URL, dan JitPack](#dependensi-repository-url-dan-jitpack)
10. [Keamanan jaringan: HTTP diblokir, hanya HTTPS](#keamanan-jaringan-http-diblokir-hanya-https)
11. [Debug Mode](#debug-mode)
12. [MCP dan Relay Server](#mcp-dan-relay-server)
13. [Fitur IDE](#fitur-ide)
14. [Keterbatasan](#keterbatasan)
15. [Build Studio Mobile sendiri](#build-studio-mobile-sendiri)

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
| Run dari editor | Python, JavaScript, Dart, Java, dan Kotlin (satu file, output langsung) |
| Preview | Layout XML dan halaman HTML/WebView, tanpa build APK |
| Repository | Google, Maven Central, **JitPack**, dan URL Maven HTTPS lain |
| Debug Mode | Penangkap crash di aplikasi hasil build |
| MCP | Relay milik sendiri, untuk Claude, ChatGPT, dan Gemini CLI |
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

## Menjalankan kode di editor (Run)

Studio Mobile bisa **menjalankan satu file langsung di HP**, tanpa membuild APK. Hasilnya tampil live di jendela output yang punya tombol **Stop**.

**Cara:** di pohon file editor, tekan tombol **⋮** di samping nama file → pilih **Run with ...**. Simpan file lebih dulu; file yang belum disimpan akan ditolak dengan pesan *"Save the file before running it"*.

| File | Menu | Dijalankan dengan | Batas waktu | Catatan |
|---|---|---|---|---|
| `.py` | Run with Python | CPython 3.14.8 | 120 detik | **arm64-v8a saja**. Run pertama mengunduh standard library sekali |
| `.js`, `.mjs` | Run with JavaScript | Node.js 24.x asli (`libnodeexe.so`) | 120 detik | Semua ABI. `.js` = CommonJS, `.mjs` = ES module |
| `.dart` | Run with Dart | Dart VM 3.13.5 | 180 detik | **arm64-v8a saja**. Hanya core library, tanpa pub dan Flutter |
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
- Keystore: buat key sendiri atau import `.p12`; Export AAB untuk Google Play
- **Debug Mode** (penangkap crash di aplikasi hasil build)
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
- **Preview tidak menjalankan kode** Java/Kotlin: logika, jaringan, dan database tidak aktif; `ConstraintLayout` belum mendukung barrier, group, dan flow
- **Repository** hanya HTTPS; kredensial, variabel, `flatDir`, `mavenLocal()`, dan SNAPSHOT tidak didukung
- **Debug Mode** harus dimatikan sebelum aplikasi dibagikan

---

## Build Studio Mobile sendiri

Jalankan workflow **Build Studio Mobile APK** di GitHub Actions. Workflow ini mengunduh dan memverifikasi aapt2 (4 ABI), NDK clang (arm64), interpreter Python (arm64), Dart VM, dan Node.js (4 ABI), lalu mengemasnya ke `jniLibs`.

## Lisensi

Lihat berkas [LICENSE](LICENSE).
