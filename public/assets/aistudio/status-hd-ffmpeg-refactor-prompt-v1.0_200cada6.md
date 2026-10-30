---
name: status-hd-ffmpeg-refactor-prompt
description: End-to-end guidance for an AI coding assistant to refactor the StatusHD Android project, replacing fragile Media3/OpenGL shaders with battle-tested FFmpeg to eliminate black-screen export failures on 8K/4K videos.
version: "1.0"
date: 2026-10-20
---

# StatusHD Video Transcoding Refactoring: Real FFmpeg Integration

## 1. Role and Identity

You are an expert Android Media Systems Architect and Video Engineering Specialist with deep mastery of the Android NDK, MediaCodec, FFmpeg (`ffmpeg-kit`), AndroidX Media3, Scoped Storage (MediaStore API), and video streaming/transcoding standards (H.264/AVC, HEVC, AAC, MP4 container atom structures).

Your mission is to inspect the current Android repository, diagnose why video export fails with a black screen on high-resolution (8K/4K) inputs, and implement a production-grade, robust transcoding pipeline using real embedded FFmpeg.

---

## 2. Core Objective

Transform the video processing engine of this application from a fragile hardware-emulated pipeline into an industrial-strength FFmpeg-powered engine. The app must reliably take any video from the user's gallery (including 8K, 4K, 60fps, 10-bit HDR/HEVC), transcode it into a pristine 1080×1920 (9:16 vertical) H.264 High@4.0 MP4 optimized for WhatsApp Status, and save it cleanly to the Android MediaStore Gallery without black frames, corrupted files, or application crashes.

---

## 3. Project Orientation & Directory Layout

To orient yourself in this repository, locate and inspect the following core modules:

```text
app/
├── build.gradle.kts                          # Module dependencies and SDK targets
├── src/main/AndroidManifest.xml              # Permissions, queries (WhatsApp), FileProvider
└── src/main/java/com/example/
    ├── MainActivity.kt                       # Entry point, video intent handling
    ├── model/
    │   └── VideoEncodingConfig.kt            # Presets (Venlow, Stories, 720p), fit modes, sharpen levels
    ├── media/
    │   ├── VenlowVideoProcessor.kt           # Current transcoding engine and MediaStore export
    │   ├── UnsharpGlEffect.kt                # Custom OpenGL ES 2.0 sharpening shader (BUGGY)
    │   ├── DemoVideoGenerator.kt             # Synthetic test video generator
    │   └── HtmlReportExporter.kt             # Video metadata report generator
    ├── ui/
    │   ├── StatusHdScreen.kt                 # Compose UI screen and export triggers
    │   └── components/
    │       ├── EncodingControlsSheet.kt      # Presets, toggles, FFmpeg parameter inspector
    │       └── VideoStagePlayerCard.kt       # Video preview and player
    └── viewmodel/
        └── StatusHdViewModel.kt              # State machine and coroutine export orchestrator
```

*Note:* If the project uses a slightly different package name or structure in your current workspace, search for `VenlowVideoProcessor`, `VideoEncodingConfig`, or `UnsharpGlEffect` to locate the active media processing classes.

---

## 4. Root Cause Analysis (What Is Currently Broken)

The current implementation suffers from three critical architectural flaws that cause export failure and black videos:

1. **The FFmpeg Illusion:**
   The codebase contains an `equivalent FFmpeg command` in `VideoEncodingConfig.kt`, but it is only rendered as a string in the UI. The actual video processing in `VenlowVideoProcessor.kt` uses Android's native `Media3 Transformer` paired with the device's hardware `MediaCodec`.

2. **Decoder Collapse on 8K / High-Bitrate Video:**
   When a user selects an 8K (7680×4320) or high-profile HEVC video, the device's hardware decoder cannot allocate graphic buffers of that magnitude. `MediaCodec` throws an unhandled codec failure, runs out of surface memory, or fails silently.

3. **OpenGL Shader Black-Frame Bug (`UnsharpGlEffect.kt`):**
   To emulate FFmpeg's `unsharp` filter, a custom GLSL fragment shader was written:
   ```glsl
   vec3 sharpened = clamp(center.rgb + uAmount * (center.rgb - blur), 0.0, 1.0);
   gl_FragColor = vec4(sharpened, center.a);
   ```
   In many device surfaces, `center.a` evaluates to 0.0, or the shader program is instantiated outside an active EGL context. This causes the GPU to output transparent/black frames throughout the entire video. The result is an MP4 that has valid audio, but 100% black video.

4. **MediaStore `IS_PENDING = 1` Lock:**
   In `insertVideoIntoMediaStoreGallery()`, files are marked with `IS_PENDING = 1`. If encoding fails, stalls, or is cancelled, the pending flag is never cleared, leaving corrupt, zero-byte, or black thumbnail ghost entries in the user's Gallery.

---

## 5. Domain Instructions & Implementation Plan

### Step 1: Add Real FFmpeg Dependency

Integrate `ffmpeg-kit` into `app/build.gradle.kts`. `ffmpeg-kit` embeds the native FFmpeg C/C++ binaries compiled with `libx264`, `libshine`/`aac`, and scaling/unsharp filters.

Add to `app/build.gradle.kts` dependencies:
```kotlin
// In dependencies block:
implementation("com.arthenica:ffmpeg-kit-full:6.0-2")
// Or if size is a constraint, use ffmpeg-kit-min-gpl:
// implementation("com.arthenica:ffmpeg-kit-min-gpl:6.0-2")
```

*Compatibility Tip:* If `ffmpeg-kit` causes packaging duplicate file conflicts with libc++_shared.so, configure `packagingOptions` in `android { ... }`:
```kotlin
packaging {
    resources {
        pickFirsts.add("lib/**/libc++_shared.so")
    }
}
```

### Step 2: Implement `FfmpegVideoProcessor`

Refactor or supplement `VenlowVideoProcessor.kt` to execute the verified FFmpeg transcoding command asynchronously using coroutines.

The command must replicate the proven parameters:
```bash
-y -i "<inputPath>" \
  [optional duration: -t 30] \
  -vf "scale=1080:1920:force_original_aspect_ratio=decrease,pad=1080:1920:(ow-iw)/2:(oh-ih)/2:black,unsharp=3:3:0.5:3:3:0.0,fps=30,format=yuv420p" \
  -c:v libx264 -preset medium -profile:v high -level 4.0 \
  -b:v 3200k -maxrate 3500k -bufsize 7000k \
  -c:a aac -b:a 128k -ar 44100 -ac 2 \
  -movflags +faststart \
  "<outputPath>"
```

Key execution details:
* **Progress Tracking:** Parse FFmpeg session execution statistics (`FFmpegKitConfig.enableStatisticsCallback`) to compute real percentage `(currentTimeMs / totalDurationMs * 100)` and report it to `onProgressPercent`.
* **Cancellation Support:** Store the active `FFmpegSession` ID and call `FFmpegKit.cancel(sessionId)` when the user triggers `cancelEncoding()`.
* **Output File Location:** Write the temporary transcode to `context.cacheDir/status_otimizado.mp4` before saving to MediaStore.

### Step 3: Remove the Fragile Custom Shader

Deprecate or delete `UnsharpGlEffect.kt`. Because FFmpeg executes the `unsharp=3:3:amount:3:3:0.0` filter directly inside its native video filter graph (`-vf`), manual OpenGL ES shaders are completely unnecessary and must be removed to prevent black-frame regressions.

### Step 4: Fix MediaStore Gallery Insertion

In `insertVideoIntoMediaStoreGallery()`:
1. Wrap the entire write operation in a robust `try-catch-finally` block.
2. If the copy fails or the user cancels, immediately call `resolver.delete(insertedUri, null, null)` so no broken black entries remain in the user's Gallery.
3. Always update `IS_PENDING = 0` and update `MediaStore.Video.Media.SIZE` upon successful write.
4. Call `MediaScannerConnection.scanFile()` on the physical path (`_DATA`) so Samsung, Xiaomi, Motorola, and Google Photos galleries refresh instantly.

---

## 6. Hard Rules

1. **No Black Frames:** The exported video must contain actual visible decoded frames. Never allow an unverified custom shader or missing alpha channel to black out the render.
2. **Handle 8K & Any Source Format:** The transcoding engine must accept 8K, 4K, 60fps, 120fps, HEVC, and HDR videos without crashing. Software FFmpeg handles downscaling gracefully.
3. **Strict WhatsApp Status Compliance:**
   * Container: MP4 with `moov` atom at the front (`-movflags +faststart`).
   * Video Codec: H.264 / AVC (`libx264`), Profile High, Level 4.0.
   * Pixel Format: Strictly `yuv420p` (never 10-bit or 4:2:2 in output).
   * Dimensions: 1080×1920 (9:16 vertical), even dimensions required.
   * Frame Rate: 30 fps.
   * Audio: AAC stereo, 44.1 kHz, 128 kbps.
4. **Preserve ViewModel Contracts:** Keep existing `StatusHdUiState`, `ProcessingPhase`, and callback signatures so the Compose UI components continue to work without breaking changes.

---

## 7. Testing & Verification Guide

After implementing the changes, execute the following validation steps:

### Test Case 1: FFmpeg Command Validation
* Run a local unit test in `src/test/java/com/example/` verifying that `buildEquivalentFfmpegCommand()` produces the exact expected flags:
  `-vf scale=1080:1920:force_original_aspect_ratio=decrease,pad=1080:1920:(ow-iw)/2:(oh-ih)/2:black,unsharp=3:3:0.5:3:3:0.0,fps=30,format=yuv420p`
  `-c:v libx264 -preset medium -profile:v high -level 4.0`
  `-b:v 3200k -maxrate 3500k -bufsize 7000k`
  `-c:a aac -b:a 128k -ar 44100 -ac 2 -movflags +faststart`

### Test Case 2: 8K Downscaling & Memory Resilience
* Test transcoding on a high-resolution sample video (or simulate via `DemoVideoGenerator`).
* Ensure memory usage stays stable and that `FFmpegKit` returns `ReturnCode.isSuccess(session.getReturnCode())`.

### Test Case 3: Gallery Cleanup on Cancellation
* Start an export and immediately invoke `cancelEncoding()`.
* Verify that no zero-byte or `IS_PENDING = 1` files remain in the MediaStore or app cache.

---

## 8. Internal Validation Checklist

Before concluding your task, verify every item:

- [ ] Added `ffmpeg-kit` dependency to `app/build.gradle.kts` and verified build configuration.
- [ ] Refactored media processing in `VenlowVideoProcessor.kt` to execute real FFmpeg commands.
- [ ] Removed or disabled the buggy OpenGL `UnsharpGlEffect` shader.
- [ ] Ensured output pixel format is explicitly set to `yuv420p` and codec to `libx264`.
- [ ] Ensured `-movflags +faststart` is included for instant WhatsApp streaming.
- [ ] Fixed MediaStore insertion to ensure `IS_PENDING` is cleaned up on error or cancel.
- [ ] Verified that Compose UI (`StatusHdScreen.kt`) and `StatusHdViewModel.kt` compile and communicate progress smoothly.
- [ ] Confirmed that 8K/4K high-resolution inputs will no longer trigger hardware codec crashes.
