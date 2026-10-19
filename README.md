# Meta-SmartSight
Converting Ray-Ban Meta-Glasses to an Accessibility Tool for Blind and Low Vision Individuals.

## Requirements

- Android Studio with JDK 17
- A physical Android phone, API 31 or newer, with the [Meta AI](https://wearables.developer.meta.com/docs/develop/dat/getting-started-toolkit/) app installed
- Supported glasses paired in Meta AI: Ray-Ban Meta (Gen 1 or Gen 2), Ray-Ban Meta Optics, or Meta Ray-Ban Display
- Meta AI and glasses firmware at the [versions the toolkit requires](https://wearables.developer.meta.com/docs/develop/dat/version-dependencies/)

This build uses Developer Mode. `APPLICATION_ID` and `CLIENT_TOKEN` are `0`, so the app is not attested and does not need a Wearables Developer Center project.

## Set up the glasses

1. Pair the glasses in the Meta AI app.
2. In Meta AI, open **Settings**, then **App Info**, and tap the app version five times.
3. Turn on **Developer Mode** and confirm.
4. In Meta AI, open the Devices tab, select the glasses, and check **Device settings**, then **General**, then **About**, then **Version**. Update the glasses if that version is below the toolkit minimum.

After registration, this app is listed under **Meta AI settings**, then **App connections**, then **Developer mode apps**.

## Build and run

Open this folder in Android Studio and run the `app` configuration on the phone. Android Studio writes `local.properties` with the SDK path. That file stays out of git.

From a terminal:

```bat
gradlew.bat :app:assembleDebug
```

The debug APK is `app/build/outputs/apk/debug/app-debug.apk`.

## Use the app

1. Allow Bluetooth when the app asks. The Wearables SDK also needs internet access.
2. Tap **Register**. Meta AI opens to finish registration and returns through the `metaglassestest` URL scheme.
3. Unfold the glasses, keep them on, and tap **Start session**.
4. Allow camera access in Meta AI when it opens. The glasses then stream video at 2 frames per second, the lowest rate the toolkit accepts. The screen shows the latest frame.
5. Frames are classified on the phone with Ultralytics YOLO26n (COCO, 80 classes). The default is 1 detection per second. Change **Detections per second** to use another rate from 0.2 to 2. That setting does not change how often frames arrive.
6. A name is spoken only when that object was not seen within the last 5 seconds. Change **Speak again after an object has been absent** to use another gap from 1 to 30 seconds. Speech is media audio, routed to the glasses over Bluetooth A2DP. In the phone's Bluetooth settings, leave media audio enabled for the glasses.
7. Tap **Stop session** when you are done.

The screen shows the current registration state and session state. Errors from the SDK appear under those labels.

The bundled model is `app/src/main/assets/yolo26n_w8a32.tflite` from the Ultralytics YOLO release. Those weights are licensed under AGPL-3.0.
