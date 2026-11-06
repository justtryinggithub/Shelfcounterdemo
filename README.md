# Shelf Counter — Android demo

Demo app that reads 1D barcodes with the phone camera and counts how many identical
products are lined up in a row. Built for the Samsung Galaxy A56 (Android 15); runs on
any Android 8.0+ phone.

## What it does

1. **Welcome page** with a **Start counting** button (asks for camera permission).
2. **Camera screen**, step 1 – **read the barcode** of the first item of a row
   (on the product or on the shelf-edge label). After 3 consistent reads it is saved:
   green frame, beep, number shown in the top panel.
3. Step 2 – frame the **whole row from an angle** (so every item behind the first one is
   at least partly visible) and tap **Count items**. The image is frozen and:
   - the item closest to the barcode is taken as the **first item** (ML Kit object detection);
   - identical items are found by **shape** and numbered on the photo (1 = first item);
   - the top panel shows the barcode and the **count of identical items**.
   If the first item is wrong or not found, **drag a box around it** to count again;
   **− / +** fix the count by hand. **Save count** adds it to the list, **Retake** goes back.
4. **Close camera** (or the back gesture) opens the result list, one line per product:

   ```
   8001234567890   number of objects = 6
   ```

   The list can be shared (WhatsApp, email, etc.) as plain text. Saving the same barcode
   twice adds the counts together.

Supported codes: EAN-13, EAN-8, UPC-A, UPC-E, Code 128, Code 39, Code 93, ITF, Codabar.
Everything runs on the device (bundled ML Kit models + OpenCV), so no internet is needed.

## How counting works

`ShapeCounter.kt` extracts SIFT keypoints (OpenCV) from the frozen photo. Keypoints inside
the first item's box are the model; every other keypoint is matched to it, and RANSAC groups
the matches into similarity transforms (shift + scale + rotation). Each group with enough
matches is one more copy of the product. Items further back can be smaller and partly
hidden. Tips for a good count:

- shoot the row **at an angle**, not straight on: an item completely hidden behind the
  first one cannot be counted;
- keep the image **sharp** (tap to focus before Count items, use the light if dim);
- products with printed labels work best; plain, featureless boxes give few keypoints.

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
| `ScanActivity.kt` | camera, barcode reading, count flow, overlay panel |
| `ShapeCounter.kt` | counts identical items by shape (OpenCV SIFT + RANSAC) |
| `BarcodeOverlayView.kt` | barcode frames, numbered item boxes, box drawing |
| `ResultActivity.kt` | final list and sharing |
