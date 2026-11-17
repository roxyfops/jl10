# Building JL10

## You need

- JDK 17–21.
- The Android SDK, platform 35.

## Build

1. Create a file called `local.properties` next to `settings.gradle`, containing:

   ```
   sdk.dir=C:\\Users\\you\\AppData\\Local\\Android\\Sdk
   ```


2. Build:

   ```
   gradlew assembleRelease
   ```

   On osx and Linux, run `./gradlew assembleRelease`.

3. The APK is at `app/build/outputs/apk/release/JL10v0.6.apk`.

release build is signed with the debug key, so you don't need to set up a keystore.

## Install

```
adb install -r app/build/outputs/apk/release/JL10v0.6.apk
```

This needs Android 11 or newer.
