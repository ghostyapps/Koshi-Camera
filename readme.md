# Koshi 光子

*Formerly HeyCam.* 光子 (kōshi) is Japanese for "photon" — a particle of light.

![HeyCam Screenshot](screenshots/screenshot_1.png)

## The Story Behind Koshi

I was tired of every phone's native camera app constantly processing photos behind the scenes. AI enhancements, aggressive noise reduction, over-sharpening - I wondered: **could I build a camera app with zero processing?**

My goal was simple: capture what the lens and sensor actually see, with minimal interference. I especially didn't want AI "improving" my photos. To achieve true control and zero processing, I created **Koshi**.

## What is Koshi?

Koshi is a minimalist camera app with manual controls that lets you capture photos in both **JPEG** and **RAW (DNG)** formats. You have full control over your hardware, putting the power back in your hands.

Since JPEG files still undergo some processing by Android's algorithms, I added **RAW support** for true zero-processing photography. 

**Want true zero processing?** Switch to **Manual mode** and shoot in **RAW format**. The photos might look flat or low-quality at first - but that's the point! You're getting the unprocessed image data straight from the sensor.

## Features

### 🎛️ Exposure, the way a camera shows it
* **S · ISO · EV · F readout** under the viewfinder. In Auto the shutter speed and ISO the camera actually chose are shown live, marked with a red **A**.
* **Ruler dial** in third stops for shutter, ISO and exposure compensation, limited to what your sensor supports.
* **Tap S or ISO in Auto** to switch to Manual, starting from the metered exposure. Tap EV to go back.
* **Tap to focus and lock** (AF-L); long-press to return to continuous AF. In Auto, the tap point is also metered.

### 🎯 Manual focus
* Tap the focus cell (AF → MF) and turn the dial: distances from the lens's closest focus to ∞, like a lens barrel.
* **Focus aid:** the centre is **magnified 2.5×** while your finger is on the focus dial.
* In auto focus the cell shows the distance the lens is actually at, with a red **A**.

### 🧪 JPEG processing control
* **Minimal** (default): no sharpening, minimal noise reduction, no distortion correction.
* **Off**: no noise reduction, sharpening, hot-pixel, aberration or distortion correction, and a neutral sRGB tone curve.
* **Device**: the phone's own processing, for comparison.
* The viewfinder uses the same settings, so it shows what will be recorded.

### ⚡ Fast capture pipeline
* No focus or exposure sequence before a normal shot, and no preview restart after it.
* Image buffers are released immediately; files are written on a separate thread, so bursts never wait for the disk.
* Each DNG is paired with the capture data of its own exposure (by sensor timestamp) and embeds the lens shading map.

### ⏱️ Interval timer
* 1–30 s interval, 1–99 shots, countdown ring in the viewfinder, optional sound.
* **Nothing Phone (3):** the countdown also runs on the Glyph Matrix.

### 🖼️ LUTs & frames
* Import `.cube` LUTs (applied with trilinear interpolation, so gradients don't band) and PNG frames.
* EXIF (exposure, ISO, aperture, date, camera) is kept when a LUT or frame is applied.
* LUTs and frames never touch DNG files.

## Modes & Controls

### Auto
- The camera meters; you set exposure compensation on the dial.
- Shutter speed and ISO are shown live with a red **A**.

### Manual
- You set shutter speed and ISO; the preview follows instantly.
- Flash in Manual fires without overriding your settings.

### Shutter
- On-screen button, volume keys, camera key, or the Essential Key on Nothing phones.

## Technical Details
- **Real lens switching.** Some phones hide their multi-camera from apps (on the Nothing Phone (3) the real one is camera id 4, over sensors 0 / 2 / 3). Koshi finds it and streams straight from the chosen sensor, so **.6× / 1× / 3× switch lenses instead of cropping**. DNGs carry the selected sensor's own colour and noise data. Needs Android 10+.
- **JPEG:** what the ISP produces after the processing level you choose. Some phones apply processing even when asked not to, so **RAW is the only guaranteed zero-processing output.**
- **RAW:** unprocessed sensor data (DNG). Noise reduction and sharpening do not apply to RAW.

## Why RAW?
RAW files capture pure sensor data. They may look flatter or noisier, but they provide **unprocessed reality**. Edit these in Lightroom or Snapseed for maximum flexibility.

## Installation
1. Download the latest APK from [Releases](../../releases).
2. Enable "Install from unknown sources" in your Android settings.
3. Install and grant camera permissions.

## Technical Stack
- **Language**: Java
- **Camera API**: Camera2 (`camera/CameraController`, `camera/PhotoSaver`)
- **UI**: Views with custom-drawn dial, viewfinder overlay and histogram; Barlow typeface (OFL)
- **Key Libraries**: `com.nothing.ketchum` (Glyph SDK)

---

**Note**: This app is designed for photographers who want full manual control. If you prefer point-and-shoot convenience with AI enhancements, your phone's native camera app might be a better choice.