# Shelf Counter — Android demo

Demo app that reads 1D barcodes with the phone camera and counts how many identical
products are lined up in a row. Built for the Samsung Galaxy A56 (Android 15); runs on
any Android 8.0+ phone.

## What it does

1. **Welcome page** with a **Start counting** button (asks for camera permission).
2. **Camera screen** that stays open while you **walk along the aisle**. For each row, hold
   the phone for about a second so the **barcode** (on the first item or on the shelf label)
   and the **products behind it** are in view, at an angle:
   - after 2 consistent reads the barcode becomes a product in the list (beep + vibration);
   - the app grabs a frame, takes the item closest to the barcode as the **first item**
     (ML Kit object detection) and counts the **identical items** by shape;
   - the top panel shows the **last item recognized**, its **count** and a small picture of
     the counted frame with the items numbered (1 = first item);
   - while the barcode stays in view it recounts about every second and keeps the
     **highest** count, so a better angle can only improve it;
   - **− / +** fix the last product by hand (automatic counting then stops for it);
     **Reset** clears everything; flashlight, pinch-to-zoom and tap-to-focus are available.
3. **Close camera** (or the back gesture) opens the result list, one line per product:

   ```
   8001234567890   number of objects = 6
   ```

   The list can be shared (WhatsApp, email, etc.) as plain text. Each barcode appears once:
   seeing it again later keeps the highest count, it does not add a second row.

Supported codes: EAN-13, EAN-8, UPC-A, UPC-E, Code 128, Code 39, Code 93, ITF, Codabar.
Everything runs on the device (bundled ML Kit models + OpenCV), so no internet is needed.

## How counting works

`ShapeCounter.kt` runs two methods on the counted frame and keeps the higher count:

- **Same label** – SIFT keypoints (OpenCV) inside the first item's box are the model; every
  other keypoint is matched to it and RANSAC groups the matches into similarity transforms
  (shift + scale + rotation). Each group with enough matches is one more copy.
- **Same shape** – the top of the first item (cap and neck) is searched at smaller scales
  (colour + edge correlation, mean-colour check) and a chain is followed from the first
  item: each next item is a bit smaller, close to the previous one, in a consistent
  direction. This counts same-shaped containers even when the labels differ.

The first item is the ML Kit object closest to the barcode that is clearly bigger than the
barcode; if none is found it is estimated from the barcode position. After each count the
numbered boxes are drawn on the live image for 2.5 s. Tips for a good count:

- shoot the row **at an angle**, not straight on, so each cap is visible;
- keep the first item **whole and in focus** (not too close), use the light if dim;
- slow down on each row: about one second per row is enough.

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
  and object-detection 17.0.2; OpenCV 4.10.0 (SIFT); APK built for arm64-v8a and armeabi-v7a
- `minSdk 26`, `targetSdk 35`, edge-to-edge handled for Android 15
- Package name: `com.demo.shelfcounter`; debug-signed (demo use only, not for the Play Store)

Source layout (`app/src/main/java/com/demo/shelfcounter/`):

| File | Role |
|---|---|
| `WelcomeActivity.kt` | welcome page, camera permission |
| `ScanActivity.kt` | camera, barcode reading, continuous count loop, overlay panel |
| `ShapeCounter.kt` | counts identical items: same label (SIFT + RANSAC) and same shape (cap template chain) |
| `BarcodeOverlayView.kt` | barcode frames, numbered item boxes, box drawing |
| `ResultActivity.kt` | final list and sharing |
