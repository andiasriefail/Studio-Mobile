# Studio Mobile

An Android IDE that runs **directly on your phone**: write code, build APK/AAB, run scripts, and sign apps without a computer.

- App: `minSdk 26` (Android 8.0+), `targetSdk 35`, built with `compileSdk 37`
- Can build projects with `compileSdk` / `targetSdk` up to **API 37 (Android 17)**
- Main languages of the built app: **Java and Kotlin** (screens, logic, XML resources)

## Table of contents

1. [Capability summary](#capability-summary)
2. [Two build engines](#two-build-engines)
    - [1. Local build (default, lightweight)](#1-local-build-default-lightweight)
    - [2. Gradle + JDK build](#2-gradle--jdk-build)
    - [Pre-build checks (local build)](#pre-build-checks-local-build)
    - [Builds run in the background](#builds-run-in-the-background)
3. [Java and JDK](#java-and-jdk)
4. [Gradle and AGP](#gradle-and-agp)
5. [Other languages](#other-languages)
    - [Python (arm64-v8a only)](#python-arm64-v8a-only)
    - [Dart (arm64-v8a only)](#dart-arm64-v8a-only)
    - [Flutter (arm64-v8a only, early stage)](#flutter-arm64-v8a-only-early-stage)
    - [C/C++ (arm64-v8a only)](#cc-arm64-v8a-only)
    - [JavaScript and web](#javascript-and-web)
6. [Running code in the editor (Run)](#running-code-in-the-editor-run)
7. [Creating a new project](#creating-a-new-project)
8. [Multi-language project structure](#multi-language-project-structure)
    - [Key file examples](#key-file-examples)
9. [Preview (XML and WebView)](#preview-xml-and-webview)
    - [Automatically selected mode](#automatically-selected-mode)
    - [XML layout preview](#xml-layout-preview)
    - [WebView preview](#webview-preview)
    - [Preview follows changes](#preview-follows-changes)
10. [Dependencies, Repository URL, and JitPack](#dependencies-repository-url-and-jitpack)
    - [Example: JitPack (Groovy)](#example-jitpack-groovy)
    - [Example: JitPack (Kotlin DSL)](#example-jitpack-kotlin-dsl)
    - [Example: other repository URLs](#example-other-repository-urls)
    - [Example: restrict repositories with `content`](#example-restrict-repositories-with-content)
    - [Not supported (will appear as warnings in the build log)](#not-supported-will-appear-as-warnings-in-the-build-log)
    - [Library file verification (SHA-1 and SHA-256)](#library-file-verification-sha-1-and-sha-256)
    - [Download security](#download-security)
11. [Network security: HTTP blocked, HTTPS only](#network-security-http-blocked-https-only)
12. [Debug Mode](#debug-mode)
    - [How to turn it on](#how-to-turn-it-on)
    - [How it works](#how-it-works)
    - [Important before sharing the app](#important-before-sharing-the-app)
    - [Restrictions](#restrictions)
    - [Debug Mode for Studio Mobile itself](#debug-mode-for-studio-mobile-itself)
13. [Editor wallpaper (photo and live video)](#editor-wallpaper-photo-and-live-video)
14. [Keystore and signing key](#keystore-and-signing-key)
    - [Built-in keystore (default)](#built-in-keystore-default)
    - [Own keystore (own key)](#own-keystore-own-key)
    - [Screen protection (FLAG_SECURE)](#screen-protection-flag_secure)
    - [Export AAB and key](#export-aab-and-key)
    - [Storage location and backup](#storage-location-and-backup)
15. [GitHub Build and repo ZIP download](#github-build-and-repo-zip-download)
    - [1. Personal access token](#1-personal-access-token)
    - [2. Choosing a repo](#2-choosing-a-repo)
    - [3. Branch and tag](#3-branch-and-tag)
    - [4. Workflow and inputs](#4-workflow-and-inputs)
    - [5. Running and monitoring](#5-running-and-monitoring)
    - [6. Download repo ZIP](#6-download-repo-zip)
    - [7. Common problems](#7-common-problems)
16. [MCP and Relay Server](#mcp-and-relay-server)
    - [1. Run the relay](#1-run-the-relay)
    - [2. Relay environment variables](#2-relay-environment-variables)
    - [3. Connect the phone](#3-connect-the-phone)
    - [4. Connect the AI](#4-connect-the-ai)
    - [5. Tools available to the AI](#5-tools-available-to-the-ai)
    - [6. Example commands to the AI](#6-example-commands-to-the-ai)
    - [7. Security](#7-security)
    - [8. Common problems](#8-common-problems)
17. [IDE features](#ide-features)
18. [Limitations](#limitations)
19. [License](#license)

**Quick guide: what do you want to do?**

| Need | Read section |
|---|---|
| Create a new project from scratch | [Creating a new project](#creating-a-new-project) |
| Build an APK on the phone | [Two build engines](#two-build-engines) |
| Build failed, looking for the cause | [Pre-build checks](#pre-build-checks-local-build), [Build Error Logs](#ide-features) |
| Choose a Java, Gradle, or AGP version | [Java and JDK](#java-and-jdk), [Gradle and AGP](#gradle-and-agp) |
| Run a single Python, JS, Dart, Java, or Kotlin file | [Running code in the editor (Run)](#running-code-in-the-editor-run) |
| Use a library from JitPack or another repository | [Dependencies, Repository URL, and JitPack](#dependencies-repository-url-and-jitpack) |
| Add a SHA-256 pin for a library | [Library file verification](#library-file-verification-sha-1-and-sha-256) |
| See crashes of the built app | [Debug Mode](#debug-mode) |
| Create and set up a GitHub token | [GitHub: Personal access token](#1-personal-access-token) |
| Pick your own repo from a list | [GitHub: Choosing a repo](#2-choosing-a-repo) |
| Choose a branch or tag | [GitHub: Branch and tag](#3-branch-and-tag) |
| Run a GitHub Actions workflow | [GitHub: Workflow and inputs](#4-workflow-and-inputs), [Running and monitoring](#5-running-and-monitoring) |
| Read GitHub build logs and errors | [GitHub: Logs](#5-running-and-monitoring) |
| Download a repo as a ZIP to the Download folder | [GitHub: Download repo ZIP](#6-download-repo-zip) |
| Connect an AI (Claude, ChatGPT, Gemini) | [MCP and Relay Server](#mcp-and-relay-server) |
| Sign the app with your own key | [Keystore and signing key](#keystore-and-signing-key) |
| Back up or import a keystore (`.p12`) | [Storage location and backup](#storage-location-and-backup) |
| Understand screenshot protection on the Keystore page | [Screen protection (FLAG_SECURE)](#screen-protection-flag_secure) |
| Export an AAB for Google Play | [Export AAB and key](#export-aab-and-key) |
| Change the editor wallpaper | [Editor wallpaper](#editor-wallpaper-photo-and-live-video) |
| Learn the limits and what is not yet supported | [Limitations](#limitations) |


---

## Capability summary

| Area | Support |
|---|---|
| Java | **8 to 25** (17 recommended) |
| Kotlin | Yes (compiler 2.4.20, in-process) |
| Gradle Plugin (AGP) | Yes, via the Gradle build engine (AGP 7.0 to 9.4) |
| Local build | Yes, without JDK and without Gradle |
| Gradle + JDK build | Yes, JDK **11, 17, 21, 25, 27** |
| Python | CPython 3.14.8, **arm64-v8a only** |
| Dart | 3.13.5, **arm64-v8a only** (pure Dart, no pub) |
| Flutter | SDK 3.47.7, **arm64-v8a only**. Early stage: the SDK is installed and checked on the phone, **Flutter APKs cannot be built yet** |
| C/C++ | NDK r29 (clang) on the phone, **arm64-v8a only** |
| JavaScript | **Native Node.js 24.x**, all ABIs (arm64-v8a, armeabi-v7a, x86_64, x86) |
| JSX / TSX / TS | Converted to JavaScript at build time (Sucrase via QuickJS) |
| Run from editor | Python, JavaScript, Dart, Java, and Kotlin (single file, live output) |
| Preview | XML layouts and HTML/WebView pages, without building an APK |
| Repository | Google, Maven Central, **JitPack**, and other HTTPS Maven URLs |
| Annotation processor | Dagger, AutoValue, Glide, ButterKnife, MapStruct, and the like, **for Java sources** (no kapt) |
| Crash checks | The local build stops if a crash is certain to occur, before the APK is created |
| New project | 4-step wizard (icon, identity, version, Android versions) with ready-to-build starter files |
| Editor wallpaper | Photo, or **live video up to 60 seconds**, behind the code editor |
| GitHub Build | Pick a repo from a list (or paste a URL), pick a branch/tag and workflow, run on GitHub Actions, view logs, download the result, and install the APK |
| Download repo ZIP | Your own repo or someone else's repo downloaded directly to the **Download** folder |
| Debug Mode | Crash catcher in the built app, and for Studio Mobile itself |
| MCP | Your own relay, for Claude, ChatGPT, and Gemini CLI |
| Keystore | One key per project: a built-in key for testing, or your own key (create new or import `.p12`); the Keystore page is protected by `FLAG_SECURE` against screenshots and screen recording |
| Output | APK (signed), AAB for Google Play |

---

## Two build engines

Studio Mobile chooses the engine automatically for each project. **Flutter** projects are recognized first and use a separate path (see [Flutter](#flutter-arm64-v8a-only-early-stage)).

### 1. Local build (default, lightweight)

Everything runs **inside the app**, without JDK and without Gradle:

ECJ (Java) + Kotlin compiler → D8/R8 → aapt2 → zipalign → APK signing (apksig) → verification.

- Suitable for simple projects that only use the plugins `com.android.application`, `com.android.library`, `kotlin-android`, `java`, `java-library`, `org.jetbrains.kotlin.jvm`
- Reads `build.gradle` / `build.gradle.kts` with its own parser (it does not run Gradle). Write `compileSdk`, `targetSdk`, `minSdk` as plain numbers, not variables
- Supports library modules, AAR, view binding, BuildConfig, manifest merging, and code shrinking (R8) following `minifyEnabled`
- Runs Java **annotation processors** on the phone (Dagger, AutoValue, Glide, ButterKnife, MapStruct, ...): the processors are converted to DEX, loaded via `DexClassLoader`, then run by ECJ with `-proc:only`. The generated code is compiled as well. Only Java sources are read; Kotlin that needs kapt is not supported
- Runs the [pre-build checks](#pre-build-checks-local-build)
- **Export AAB** is supported: the bundle is packaged directly by Studio Mobile (without bundletool) and signed with the project key

### 2. Gradle + JDK build

Used automatically if the project:

- has `gradlew` / `gradle-wrapper.properties` (an Android Studio project)
- has a `buildSrc` or `build-logic` folder
- uses `apply from`, `includeBuild`, custom tasks, or plugins other than the list above

Studio Mobile chooses **Gradle and JDK together** based on the project's needs (wrapper version, AGP, Java level) and the AGP/Gradle/JDK compatibility table. The Gradle version from the wrapper is tried first; another version is only used if that one cannot run.

Task that is run: `assembleDebug`. During **Export AAB**, the `bundleRelease` task is also run in the same Gradle process:

- What is built is the **release** variant, not debug, because Google Play rejects debuggable bundles. The `release` build type from the project's Gradle file is used as-is (minify, resources, and signing)
- The key selected on the **Keystore** page is passed to Gradle through temporary signing properties (the same as *Generate Signed Bundle* in Android Studio)
- If no key is selected **and** `build.gradle` has no release `signingConfig`, the bundle is not signed and Google Play will reject it; the log gives a warning
- The debug APK is still built in the same run, and the bundle result is saved at `Studio Mobile/Export AAB/<app name>.aab`
- If Gradle finishes but there is no `.aab` file in `<module>/build/outputs/bundle/release`, the build is declared failed. Make sure the app module uses the `com.android.application` plugin

### Pre-build checks (local build)

After compilation, the local build looks for problems that **pass compilation but will certainly make the app crash**, so that a broken APK is never created. Only certain cases are reported, with no guessing. The build is **stopped** with the message *"App would crash at runtime; build stopped, no APK was generated."* if:

- the compiler reports a *Null pointer access* that is certain to happen (the *Potential null pointer access* form is ignored)
- an `<activity>`, `<service>`, `<receiver>`, or `<provider>` in the manifest points to a class that does not exist in the compiled code
- an Activity is called through an `Intent` but has not been declared in `AndroidManifest.xml`
- a component has an `intent-filter` but no `android:exported`, even though `targetSdk` 31 and above requires it (the APK cannot be installed)

One check is only a **warning** in the log and does not stop the build: the code uses network classes (`HttpURLConnection`, `Socket`, ...) but the manifest has no `INTERNET` permission.

### Builds run in the background

Builds run in a *foreground service*, so they keep going even if the app is left or the screen is turned off. During the build, an ongoing notification is shown containing the current step, a progress bar, elapsed time, and a **Stop** button; when finished it is replaced by a result notification. The app asks once to be exempted from battery optimization, and the choice is in the system dialog.

---

## Java and JDK

**Java language level**

- **Java 8 to Java 25** is supported. Projects without a declaration are built with Java 25
- Levels above 25 (`sourceCompatibility`, `targetCompatibility`, `jvmTarget`, `jvmToolchain`) are **rejected before compilation**, as are local libraries in `libs/` compiled for newer Java
- Levels below 8 only give a warning and are compiled as Java 8

**JDK for Gradle builds** (downloaded once, SHA-256 verified, architectures **arm64-v8a and armeabi-v7a**):

| JDK | Notes |
|---|---|
| 11 | For AGP 7.x |
| 17 | Minimum for AGP 8.x and 9.x |
| 21 | The only version already marked *verified* in `tools/gradle-jdk.json` |
| 25 | Supported by Gradle 9.1+ |
| 27 | Available to download, but the compatibility table currently only goes up to JDK 26, so it is not yet paired with any Gradle |

There is no JDK for x86 and x86_64, so Gradle builds only run on ARM phones.

---

## Gradle and AGP

**Available Gradle versions** (downloaded from `services.gradle.org`):

`7.6.6`, `8.13`, `8.14.6`, `9.5.0`, `9.8.1` (`9.8.1` must be present)

**AGP compatibility table** (`assets/build-tools/gradle-compat.json`, can be edited without changing code):

| AGP | Minimum Gradle | Maximum Gradle | Minimum JDK |
|---|---|---|---|
| 7.0 to 7.4 | 7.0.2 to 7.5 | below 8.0 | 11 |
| 8.0 to 8.13 | 8.0 to 8.13 | below 9.6 | 17 |
| 9.4 | 9.6.0 | unbounded | 17 |

Note: the AGP 7.x rows are marked `verify: true`, not yet matched against the official table.

**Maximum JDK per Gradle:** 7.6 → JDK 19, 8.5 → 21, 8.10 → 23, 8.14 and 9.0 → 24, 9.1 → 25, 9.4+ → 26.

---

## Other languages

### Python (arm64-v8a only)

- CPython 3.14.8. The interpreter is included in the APK, the standard library is downloaded once on first use
- Runs **a single `.py` file** from the editor, for scripts, automation, and data processing
- Python is **not packaged into the APK** and cannot create the app's UI

### Dart (arm64-v8a only)

- Dart SDK 3.13.5. `src/main/dart/main.dart` is compiled to a kernel and packaged into the APK together with the Dart VM
- Called from Java/Kotlin through the `studio.dart.StudioDart` class
- Dart core libraries only. **No pub packages**. For Flutter projects see the next section

### Flutter (arm64-v8a only, early stage)

- A project is considered Flutter if it has a `pubspec.yaml` with the dependency `flutter: sdk: flutter` **and** an `android/` folder containing Gradle files. Pure Dart projects (without a Flutter dependency) still go through the Dart path. If `android/` does not exist yet, create it on a computer with `flutter create --platforms=android .` and then move the project back
- **arm64-v8a** phones only. On other phones the build stops with a message that Flutter needs an arm64 phone
- On first use, Flutter SDK 3.47.7 (archive of about 550 MB, needs about 2 GB of free space) is downloaded once and its SHA-256 is verified. Three executables (`dart`, `gen_snapshot`, `impellerc`) are included in the APK as `libflutterdart.so`, `libgensnapshot.so`, and `libimpellerc.so`, because Android 10+ only allows execution from the app's native library folder
- **Current status:** the build installs the SDK and then checks whether the Flutter tools can run on the phone. The check result is in the Build Logs on the `[flutter-check]` line. **The APK creation step is not yet available**, so building a Flutter project still ends with a failed status along with this explanation

### C/C++ (arm64-v8a only)

- NDK r29 (clang) is downloaded once, builds `lib<name>.so` to be called through JNI
- CMake + Ninja mode if available, or clang directly for the recognized subset of CMake
- 64-bit libraries are linked with 16 KB alignment (a Google Play requirement)
- On non-arm64 phones, the project can still use prebuilt `.so` files in `src/main/jniLibs/<abi>/` (build them through GitHub Actions, there is a `native-build.yml` template)

### JavaScript and web

- `.js` and `.mjs` are run with the **native Node.js binary** included in the APK per ABI (`libnodeexe.so`), built from [andiasriefail/node-android-build](https://github.com/andiasriefail/node-android-build)
- `.js` files are treated as CommonJS (`require`). For ES modules use `.mjs`
- Syntax checking uses `node --check`
- `.jsx`, `.tsx`, `.ts` in `src/main/assets` are automatically converted to `.js` at build time (React in WebView)

---

## Running code in the editor (Run)

Studio Mobile can **run a single file directly on the phone**, without building an APK. The result is shown live in an output window that has a **Stop** button.

**How:** in the editor's file tree, tap the **⋮** button next to the file name → choose **Run with ...**. Save the file first; a file that has not been saved is rejected with the message *"Save the file before running it"*.

| File | Menu | Run with | Time limit | Notes |
|---|---|---|---|---|
| `.py` | Run with Python | CPython 3.14.8 | 120 seconds | **arm64-v8a only**. The first run downloads the standard library once |
| `.js`, `.mjs` | Run with JavaScript | Native Node.js 24.x (`libnodeexe.so`) | 120 seconds | All ABIs. `.js` = CommonJS, `.mjs` = ES module |
| `.sh`, `.bash` | Run with Shell | The phone's own shell (`/system/bin/sh`, mksh + toybox) | 120 seconds | **All ABIs**, nothing to download. Started as `sh file.sh`, so no executable bit is needed. Not full bash |
| `.dart` | Run with Dart | Dart VM 3.13.5 | 180 seconds | **arm64-v8a only**. Core libraries only, no pub and no Flutter |
| `.java` | Run with Java | ECJ → D8 → `main()` in a separate process | 120 seconds | The file must have `public static void main(String[] args)` |
| `.kt` | Run with Kotlin | Kotlin compiler → D8 → `main()` in a separate process | 120 seconds | The file must have a `main` function |

General rules:

- **One Run at a time.** If a second Run starts while the first is still running, the second is canceled immediately. Press **Stop** on the first one to run again
- If the time limit is exceeded, the process is stopped and the output window writes `[timed out after 120 s]`
- When finished, the output writes `[process finished with exit code N]`
- **C/C++ has no Run button.** C/C++ code is built into `lib<name>.so` together with the app and called through JNI (see the example below)
- Other files (for example `.lua`, `.rs`) can only be edited with syntax highlighting, without a Run menu
- Python, Dart, Node.js, and Shell run from the editor **are script mode**. Only `src/main/dart/main.dart` is packaged into the APK, and JavaScript goes into the APK through `assets` (WebView). Python and Shell scripts are not packaged into the APK
- Shell scripts in `scripts/` can also run **automatically around a build**: `scripts/pre-build.sh` and `scripts/post-build.sh` (see [Shell scripts (.sh)](#shell-scripts-sh))

**Example files to try**

`hello.py`

```python
import sys
print("Hello from Python", sys.version.split()[0])
```

`hello.js`

```javascript
console.log("Hello from Node.js", process.version);
```

`hello.sh`

```sh
#!/system/bin/sh
echo "Hello from Shell"
echo "Android $(getprop ro.build.version.release), $(uname -m)"
```

`main.dart`

```dart
void main() {
  print('Hello from Dart');
}
```

`Hello.java`

```java
public class Hello {
    public static void main(String[] args) {
        System.out.println("Hello from Java");
    }
}
```

`hello.kt`

```kotlin
fun main() {
    println("Hello from Kotlin")
}
```

Example output in the Run window for `hello.py`:

```
$ python hello.py
Hello from Python 3.14.8

[process finished with exit code 0]
```

Example output in the Run window for `hello.sh`:

```
$ sh hello.sh
Hello from Shell
Android 14, aarch64

[process finished with exit code 0]
```

---

## Creating a new project

The create-project button on Home opens a **4-step** wizard:

| Step | Content |
|---|---|
| 1. App icon | Choose an image; it is cropped square from the center. The mdpi, hdpi, xhdpi, and xxhdpi icon sizes can be set from 16 to 512 px (default 48, 72, 96, 144; xxxhdpi is calculated automatically) |
| 2. Identity | Project name, app name, and package. An empty package becomes `com.example.<project name>` |
| 3. Version | `versionCode` (default 1) and `versionName` (default `1.0`) |
| 4. Android versions | Min SDK and Target SDK, API 21 to 37. Defaults are 23 and 35. Min SDK cannot exceed Target SDK. A summary is shown at the bottom |

All wizard choices are written to `app/build.gradle` as-is (`minSdk`, `targetSdk`, `versionCode`, `versionName`), so the project list, editor, and build read the same values. `compileSdk` is 37, or higher if Target SDK is higher.

**Files created** (existing files are never overwritten; if any step fails, everything already written is deleted again):

```
settings.gradle
build.gradle
gradle/libs.versions.toml        (AGP 8.13.1)
app/build.gradle                 (Groovy DSL, Java 17)
app/proguard-rules.pro
app/src/main/AndroidManifest.xml (without the package attribute; the namespace is in build.gradle)
app/src/main/java/<package>/MainActivity.java
app/src/main/res/values/strings.xml
app/src/main/res/values/themes.xml
app/src/main/res/mipmap-*/ic_launcher.png   (if an icon is chosen)
```

Not created: `gradlew` and `gradle/wrapper`, `gradle.properties`, `.gitignore`, `local.properties`. New projects are built by the **local build**.

If an icon is chosen, the **512 x 512 px** store icon (PNG, ready for Google Play Console) is also saved at `Studio Mobile/Icon Google Play Console/<project name>/ic_launcher-playstore.png`. That folder is always created, and is empty if there is no icon.

---

## Multi-language project structure

One project can contain **Java, Kotlin, XML, C/C++, Dart, JavaScript/JSX, and Python all at once**. Below is an example `My Project/` that combines all of them:

```
My Project/
├── settings.gradle
├── build.gradle
├── gradle/
│   └── checksums.properties            # (optional) library SHA-256 pins
├── scripts/                            # (free) scripts run from the editor
│   ├── hello.py                        # Python: Run with Python, NOT included in the APK
│   ├── tool.js                         # Node.js: Run with JavaScript
│   ├── hello.sh                        # Shell: Run with Shell, NOT included in the APK
│   ├── pre-build.sh                    # (optional) Shell: runs automatically BEFORE the build
│   └── post-build.sh                   # (optional) Shell: runs automatically AFTER the build ($STUDIO_APK)
└── app/
    ├── build.gradle
    ├── libs/                           # (optional) local .jar files
    └── src/main/
        ├── AndroidManifest.xml
        ├── java/com/example/app/
        │   ├── MainActivity.java       # Java: screens and logic
        │   └── NativeBridge.java       # Java: JNI function declarations
        ├── kotlin/com/example/app/
        │   └── Helper.kt               # Kotlin: side by side with Java
        ├── res/
        │   ├── layout/activity_main.xml    # XML: UI (can be Previewed)
        │   └── values/strings.xml
        ├── assets/                     # Web: loaded in WebView
        │   ├── index.html
        │   ├── App.jsx                 # automatically converted to App.js at build time
        │   └── style.css
        ├── cpp/                        # C/C++: NDK r29, arm64-v8a
        │   ├── CMakeLists.txt
        │   └── native-lib.cpp
        ├── dart/
        │   └── main.dart               # Dart: entry point, packaged into the APK
        └── jniLibs/                    # (optional) prebuilt .so, per ABI
            └── arm64-v8a/
```

Role of each part:

| Folder | Language | Goes into the APK? | How it is used |
|---|---|---|---|
| `src/main/java`, `src/main/kotlin` | Java, Kotlin | Yes | App logic |
| `src/main/res` | XML | Yes | UI; can be Previewed |
| `src/main/assets` | HTML, CSS, JS, JSX/TSX/TS | Yes | Loaded in `WebView`; JSX/TSX/TS are converted to JS |
| `src/main/cpp` | C/C++ | Yes (`lib<name>.so`) | Called through JNI |
| `src/main/dart/main.dart` | Dart | Yes (kernel + Dart VM) | Called through `studio.dart.StudioDart` |
| `src/main/jniLibs/<abi>` | Prebuilt `.so` | Yes | For non-arm64 phones (GitHub Actions result) |
| `scripts/` (free name) | Python, Node.js, Shell | **No** | Run from the editor |
| `scripts/pre-build.sh`, `scripts/post-build.sh` | Shell | **No** (they run during the build) | Run automatically before / after the build; the app asks for permission first |

> Note: Python, Dart, and C/C++ are for **arm64-v8a** only. Shell (`.sh`) and Node.js work on all ABIs. On other phones, those parts cannot be built or run on the device; use prebuilt `.so` files in `jniLibs/<abi>/` for C/C++.

### Key file examples

`app/build.gradle`

```groovy
plugins {
    id 'com.android.application'
    id 'org.jetbrains.kotlin.android'
}

android {
    namespace 'com.example.app'
    compileSdk 36            // write as a plain number, not a variable

    defaultConfig {
        applicationId 'com.example.app'
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
Java_com_example_app_NativeBridge_hello(JNIEnv* env, jclass) {
    return env->NewStringUTF("Hello from C++");
}
```

`app/src/main/java/com/example/app/NativeBridge.java`

```java
package com.example.app;

public final class NativeBridge {
    static { System.loadLibrary("native-lib"); }
    public static native String hello();
}
```

`app/src/main/dart/main.dart` (reads a line from Java via stdin and replies via stdout)

```dart
import 'dart:io';

void main() {
  stdin.listen((data) {
    final text = String.fromCharCodes(data).trim();
    print('Dart received: $text');
  });
}
```

`app/src/main/java/com/example/app/MainActivity.java` (combines XML layout, WebView, C++, Dart, and Kotlin)

```java
package com.example.app;

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
        title.setText(NativeBridge.hello());                          // C++ via JNI
        title.append("\n" + Helper.INSTANCE.greet("Kotlin"));         // Kotlin from Java

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
            Log.e("Dart", "Cannot run: " + e.getMessage());          // e.g. the phone is not arm64
        }
    }
}
```

`app/src/main/kotlin/com/example/app/Helper.kt`

```kotlin
package com.example.app

object Helper {
    fun greet(name: String) = "Hello from $name"
}
```

`scripts/hello.py`, `scripts/tool.js`, and `scripts/hello.sh` can be run directly from the **⋮ → Run with Python / JavaScript / Shell** menu, without a build.

`scripts/pre-build.sh` (example: write the build time into an asset before the APK is built)

```sh
#!/system/bin/sh
# Working directory = project root. $STUDIO_PROJECT_DIR is the same folder.
mkdir -p app/src/main/assets
date > app/src/main/assets/build-time.txt
echo "pre-build done"
```

`scripts/post-build.sh` (example: report the finished APK)

```sh
#!/system/bin/sh
# $STUDIO_APK = path of the APK that was just built
ls -l "$STUDIO_APK"
```

---

## Preview (XML and WebView)

Preview shows the project's screens **without building an APK**. Open it from the editor with the **Preview** button. It appears as a phone frame with a status bar, navigation bar, and title bar that follow the project's theme and code.

> **Important:** the project's Java/Kotlin code is **not run** in the preview. The code is only *read* to find out what happens when a view is touched (open another Activity, show a Toast, close the screen). Logic, network, and database are not active.

### Automatically selected mode

| What is opened | What is shown |
|---|---|
| `res/layout/*.xml` | **Layout Mode**: the XML is inflated with the project's own theme and styles |
| `assets/*.html` | **Web Mode**: the page is loaded in a real WebView |
| Other file / no file open | The layout of the launcher Activity in the manifest, or the web page in `assets` |
| Code calls `loadUrl("https://...")` | That HTTPS page is loaded in the WebView |

Both modes can be combined, for example a layout containing a `WebView` that loads `assets/index.html`.

### XML layout preview

Only the AAPT2 stage (compile and link) runs, without Java, DEX, or signing, so it is fast.

Widgets that cannot be inflated directly are replaced with preview versions that read `app:` and `tools:` attributes:

- `ConstraintLayout`: parent/sibling/guideline anchors, bias, dimension ratio, chain, barrier, group, flow (simple version: wrapMode none/chain/aligned, orientation, gap, maxElementsWrap, style, bias). Not yet available: flow_horizontalAlign/verticalAlign, circular positioning
- `RecyclerView`, `ListView`, `GridView`, `ViewPager` (sample content from `tools:listitem`)
- `TabLayout`, `BottomNavigationView`, `FloatingActionButton`, `TextInputLayout`, `Chip`, `DrawerLayout`, `AppBarLayout`
- `MaterialButton`, `CardView`, `MaterialCardView`, as well as `srcCompat` and tint
- Custom views are drawn as their parent class; fragments show the inflated layout
- `VideoView` and ExoPlayer/Media3 `PlayerView` play real video (HTTPS, `res/raw`, or `assets` only)
- Picture-in-picture is imitated if the manifest contains `android:supportsPictureInPicture="true"` (display only, the video is not played)

**Example** `app/src/main/res/layout/activity_main.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:tools="http://schemas.android.com/tools"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:orientation="vertical"
    android:padding="16dp">

    <!-- tools:text is only shown in the preview and only fills in empty ones -->
    <TextView
        android:id="@+id/title"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:textSize="24sp"
        tools:text="Hello Studio Mobile" />

    <Button
        android:id="@+id/btnNext"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:text="Open second screen" />
</LinearLayout>
```

**Navigating between screens in the preview.** If `MainActivity` contains the following code, touching the button in the preview opens `SecondActivity`. The Back button in the preview's navigation bar returns to the previous screen:

```java
findViewById(R.id.btnNext).setOnClickListener(v ->
        startActivity(new Intent(this, SecondActivity.class)));
```

### WebView preview

Pages in `assets/` are loaded in a real WebView. Its behavior follows the project's code, as on a device:

- JavaScript and DOM storage are **only active if the code turns them on** (`setJavaScriptEnabled(true)`, `setDomStorageEnabled(true)`)
- If the code loads `file:///android_asset/...`, the preview uses the same scheme, so `fetch()` to local files fails just as on a device
- Media autoplay only works if the code calls `setMediaPlaybackRequiresUserGesture(false)`
- `http://` pages (without S) are **not loaded**; the preview explains why. Use HTTPS
- `.jsx`, `.tsx`, `.ts` are converted to `.js` automatically. Relative imports (`./App`), `import './style.css'`, and `import data from './data.json'` are also served

**Example** `app/src/main/assets/index.html`:

```html
<!doctype html>
<html>
  <head>
    <meta name="viewport" content="width=device-width, initial-scale=1">
    <title>Hello</title>
  </head>
  <body>
    <h1>Hello from WebView</h1>
    <button onclick="document.body.style.background='#cde'">Change color</button>
  </body>
</html>
```

**Example** `MainActivity.java` that loads it:

```java
WebView web = findViewById(R.id.web);
web.getSettings().setJavaScriptEnabled(true);   // without this JS is off, in the preview and on the phone
web.loadUrl("file:///android_asset/index.html");
```

### Preview follows changes

The preview is built from the file contents on disk at that moment. If an AI assistant changes a file through MCP, the preview that is currently open is updated too. The Refresh button reloads it manually.

---

## Dependencies, Repository URL, and JitPack

Libraries are fetched from the Maven repositories **declared in the project**, plus **Google Maven** and **Maven Central** as a fallback (projects that do not write them still work), or from `libs/*.jar`. Libraries that are not yet in the cache fail if the phone is offline.

Studio Mobile reads the `repositories { }` block from `settings.gradle(.kts)`, the root `build.gradle(.kts)`, then each module, in that order.

### Example: JitPack (Groovy)

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
    // JitPack format: com.github.<user>:<repo>:<tag or commit>
    implementation 'com.github.bumptech:glide:v4.16.0'
}
```

### Example: JitPack (Kotlin DSL)

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

### Example: other repository URLs

All of the forms below are recognized:

```groovy
maven { url 'https://repo.example.com/releases' }
maven { url = uri("https://repo.example.com/releases") }
maven { setUrl("https://repo.example.com/releases") }
maven("https://repo.example.com/releases")
maven(url = "https://repo.example.com/releases")
```

### Example: restrict repositories with `content`

So that JitPack is only used for certain groups (faster and safer):

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

Recognized filters: `includeGroup`, `includeGroupAndSubgroups`, `includeGroupByRegex`, `includeModule`, the equivalent `exclude...` forms, and `exclusiveContent { forRepository { ... } filter { ... } }`.

### Not supported (will appear as warnings in the build log)

| Declaration | Handling |
|---|---|
| `http://...` | **Blocked for security**, skipped. **HTTPS** only ([why](#network-security-http-blocked-https-only)) |
| Addresses from variables, for example `url "$repoUrl"` | Skipped. Write the address as plain text |
| `credentials { ... }` | The repository is still tried **without** credentials, so only public files can be downloaded |
| `flatDir`, `ivy` | Skipped (not Maven repositories) |
| `mavenLocal()` | Skipped (does not exist on the phone) |
| `SNAPSHOT` versions | Not supported, use a release version |
| `oss.sonatype.org` | Retired by Sonatype (2025). Use Maven Central |
| `jcenter()` | Already shut down. Used only if the preceding repositories do not have the file |

Note: JitPack must be **declared** in the project; it is not active automatically like Google and Maven Central. The first build of a JitPack library can take a long time because JitPack builds from the source on the server side.

### Library file verification (SHA-1 and SHA-256)

Every downloaded library file is checked before being used:

1. **SHA-1 from the repository.** The file is compared with the `.sha1` that the repository publishes next to it. A mismatch means the file is **not used**.
2. **Without a `.sha1` and without a pin, the file is rejected.** Studio Mobile refuses to use files that cannot be verified (message *"File cannot be verified"*).
3. **A SHA-256 pin that you create (recommended).** The SHA-1 from the server comes from the same server as the file, so it does not prove that it is the file you intended. A SHA-256 pin comes from *outside* the server (for example a release page) and **is decided by you**: if a pin exists, the server's `.sha1` is not needed and a file that does not match is never used, from any repository.

Pins are written in `gradle/checksums.properties` at the project root, one line per library, in the format `group:artifact:version=SHA-256`:

```properties
# gradle/checksums.properties
# SHA-256 of the library's main file (.jar, or .aar for Android libraries).
# Copy from the release page. The values below are only an example of the form, NOT real hashes.

# Library from JitPack
com.github.User:Repo:1.0.0=9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08

# The "sha256:" prefix may be used
com.github.Other:Lib:2.3.1=sha256:9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08
```

Pin file rules:

| Rule | Description |
|---|---|
| Line | `group:artifact:version=value`, exactly 3 parts before the `=` |
| Value | Exactly **64 hexadecimal characters** (upper or lower case, automatically converted to lower case) |
| Comments | Start with `#`, may be on their own line or at the end of a line |
| Duplicates | The same library with two different values is rejected |
| Wrong format | The build stops with a message saying which line is wrong |
| Cache | A pin added after the file is already in the cache is still checked; a cached file that does not match is deleted |

A build that uses a pin marks that library with `[SHA-256 pinned]` in the log.

**How to get the SHA-256**

- From the library's release page (the best way, because the value comes from another source)
- Calculate it yourself from a file you downloaded and trust:

```bash
# Linux / Termux
sha256sum library-1.0.0.aar

# macOS
shasum -a 256 library-1.0.0.aar

# Windows (PowerShell / cmd)
certutil -hashfile library-1.0.0.aar SHA256
```

- Or let Studio Mobile show it. If the library has not been pinned and the repository does not publish a `.sha1`, the build fails and the log contains a ready-to-paste line:

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

> **Do not just paste that line.** First compare the value with the SHA-256 on the release page. If it differs, the file may have been altered and **should not be used**.

Example of a failure when the pin does not match:

```
SHA-256 of Repo-1.0.0.aar (com.github.User:Repo:1.0.0) from https://jitpack.io/ does not match gradle/checksums.properties.
Expected <value in the pin>, received <value of the downloaded file>. The file was not used.
```

If the library has not been pinned but the server's `.sha1` exists and matches, the build still runs and the log gives a **warning** containing the SHA-256 and the line that can be added to `gradle/checksums.properties`, because the server's SHA-1 alone does not prove that it is the file you expect.

These pins are read by the **local build** resolver. The Gradle + JDK build uses real Gradle with its own verification mechanism.

### Download security

- All component downloads (NDK, JDK, Python, Dart, Gradle) use **HTTPS and are SHA-256 verified**
- The tool catalog is pinned with hashes inside the app (example: `tools/dart-sdk.json` contains `"sha256": "a467fe1d..."` for the Dart SDK)
- ZIP/TAR extraction is protected from zip-slip and zip bombs
- Importing a password-protected ZIP (ZipCrypto and AES) shows a password dialog

---

## Network security: HTTP blocked, HTTPS only

Studio Mobile **does not allow plain HTTP (`http://`)**. All connections must use **HTTPS (`https://`)**. This is intentional for security: over HTTP, a party in the middle of the network (public Wi-Fi, router, internet provider) can read or **alter** files that pass through. For an IDE that builds apps, a library altered on the way could insert malicious code into your APK.

Where this rule applies:

| Part | Behavior when `http://` |
|---|---|
| Maven repositories in `build.gradle` / `settings.gradle` | **Skipped** with the warning *"is not HTTPS; only HTTPS repositories are supported. Skipped."* |
| Redirects from a repository | Only followed if the destination is **HTTPS**, at most 3 times |
| Component downloads (NDK, JDK, Python, Dart, Gradle) | HTTPS only, plus SHA-256 verification |
| Relay URL on the MCP screen | **Rejected** (*"Enter a valid https:// relay address"*) |
| Pages loaded in Preview (`loadUrl("http://...")`) | **Not loaded**; the preview explains why |
| Video in Preview | HTTPS only |
| The Studio Mobile app itself | `usesCleartextTraffic="false"` in the manifest |

Additional checks on HTTPS connections:

- The host name is sent as SNI and the **server certificate must be valid for that host name**. A certificate that only matches an IP address or another name is rejected
- DNS is resolved through Google DNS and then Cloudflare DNS, not the phone's built-in DNS. If there is no DNS answer at all, the download stops

**Example fix**

```groovy
// Blocked: http
maven { url 'http://repo.example.com/releases' }

// Correct: https
maven { url 'https://repo.example.com/releases' }
```

If your repository server only provides HTTP, the options are: move it to HTTPS, or download the `.jar` / `.aar` file from a trusted source and put it in `app/libs/`. Compare the SHA-256 of that file with the one published by its source before using it.

Note: this rule applies to Studio Mobile. For apps you build yourself, permission for HTTP traffic is set in that app's manifest and code.

---

## Debug Mode

Debug Mode catches **crashes of the built app** and shows them directly on screen, so the app does not close silently. The log can be copied and then sent to an AI assistant.

### How to turn it on

**From the app:** open the project in the editor → **Debug Mode** menu → turn on **Enable Debug Mode**.

**From an AI through MCP:** call the `set_debug_mode` tool.

```json
{ "name": "set_debug_mode", "arguments": { "enabled": true } }
```

Without the `enabled` argument, the tool only reports the current status. The default is **off**. This setting applies in the Studio Mobile app, not per project, and cannot be changed if MCP is in read-only mode.

### How it works

1. During the build, Studio Mobile copies the project to a temporary build folder.
2. In **that copy only** (your project files are not changed), two classes and two manifest entries are added:
   - `StudioDebugProvider`: a `ContentProvider` with the authority `<applicationId>.studiodebug`. Android creates it before `Application.onCreate()`, so the crash catcher is installed before the app's code.
   - `StudioCrashActivity`: a full-screen white page in a separate process (`:studio_debug`), so it stays alive after the app's process is killed.
3. When an unhandled exception occurs, the stack trace is saved and the **"The app crashed"** page appears.
4. Press **Copy log**, then paste it to an AI assistant. Press **Close** to close.

Example flow: the app calls `Integer.parseInt("abc")` → the crash page shows `java.lang.NumberFormatException: For input string: "abc"` complete with the stack trace and the line of code that caused it.

### Important before sharing the app

> **Turn off Debug Mode before the APK/AAB is shared or uploaded.** With Debug Mode on, the built app includes the crash page and stack trace log. An app for release must be built with Debug Mode off.

### Restrictions

- Catches crashes from unhandled Java/Kotlin exceptions. Native (C/C++) crashes or a frozen app (ANR) may not be caught. If the log is empty, ask the AI to analyze further with Build Error Logs
- If `AndroidManifest.xml` has no `<application>` element or the package name cannot be determined, Debug Mode is skipped and the reason is written in the build log
- This is not the same as **Build Error Logs**, which are failure logs *during the build*. Debug Mode is for crashes *while the app is running*

### Debug Mode for Studio Mobile itself

The section above is for the **built** app. Studio Mobile also has a crash catcher for **itself**: turn it on or off in **Settings** (the default is **on**).

- Installed before `Application.onCreate()`, so crashes anywhere are caught: the editor, the compiler, dependency downloads, and the `:javarun` process
- The stack trace is saved and shown on a full-screen page in a separate process
- Terminations that the Java handler cannot see (the system kills the app because of memory, a native crash, ANR) are recovered when the app is opened again through `ApplicationExitInfo`, on **Android 11 and above**

---

## Editor wallpaper (photo and live video)

Open from **Settings → Change Wallpaper**. The image or video appears behind the **code editor**.

**Photo**

- Chosen from the gallery; if the phone has no gallery app, a file picker is used
- Scaled down so the longest side is at most 2048 px, EXIF rotation is applied, then saved as a quality-90 JPEG in the app's private storage. The wallpaper keeps working even if the original file is moved or its permission is lost

**Live video**

- **Choose video** button. Maximum duration **60 seconds** (0.5 second tolerance) and maximum file size 500 MB. A longer video is rejected with the message *"The video is longer than 60 seconds. Choose a shorter one."*
- Played **without sound** and **on a continuous loop**; it does not take audio focus, so music that is playing is not disturbed
- Only played in the **code editor**. It stops on Home, Preview, and Build, and pauses when you leave the app, then resumes from the last position when you come back
- The first frame is saved as a still image and used as the thumbnail and initial display before the video is ready

**Settings**

- **Darkness** slider 0 to 85% (default 55%): a midnight-blue layer over the image so the code stays readable over bright images
- The **Remove** button deletes the wallpaper. Only one wallpaper is active: choosing a photo replaces the video, and vice versa

---

## Keystore and signing key

Every app must be signed before it can be installed. Studio Mobile uses **one key per project**, and that key is used for the APK and AAB of that project.

### Built-in keystore (default)

A project that has not been given its own key is signed with the **Studio Mobile built-in key**.

- Created automatically when the app is first opened (file `studio-mobile-debug.p12`, alias `studio-mobile-debug`, type PKCS12)
- This key is **the same for all Studio Mobile users**, and its password is embedded in the app, so anyone can sign with the same key
- Suitable for **testing on your own phone**. **Do not use it for release** to Google Play or to share with other people
- On the Keystore page, its type is shown as **Studio Mobile default**
- Gradle builds for projects without their own key use the default Gradle/AGP signing, not this Studio Mobile built-in key

### Own keystore (own key)

Your own key is stored per project and appears on the Keystore page as **Own key**. There are two ways to get one: create a new one or import an existing `.p12`.

**Opening the Keystore page:** in the project list on Home, tap **⋮** on the project → **Keystore**.

The Keystore page shows the key name, type, alias, store password and key password (hidden, tap **Show** to see them), owner, validity dates, and SHA-256 fingerprint.

| Button | Function |
|---|---|
| **Copy** | Copies the key details to the clipboard: name, alias, both passwords, owner, validity dates, SHA-256, SHA-1, and the keystore contents in Base64. On Android 13+ the clipboard is marked sensitive so it does not appear in the keyboard preview |
| **Import** | Imports a **`.p12` (PKCS12)** keystore file. Fill in the alias, store password, and key password first (may be left empty if the same as the store password), then choose the file. The alias and passwords are checked first; if wrong, the key is not replaced |
| **Export** | Saves the key as a `.p12` file to a location of your choice. The alias and passwords are checked before the file is saved. The built-in key is exported as `<project name>-debug.p12` |
| **Change Key** | Creates a new key for this project (see below) |

**Creating a new key (Change Key)**

| Field | Rule |
|---|---|
| Alias | Required |
| Password and confirmation | Required, at least **6 characters**, and both must be the same |
| Company (CN) | Required |
| Organization (O) | Required |
| Unit (OU), City, State | May be empty |
| Country | Required, **2 letters** (country code, for example `ID`) |

The created key uses 2048-bit RSA and is valid for **30 years**, so it meets Google Play's minimum validity requirement (2033).

> **Warning:** an app that is already installed and signed with the old key **cannot be updated** by a version signed with a new key. Users must uninstall the old one first. For an app that has already been released, **do not change the key**.

### Screen protection (FLAG_SECURE)

The Keystore page shows passwords and key contents, so Studio Mobile sets Android's **`FLAG_SECURE`** security flag to prevent screenshots and screen recording:

| Part | Protection |
|---|---|
| **Keystore** page | `FLAG_SECURE` turns on as soon as the page is opened, and is **turned off again** when moving to another screen. Other screens (editor, build, and others) can still be screenshotted as usual |
| Key **Import** and **Export** dialogs (alias and passwords) | Protected |
| **Change Key** form (creating a new key), including when called from Export AAB | Protected |
| **Key changed** dialog after a key is created from Export AAB | Protected |

As a result: screenshots fail or show a black screen, screen recordings show a black screen, and the app preview in the recent apps list (*recents*) does not show the page contents.

Limitations you need to know about:

- This flag **does not prevent photographing the screen with a camera** or someone looking over your shoulder
- The **Copy** button still puts the passwords and keystore contents on the clipboard. On Android 13+ the clipboard is marked sensitive, but other apps that have clipboard access can still read it. Clear the clipboard after use
- The `.p12` file produced by **Export** is an ordinary file in the location you choose, outside the protection of this flag. Keep it in a safe place

### Export AAB and key

Google Play needs the developer's own key. When you choose **Export AAB** (project **⋮** menu):

- If the project still uses the built-in key, the key creation form appears first. After the key is created, there is a choice of **Export AAB** or **Import** another key
- If the project already has its own key, the AAB build starts right away
- The result is saved at `Studio Mobile/Export AAB/<app name>.aab`

How the key is used by each engine: the local build signs with the project key; the Gradle build receives the same key through temporary signing properties (see [Gradle and AGP](#gradle-and-agp)).

### Storage location and backup

- Your own key is stored in the app's **private** storage (`project-keys/<project name>/`), not in the `Studio Mobile/` folder
- Renaming a project also moves its key
- **Deleting a project also deletes its key**, and uninstalling Studio Mobile deletes all keys
- **Back up your own key** with the **Export** button, then keep the `.p12` file and its passwords in a safe place. A lost key means the app on Google Play can no longer be updated

---

## GitHub Build and repo ZIP download

This feature runs **GitHub Actions** from the phone: Studio Mobile reads a repo's workflows, you choose the branch and inputs, the workflow runs on GitHub's servers, and then the result (for example an APK) is downloaded to the phone. There is also a button to **download the whole repo as a ZIP**.

All connections to GitHub use **HTTPS** (`api.github.com`). The token is never sent to any address other than GitHub.

### 1. Personal access token

When you first open GitHub Build, the app asks for a token. The **Create token** button opens the token creation page on GitHub. The token is first checked with GitHub; if it is rejected, an error message appears in the dialog and the token is not saved. A valid token is stored **encrypted** (AES-256-GCM, key in the Android Keystore) only on this phone. The **Delete Token** button at the top right of the Github Build page deletes the token and its key.

Two types of token are supported. Choose one.

| | Fine-grained token (recommended) | Classic token |
|---|---|---|
| Page | `github.com/settings/personal-access-tokens` | `github.com/settings/tokens` → *Tokens (classic)* |
| Token prefix | `github_pat_...` | `ghp_...` |
| Repo access | Choose **All repositories** or **Only select repositories** | All repos the account can access |
| Required permissions | **Actions: Read and write** and **Contents: Read-only** (Metadata: Read-only is automatic) | **`repo`** scope (required). The `workflow` scope may be ticked but is not needed to run workflows |
| Scope | One owner only (one account **or** one organization) | All accounts and organizations where you are a member or collaborator |
| Validity | Must have an expiration date | Can have no expiration (not recommended) |

Important notes:

- **Choose the fewest permissions possible.** Do not tick scopes such as `delete_repo`, `admin:org`, or `admin:enterprise`; Studio Mobile does not use them. Set an expiration date and delete the token on GitHub when it is no longer used
- **Do not share the token** with anyone, including with an AI assistant or through screenshots
- Without **Contents (read)** (fine-grained), the workflow file contents cannot be read. Workflows can still be run, but **without inputs**, and the page gives the note *"Inputs unavailable: the token needs Contents (read)"*
- A rejected token (error 401) means it is wrong, expired, or has been deleted. Delete the token in the app and enter a new one
- Error 404 means the repo address is wrong or the token has no access to that repo

### 2. Choosing a repo

The Github Build page has two ways to choose a repo.

**Your own repo list (dropdown)**

The **Select repository** button opens the list of repos the token can access, sorted from most recently changed, with a search field. Private repos are marked 🔒. The list is loaded automatically in the background when the page is opened, and loads up to **1000 repos**.

| Token | What appears in the list |
|---|---|
| Fine-grained, **All repositories** | All repos owned by the token's owner |
| Fine-grained, **Only select repositories** | Only the selected repos, for example just one repo |
| Classic | All of your own repos, repos where you are a collaborator, and organization repos |

After choosing a repo, two options appear: **Build with workflow** and **Download ZIP**. If the list fails to load, the button changes to *"Could not load repositories. Tap to retry"*.

**Paste a repo address**

For someone else's repo or a repo outside the list. Recognized forms:

```
https://github.com/owner/repo
github.com/owner/repo
owner/repo
git@github.com:owner/repo.git
https://github.com/owner/repo/tree/branch-name
```

If the address contains `/tree/<branch>`, that branch is used as the starting point. If not, the default branch is used.

### 3. Branch and tag

After the repo is read, the repo page shows the status (**Public** or **Private**) and the default branch. The **Use workflow from** button opens the *Switch branches/tags* dialog:

- **Branches** and **Tags** tabs, with a **Find a branch or tag** field
- The default branch is marked `(default)`, the active choice is marked `✓`
- Loads up to 500 branches and 500 tags
- Switching the branch re-reads all workflows **on that branch**, because workflow contents and inputs can differ between branches

The workflow is run **on the selected branch or tag**.

### 4. Workflow and inputs

All of the repo's workflows are shown as cards (name and file path). A workflow **can be run** if its status is active **and** it has the manual trigger `workflow_dispatch`. If not, the card is given a note:

| Note | Meaning |
|---|---|
| `Disabled on GitHub` | The workflow is disabled on GitHub |
| `Not found on <branch>` | The workflow file does not exist on that branch |
| `No manual trigger (workflow_dispatch)` | It has no `workflow_dispatch`. Add `on: workflow_dispatch:` to the workflow file |
| `Inputs unavailable: the token needs Contents (read)` | The file cannot be read; the workflow runs without inputs |

Inputs defined in `workflow_dispatch.inputs` appear as a form: `string` and `number` as text fields, `boolean` as a checkbox, `choice` as a selection. Required inputs are marked with `*`; if empty, the app refuses to run. Default values from the workflow file are filled in automatically, and values you have changed are kept when switching workflows as long as they are still valid.

Example of a workflow that can be run from Studio Mobile:

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

### 5. Running and monitoring

The **Run workflow** button sends the command to GitHub, then the run page shows:

- The run title (`#number`), branch, status (*Queued*, *Running*, *Succeeded*, *Failed*, *Cancelled*, *Timed out*), and elapsed time
- **Steps**: a list of stages with markers `✓` succeeded, `✕` failed, `●` running, `○` waiting
- **Log** and a **Stop** button (cancels the run on GitHub) as well as **Open on GitHub**
- Status is updated every 3 seconds. If the connection drops, the app retries by itself. If GitHub's rate limit is hit (403/429), there is a 20-second pause
- The **Resume last run** link on the main page reopens the last run, for example after the app was closed

**Log**

- The log is read from the job that is running (or the job that failed) and keeps the **last 5000 lines**
- The log box can be scrolled up and down, with **Top**, **Bottom**, and **Full screen** buttons. The full-screen view can be scrolled and its text can be copied, and it is also updated during the build
- If the run fails, an **Errors found in the log** card appears that summarizes the error lines (for example `error:`, `FAILURE:`, `What went wrong`, `Exception`), so the cause is not buried under cleanup logs at the end
- The log of a failed job is saved in full at `Studio Mobile/Build Error Logs/<repo>-run<number>.log`

**Result**

- A successful run shows its artifacts (name and size). If there is only **one** artifact, it is downloaded automatically; if more, tap **Download** on the one you want. Downloads have progress and a **Cancel** button
- The ZIP is saved at `Studio Mobile/Github Application Zip/<repo>-run<number>-<artifact>.zip`
- If the ZIP contains an `.apk` file, an **Install** button appears. Android will ask for the *Install unknown apps* permission for Studio Mobile on first use
- Artifacts that have expired on GitHub are marked *Expired on GitHub*

### 6. Download repo ZIP

Downloads the entire contents of a repo as a single ZIP file, **without needing a workflow**. Works for your own repos as well as other people's repos (private repos need a token that has access).

Three places to start it from:

| From where | How | Branch downloaded |
|---|---|---|
| Main page | Enter the repo address, press **Download repository ZIP** | The branch from `/tree/<branch>` if present, otherwise the default branch |
| Own repo dropdown | Choose a repo → **Download ZIP** | Default branch |
| Repo page | The **Download ZIP of <branch/tag>** button | The branch or tag currently selected |

Result:

- Saved directly in the phone's **Download** folder as `<repo>-<branch>.zip`. If the name already exists, Android adds a number after it. No additional storage permission is needed
- While downloading, a progress dialog with a **Cancel** button appears. If canceled or failed, the partial file is deleted automatically
- The ZIP contents are a single parent folder named `<owner>-<repo>-<commit code>/` containing all files on that branch
- This is a ZIP from GitHub: **without the `.git` folder** (no commit history), submodules are not included, and Git LFS files are only pointers (not the actual contents)
- The destination folder is different from build results: repo ZIPs go to **Download**, while workflow result artifacts go to `Studio Mobile/Github Application Zip/`

To use a repo ZIP as a project in Studio Mobile, use the import project from ZIP feature.

### 7. Common problems

| Message / symptom | Cause and solution |
|---|---|
| `The token was rejected...` | The token is wrong or expired. Press **Delete Token**, then enter a new token |
| `Not found. Check the address, or the token may not have access...` | The address is wrong, or the token does not cover that repo. For fine-grained, add the repo to the token's access list |
| Repo does not appear in the dropdown | A fine-grained token only covers one owner and the selected repos. Paste the repo address manually, or create a new token |
| `This repository has no GitHub Actions workflows.` | The repo has no workflows yet. Download it as a ZIP, or add a file in `.github/workflows/` |
| The Run button is dimmed, workflow cannot be selected | The workflow has no `workflow_dispatch`, or is disabled. See the note on the card |
| `The workflow was started, but its run could not be found.` | GitHub was late creating the run (checked for 60 seconds). Press **Open on GitHub** or use **Resume last run** |
| Log is incomplete | Only the last 5000 lines are shown. Open **Open on GitHub** for the full log, or see the file in `Build Error Logs/` if the run failed |
| `Blocked an unsafe redirect.` | GitHub redirected the download to a non-HTTPS address or there were too many redirects. The download is canceled for security |

---

## MCP and Relay Server

MCP lets an AI assistant (Claude, ChatGPT, Gemini CLI) **read, edit, and build projects** from the phone.

The phone has no public address, so there is a **relay server** of your own in the middle. The phone contacts the relay (long-poll), not the other way around:

```
AI  --POST /s/<secret>/mcp-->  RELAY  <--long-poll GET /s/<secret>/phone/poll--  PHONE
                                      <--POST /s/<secret>/phone/reply/<id>------  PHONE
```

The relay address is **not embedded in the app**. You type it yourself on the MCP screen and it is stored only on the phone.

### 1. Run the relay

The code is in the `relay/` folder: a single `server.js` file, **Node.js 18+**, **no dependencies**.

```bash
cd relay
# create a long, random secret: this is the "key" to your phone
export SECRET=$(openssl rand -hex 24)
echo "Your secret: $SECRET"

PORT=8787 node server.js
# Studio Mobile MCP relay listening on :8787
```

Check that the relay is alive:

```bash
curl http://localhost:8787/health
# Studio Mobile MCP relay is running.
```

To be used from the internet, the relay **must be behind HTTPS** (the app rejects `http://`). The simplest example uses a reverse proxy, for example Caddy (automatic HTTPS certificate):

```
# Caddyfile
relay.example.com {
    reverse_proxy localhost:8787
}
```

The relay can also be installed on any hosting service that runs Node 18+ and provides HTTPS. The final address will look like `https://relay.example.com/s/<secret>`.

### 2. Relay environment variables

| Variable | Default | Function |
|---|---|---|
| `PORT` | `8787` | Server port |
| `RELAY_SECRETS` | empty | List of allowed secrets, separated by commas. Empty means any secret of 16 to 128 characters (`A-Z a-z 0-9 _ -`) is accepted |
| `AI_TIMEOUT_SECONDS` | `120` | How long an AI request waits for the phone's answer |
| `AI_TOKEN` | empty | If filled in, every AI request to `/mcp` must carry `Authorization: Bearer <token>` or `X-API-Key: <token>` |
| `PHONE_TOKEN` | empty | If filled in, the phone must send the same token |

A safer production example:

```bash
export RELAY_SECRETS="$SECRET"
export AI_TOKEN=$(openssl rand -hex 24)
export PHONE_TOKEN=$(openssl rand -hex 24)
node server.js
```

> `AI_TOKEN` only suits clients that can send headers (Claude Code, Gemini CLI). **Do not fill in `AI_TOKEN` if the relay is used by ChatGPT**, because ChatGPT's connector form has no header field.

Relay limits: body maximum 8 MB, queue maximum 50 requests, the phone is considered online if seen within the last 45 seconds.

### 3. Connect the phone

1. Open Studio Mobile → **MCP**.
2. Fill in the **Relay URL**, for example `https://relay.example.com/s/ENTER_YOUR_SECRET`. It may end with `/mcp` or lack `https://`; the app tidies it up. `http://` is not allowed, and there must be no `?` or `#`.
3. If the relay uses `PHONE_TOKEN`, fill in the **Phone token** field.
4. Press **Connect** and wait for the **Connected** status.
5. Copy the **AI address** (`.../mcp`) using the **Copy address** button.

### 4. Connect the AI

Replace `AI_ADDRESS` with `https://relay.example.com/s/ENTER_YOUR_SECRET/mcp`.

**Claude (claude.ai / desktop app):** Customize → Connectors → **+** → *Add custom connector*. Name: `Studio Mobile`. Remote MCP server URL: `AI_ADDRESS`. Leave sign-in and advanced settings empty. Enable the connector in the chat, then try: *"Show the list of my Studio Mobile projects."*

**Claude Code:**

```bash
claude mcp add --transport http studio-mobile AI_ADDRESS

# if the relay uses AI_TOKEN
claude mcp add --transport http studio-mobile AI_ADDRESS \
  --header "Authorization: Bearer YOUR_AI_TOKEN"
```

Or through `.mcp.json`:

```json
{
  "mcpServers": {
    "studio-mobile": {
      "type": "http",
      "url": "AI_ADDRESS",
      "headers": { "Authorization": "Bearer YOUR_AI_TOKEN" }
    }
  }
}
```

**Gemini CLI:**

```bash
gemini mcp add --transport http --scope user studio-mobile AI_ADDRESS
```

Or in `~/.gemini/settings.json` (use `httpUrl`, **not** `url`, because `url` means SSE):

```json
{ "mcpServers": { "studio-mobile": { "httpUrl": "AI_ADDRESS" } } }
```

Type `/mcp` in Gemini CLI to make sure `studio-mobile` is connected.

**ChatGPT:** Settings → Apps & Connectors → Advanced settings → turn on *Developer mode* (needs a plan that supports it). Create a connector `Studio Mobile`, MCP server URL `AI_ADDRESS`, Authentication: *No authentication*, tick *I trust this application*. In a new chat choose **+** → More → Developer mode → Studio Mobile.

**Clients that only support stdio:**

```bash
npx -y mcp-remote AI_ADDRESS
```

### 5. Tools available to the AI

| Group | Tools |
|---|---|
| Info | `get_studio_limits`, `get_toolchain_info`, `workspace_overview` |
| Read | `list_projects`, `list_files`, `read_file`, `search_text`, `find_files`, `check_local_libs` |
| Check | `validate_project`, `check_syntax` |
| Build | `get_build_status`, `wait_for_build`, `get_build_log` |
| Write | `write_file`, `edit_file`, `multi_edit`, `replace_lines`, `insert_lines`, `append_file` |
| File management | `copy_path`, `create_directory`, `move_path`, `delete_path` |
| Debug | `set_debug_mode` |

Tools in the **Write** and **File management** groups, and `set_debug_mode`, only appear if **Read-only mode is turned off**.

### 6. Example commands to the AI

- *"List all projects in Studio Mobile."*
- *"Read `MainActivity.java` in the Calculator project and explain its flow."*
- *"Change the button color in `activity_main.xml` to blue, then build."*
- *"Turn on Debug Mode, build, and tell me if there is a crash."*
- *"Find all uses of `Toast` in this project."*

When the AI changes a file, the editor and preview that are currently open are updated too. Successive changes are merged (sync 300 ms after the last change, at the latest 1.5 seconds after the first). If you have unsaved edits in the same file, your edits are **not overwritten**.

### 7. Security

- Anyone who knows the Relay URL can reach your phone. **Keep it secret** and use a long random secret (`openssl rand -hex 24`)
- Leave **Read-only mode on** unless you really do allow the AI to change, move, and delete files, change Gradle files, and run builds
- Use `RELAY_SECRETS`, `AI_TOKEN`, and `PHONE_TOKEN` on a relay that can be accessed publicly
- Always serve the relay over HTTPS

### 8. Common problems

| Message / symptom | Cause and solution |
|---|---|
| `Studio Mobile is not connected to this relay` | The phone has not connected or the connection was lost. Open the app → MCP → Connect |
| `The phone did not answer in time` | The phone did not reply within `AI_TIMEOUT_SECONDS`. Make sure the app is not stopped by the system and the internet is active |
| `Missing or wrong AI token` / `phone token` | The header token is not the same as `AI_TOKEN` / `PHONE_TOKEN` |
| `This secret is not allowed on this relay` | The secret is not in `RELAY_SECRETS`, or is shorter than 16 characters |
| `The phone has too many pending requests` | The queue is full (50). Wait or restart the connection |
| The app rejects the Relay URL | It must be `https://`, without `?` or `#` |

---

## IDE features

- Editor with syntax highlighting and **preview** (XML layout, WebView, React/JSX)
- Import projects from ZIP (including password-protected) or a folder; **Export Project To Zip** with Android's built-in picker
- **Keystore** per project: a built-in key for testing, or your own key (create new, import, export, copy); the key page and dialogs are protected by `FLAG_SECURE`; Export AAB for Google Play
- **Editor wallpaper**: photo or live video (max. 60 seconds) behind the editor
- **New project wizard** with the icon, version, and SDK of your choice, plus a 512 x 512 Google Play icon
- **Debug Mode** (crash catcher in the built app, and for Studio Mobile itself)
- Builds run in the background with a progress notification and a Stop button
- **GitHub Build**: list of your own repos, choose branch/tag and workflow, run on GitHub Actions, logs with an error summary, and install the resulting APK
- **Download repo ZIP** directly to the Download folder, for your own repo or someone else's
- **MCP relay** so AI assistants can read, edit, and build projects from the phone
- Build Error Logs per app: `<app name>-erors-logs.txt`

**`Studio Mobile/` folder structure**

```
My Project/        all projects
My Application/    APKs from "Save APK"
Export AAB/        AABs for Google Play
Icon Google Play Console/  512 x 512 store icon per project
Local/local_libs/  downloaded libraries
Build Error Logs/  one error log per app (including logs of failed GitHub runs)
Github Application Zip/  artifacts from GitHub Actions
```

Repo ZIPs from the **Download repo ZIP** feature are saved in Android's **Download** folder, not in this folder.

Needs the "All files access" permission (Android 11+) so that the folder is in shared storage. Without the permission, the same structure is created in the app's private storage.

**Permissions requested by the app**

| Permission | What it is for |
|---|---|
| `INTERNET` | Downloading build components and dependencies, as well as MCP and GitHub |
| `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_SPECIAL_USE`, `WAKE_LOCK` | Builds keep running while the screen is off |
| `POST_NOTIFICATIONS` | Progress and build result notifications (Android 13+) |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | Long builds are not killed by the system; asked once and you decide |
| `REQUEST_INSTALL_PACKAGES` | Opens the system installer for a newly built APK |
| `MANAGE_EXTERNAL_STORAGE` | The `Studio Mobile/` folder in shared storage (Android 11+). On Android 8 and 9 the classic storage permission is used |

---

## Limitations

- **Python, Dart, Flutter, and C/C++ are for arm64-v8a only.** They do not run on 32-bit ARM (armeabi-v7a), and on x86 and x86_64 that cannot run arm64 code
- **Gradle builds need an ARM phone** (the JDK is only available for arm64-v8a and armeabi-v7a)
- `compileSdk` / `targetSdk` above **API 37** are not supported
- Export AAB through Gradle always uses the **release** variant from the project's Gradle file. Without a key in the Keystore and without a release `signingConfig`, the bundle is not signed
- One APK per build: no build variants and custom product flavors
- Jetpack Compose is not supported. Annotation processors are for Java sources only; kapt (Kotlin) is not available on the phone
- Arbitrary Gradle plugins can only go through the Gradle build engine; the local build does not run them
- Manifest merging does not support `tools:node` and `tools:replace`
- Dart without pub packages; Python is not packaged into the APK
- **Flutter is still at an early stage**: arm64-v8a only, the SDK is installed and checked, but it **does not yet produce an APK**
- No unit tests or instrumented tests; only `assembleDebug` (plus `bundleRelease` for Export AAB in Gradle builds)
- The wizard only creates minimal starter files (one `MainActivity` containing one `TextView`, manifest, `strings.xml`, `themes.xml`). There are no XML layouts or other sample code, and imported projects are not changed
- The crash risk check before build exists only in the **local build**
- Video wallpaper is at most **60 seconds** and 500 MB, and is only played in the code editor
- The Studio Mobile built-in key is created automatically, is the same for all users, and **must not be used for release**; use your own key. Key import only supports the `.p12` (PKCS12) format. `FLAG_SECURE` does not prevent photographing the screen with a camera
- Large components (NDK, JDK, Gradle, Python standard library) are downloaded on first use and need internet and storage space
- **Preview does not run** Java/Kotlin code: logic, network, and database are not active; `ConstraintLayout` does not yet support barrier, group, and flow
- **Repository** is HTTPS only; credentials, variables, `flatDir`, `mavenLocal()`, and SNAPSHOT are not supported
- **Debug Mode** must be turned off before the app is shared
- **GitHub Build**: only workflows with `workflow_dispatch` can be run; the repo list is limited to 1000, branches and tags to 500 each; the log shows the last 5000 lines; a fine-grained token covers only one owner
- **Repo ZIPs** do not contain Git history (`.git`), submodules, or the contents of Git LFS files

---

## License

Studio Mobile is released under the **PolyForm Noncommercial License 1.0.0** (`PolyForm-Noncommercial-1.0.0`). The full terms are at <https://polyformproject.org/licenses/noncommercial/1.0.0>, and a summary notice is in the [LICENSE](LICENSE) file.

| Allowed | Not allowed without written permission |
|---|---|
| Used for personal purposes, learning, and research | Sold or included in paid products |
| Used by non-profit organizations | Used by companies for business activities |
| Modified, including fixing bugs | Distributed as part of a commercial service or product |
| Redistributed for non-commercial purposes | |

[CONTRIBUTING.md](CONTRIBUTING.md)

## Shell scripts (.sh)

Studio Mobile runs shell scripts with the phone's own shell (`/system/bin/sh`). Nothing is bundled or downloaded, and it works on every ABI (arm64-v8a, armeabi-v7a, x86, x86_64).

- **Run a file:** open the menu of a `.sh` or `.bash` file and choose **Run with Shell**. The script is started as `sh script.sh`, so it does not need the executable bit (Android 10+ would refuse to execute files from the app's data folder anyway).
- **Build hooks:** put these files in the `scripts/` folder of your project:
  - `scripts/pre-build.sh` runs before the build (generate sources, copy assets, write a version file ...).
  - `scripts/post-build.sh` runs after the APK was built; `$STUDIO_APK` is the path of the finished APK.
  - Both run in the build copy of the project (never your own files), with the project root as working directory, and `$STUDIO_PROJECT_DIR` points to it. A non-zero exit code fails the build, and a script gets 10 minutes. This works for the local, Gradle and Flutter routes.
  - Because scripts can come from an imported ZIP, the app asks "Run build scripts?" once per build before the first one runs.
- **Limits:** only the system tools (toybox/mksh: `ls`, `cp`, `sed`, `grep`, ...) are available. This is mksh, not full bash, and programs inside the app's own folders cannot be executed by a script. `#!` lines are ignored.
- The `check_syntax` MCP tool checks `.sh` files with `sh -n` (parse only, nothing runs).
