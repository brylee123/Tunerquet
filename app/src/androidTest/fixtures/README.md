# Inert cleanup fixture (dedicated emulator only)

This APK has no executable code. Its package identifier deliberately matches the initial optional-media catalog so tests can exercise the real Android package manager. It is clearly labeled **Tunerquet cleanup TEST FIXTURE** and uses a test-only version. Never install it on a personal device or distribute it.

Use a dedicated API 30+ emulator. Build and sign it with the same debug key as Tunerquet (find that key with ./gradlew.bat :app:signingReport):

~~~powershell
$androidSdk = $env:ANDROID_HOME
$debugKey = 'C:/Android/.android/debug.keystore' # Replace with signingReport's debug Store.
New-Item -ItemType Directory -Force build/cleanup-fixture | Out-Null
& "$androidSdk/build-tools/35.0.0/aapt2.exe" link --manifest app/src/androidTest/fixtures/AndroidManifest.xml -I "$androidSdk/platforms/android-35/android.jar" -o build/cleanup-fixture/unsigned.apk
& "$androidSdk/build-tools/35.0.0/zipalign.exe" -f 4 build/cleanup-fixture/unsigned.apk build/cleanup-fixture/aligned.apk
& "$androidSdk/build-tools/35.0.0/apksigner.bat" sign --ks $debugKey --ks-pass pass:android --out build/cleanup-fixture/fixture.apk build/cleanup-fixture/aligned.apk
& "$androidSdk/platform-tools/adb.exe" -s emulator-5580 install -r --force-queryable build/cleanup-fixture/fixture.apk
./gradlew.bat :app:connectedDebugAndroidTest
~~~

Replace emulator-5580 with the dedicated emulator serial. Stop if any build/sign/install command fails. The force-queryable flag lets the test inspect the fixture label, metadata and signing certificate. The round-trip test skips when the fixture is absent or hidden; it requires emulator hardware and checks the marker, label and matching app signature before changing anything. It verifies user-0 enabled=3, APK/data retention, journal persistence and exact restoration to enabled=0. Its package-manager adapter uses real Android commands; separate tests cover the shipping TLS transport and shell-v2 exit status. The fixture and all generated APKs stay out of release builds.
