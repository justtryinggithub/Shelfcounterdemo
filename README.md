# Shelf Counter — Android demo

Demo app that reads 1D barcodes with the phone camera and counts how many identical
products are lined up in a row. Built for the Samsung Galaxy A56 (Android 15); runs on
any Android 8.0+ phone.

## What it does

1. **Welcome page** with a **Start counting** button (asks for camera permission).
2. **Camera screen** with a live overlay:
   - green frame on every barcode that has been counted, amber while it is being confirmed;
   - top panel: **last item recognized** (barcode number and type) and its **count of identical items**;
   - a beep and a short vibration each time a new product appears or a count goes up;
   - flashlight, Reset, pinch-to-zoom and tap-to-focus.
3. **Close camera** (or the back gesture) opens the result list, one line per product:

   ```
   8001234567890   number of objects = 6
   ```

   The list can be shared (WhatsApp, email, etc.) as plain text.

Supported codes: EAN-13, EAN-8, UPC-A, UPC-E, Code 128, Code 39, Code 93, ITF, Codabar.
Recognition runs fully on the device (ML Kit bundled model), so no internet is needed.

## How counting works

Each camera frame reports every barcode in view. If the same number appears N times in one
frame, N identical items are side by side. A product's count is the highest N seen in at
least 3 frames, which filters out misreads. So:

- keep the **whole row in the frame** for about a second; every item's barcode must face the camera;
- counts only go up during a session; use **Reset** to start over;
- rows longer than one camera frame should be counted in parts (count, Close camera, New count).

## Get the APK

### Option A — GitHub (no software to install)

1. Create a new repository on github.com (private is fine) and upload this whole folder,
   including the hidden `.github` folder.
2. The **Build APK** workflow runs automatically (tab *Actions*, about 4 minutes).
3. Open the repository's **Releases** → *Shelf Counter demo* → download `ShelfCounterDemo.apk`.

### Option B — Android Studio

1. File → Open → select this folder; wait for Gradle sync.
2. Build → Build App Bundle(s) / APK(s) → **Build APK(s)**.
3. The APK is in `app/build/outputs/apk/debug/app-debug.apk`.
   (Or connect the phone with USB debugging on and press Run.)

Command line equivalent: `./gradlew assembleDebug` (needs the Android SDK and JDK 17+).

## Install on the Samsung A56

1. Copy the APK to the phone (download it in Chrome, or send it via Drive/email/USB).
2. Open it from **My Files → Downloads**.
3. If asked, allow **Install unknown apps** for the app you opened it with
   (Settings → Apps → *Chrome / My Files* → Install unknown apps).
4. If **Auto Blocker** is on (Settings → Security and privacy → Auto Blocker), turn it off
   temporarily, because it blocks apps that don't come from the Galaxy Store or Play Store.
5. Play Protect may warn about an unknown developer: tap *More details → Install anyway*.

## Project facts

- Kotlin, Views, Material 3; CameraX 1.4.1 + `camera-mlkit-vision`; ML Kit barcode-scanning 17.3.0
- `minSdk 26`, `targetSdk 35`, edge-to-edge handled for Android 15
- Package name: `com.demo.shelfcounter`; debug-signed (demo use only, not for the Play Store)

Source layout (`app/src/main/java/com/demo/shelfcounter/`):

| File | Role |
|---|---|
| `WelcomeActivity.kt` | welcome page, camera permission |
| `ScanActivity.kt` | camera, barcode detection, overlay panel |
| `ShelfCounter.kt` | counting logic (no Android dependencies) |
| `BarcodeOverlayView.kt` | frames drawn on top of the camera image |
| `ResultActivity.kt` | final list and sharing |
