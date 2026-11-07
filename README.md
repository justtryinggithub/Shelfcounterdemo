# Shelf Counter — Android demo

Demo app that reads 1D barcodes with the phone camera and counts how many identical
products are lined up in a row. Built for the Samsung Galaxy A56 (Android 15); runs on
any Android 8.0+ phone.

## What it does

1. **Welcome page** with a **Start counting** button (asks for camera permission).
2. **Camera screen** (version 2.7) – point at the **barcode** of the first item of a row
   (on the product or on the shelf-edge label). After 3 consistent reads it is taken:
   green frame, beep, number shown in the top panel and the message
   **"Barcode acquired. I'll count in 1 second"**.
3. One second later counting **starts by itself** – keep the whole row in view from an angle:
   - the app **counts twice**, on two different camera frames (the camera stays live);
     the item closest to the barcode is the **first item** (ML Kit object detection) and
     identical items are found by shape;
   - if **both counts are the same**, the value is **saved in the list**, the counted items
     are shown on the camera image for a moment and the message
     **"Count done. Looking for the next barcode"** appears; the camera stays open;
   - when a **new barcode** (one not counted yet) is read, the message disappears and the
     same steps start again for that product;
   - if the two counts **differ**, the last frame is frozen with the numbered items: drag a
     box around the first item to count again, fix with **− / +**, then **Save count**;
     **Retake** goes back to the camera;
   - **Recount items** repeats the double count for the current barcode (the new value
     replaces the old one). Barcodes already in the list do not start a count by themselves.
4. **Close camera** (or the back gesture) opens the result list, one line per product:

   ```
   8001234567890   number of objects = 6
   ```

   The list can be shared (WhatsApp, email, etc.) as plain text. Each barcode appears once.

Supported codes: EAN-13, EAN-8, UPC-A, UPC-E, Code 128, Code 39, Code 93, ITF, Codabar.
Everything runs on the device (bundled ML Kit models + OpenCV), so no internet is needed.

## How counting works

`ShapeCounter.kt` extracts SIFT keypoints (OpenCV) from the frozen photo. Keypoints inside
the first item's box are the model; every other keypoint is matched to it, and RANSAC groups
the matches into similarity transforms (shift + scale + rotation). Each group with enough
matches is one more copy of the product. Items further back can be smaller and partly
hidden.

**Colour check (2.7):** a copy must also have the same colours in the same places of its
shape. A grid of points on the first item is carried onto each copy with its transform and
the colours are compared (Lab, lightness counts less) where the copy is visible; if fewer
than half match, it is a different product (e.g. another fragrance in the same bottle)
and is not counted. Tips for a good count:

- shoot the row **at an angle**, not straight on: an item completely hidden behind the
  first one cannot be counted;
- keep the image **sharp** and the phone still while it counts (tap to focus, use the light if dim);
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
