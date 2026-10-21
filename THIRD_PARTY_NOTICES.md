# Third-party components

The APK bundles license texts under `assets/licenses/`.

- **Conscrypt Android 2.5.3**, Google and Conscrypt contributors: Apache-2.0 with bundled third-party notices. Source: https://github.com/google/conscrypt/tree/2.5.3 . Used only as the TLS provider for the pairing bootstrap.
- **SPAKE2 Android 2.2.1**, Muntashir Al-Islam and native BoringSSL contributors. Repository license: LGPL-3.0, including the incorporated GPL-3.0 terms; native code retains its upstream notices. Source and instructions for rebuilding/relinking: https://github.com/MuntashirAkon/spake2-java/tree/2.2.1 . Dependency: `com.github.MuntashirAkon.spake2-java:spake2-android:2.2.1`. The pure-Java artifact is intentionally not used. Note that the published POM incorrectly labels the dependency as GPL-3.0; the repository LICENSE is LGPL-3.0.

For a replacement native SPAKE2 build, keep the same Java/JNI API and replace the dependency in `app/build.gradle.kts`, then rebuild with the included Gradle wrapper. No app code restricts debugging or modification of these libraries.

Protocol references (not vendored source):

- AOSP pairing architecture: https://android.googlesource.com/platform/packages/modules/adb/+/refs/heads/main/docs/dev/adb_wifi.md
- AOSP pairing connection / framing: https://android.googlesource.com/platform/packages/modules/adb/+/refs/heads/main/pairing_connection/pairing_connection.cpp
- AOSP key derivation and AEAD framing: https://android.googlesource.com/platform/packages/modules/adb/+/refs/heads/main/pairing_auth/aes_128_gcm.cpp
- Android RSA public-key encoding: https://android.googlesource.com/platform/system/core/+/refs/heads/main/libcrypto_utils/android_pubkey.cpp
