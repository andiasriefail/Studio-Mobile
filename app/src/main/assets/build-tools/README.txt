Studio Mobile offline build toolchain assets

Install verified files in app-private files/build-tools:
- platforms/android-<API>/android.jar (exact platform requested by compileSdk)
- kotlin-stdlib.jar (matching the selected Kotlin compiler)
- aapt2: packaged as jniLibs/<abi>/libaapt2.so; can also be placed under files/build-tools
- zipalign: optional. The in-process ZipAligner is used when no native zipalign runs; alignment still happens before signing

Java compilation (ECJ), Kotlin compilation, D8, and APK signing/verification use in-process app dependencies. No standalone java, ecj.jar, Kotlin compiler JAR, d8.jar, apksigner, or keytool executable is required by these stages.

The catalog schema and SHA-256 pin are implemented, but the bundled catalog has no downloadable components until trusted direct URLs, checksums, versions, ABI constraints, licenses, and redistribution rights are verified. The repository does not yet ship a complete verified toolchain. A local debug key is generated in app-private storage and must never be used for production publishing.
