# Linux / NixOS webcam timelapse exploration

**Status:** Exploration only; not an implementation specification or approved roadmap.

**Requested action:** Record ideas about using Timestamp Timelapse on this NixOS computer with its webcam. Do not create a ticket.

**Scope of this change:** Documentation only. No application implementation, package installation, system configuration changes or camera capture.

## 1. Question and conclusion

Can the current Android app be installed on NixOS and use the computer's webcam?

**Not directly as a native Linux application.** The current project targets Android 16+ (API 36) and uses Android-specific camera, UI, storage and media APIs. Its existing Nix flake provides an ADB-only development shell, not a Linux application package.

A native Linux version or companion is feasible in principle. It would be **Linux-compatible rather than inherently NixOS-specific**; Nix packaging would make its dependencies and launch process reproducible on NixOS. Packaging alone cannot turn the existing Android APK into a native Linux app.

The smallest useful exploration is webcam interval capture with capture-time timestamps and MP4 export. Reproducing the entire Android application's UI, adaptive recording, recovery and work-session features is a substantially broader effort.

No implementation approach has been selected or authorized by this document.

## 2. Findings from this discussion

Read-only inspection of this computer and repository found:

| Area | Observation | What it does not establish |
| --- | --- | --- |
| Host | Linux x86_64; Nix tooling available | A desktop application has been built or installed |
| Webcam | Azurewave integrated RGB camera appears in Linux video-device metadata | Successful frame capture, image quality or supported capture modes |
| Device access | The current user has read/write access to the present `/dev/video*` nodes and belongs to the `video` group | Every node is a usable image stream, or the camera is not busy |
| Device identity | A `/dev/v4l/by-id/` link exists for the integrated RGB camera | That numeric `/dev/videoN` names will remain stable, or that a particular node has been capture-tested |
| Media tooling | Installed FFmpeg reports V4L2 input, `drawtext` and `libx264` support | Successful end-to-end timestamped webcam timelapse export |
| Existing app | CameraX, native Android views, MediaCodec, MediaMuxer and MediaStore are used | Native Linux compatibility |

These are observations from the earlier feasibility check in this conversation, not ongoing health checks or runtime acceptance evidence. The webcam was not activated and no images were captured. No additional package or system permission changes were shown to be necessary by this inspection.

## 3. Possible approaches

### A. Small Linux companion — suggested first exploration

Build a narrowly scoped capture tool using Linux webcam access and FFmpeg for image/video processing. A command-line prototype could establish the pipeline before choosing a UI.

Potential benefits:

- Validate actual camera capture and timestamped export with little initial UI work.
- Use the media tooling already available on this computer.
- Keep the existing Android application unchanged.

Trade-offs:

- Initially a subset of Android functionality, not feature parity.
- Session safety, scheduling and recovery still need application logic; FFmpeg alone does not provide those guarantees.
- Even a small tool needs clear start/stop controls and privacy boundaries.

This is a recommendation for investigation, not a commitment to a framework or implementation.

### B. Desktop application with a reusable core

Create a desktop UI and Linux capture/export adapters while evaluating whether platform-independent Kotlin policies can be shared with Android.

Possible reusable concepts include adaptive frame selection, timing calculations, session states and validation rules. Actual source reuse requires checking dependencies, extracting suitable modules and testing both platforms. Android filesystem/media ownership assumptions must not simply be copied into a desktop backend.

This is a stronger candidate if long-term Android/Linux parity matters, but requires more design and maintenance than a small companion. No desktop framework has been chosen.

### C. Browser-based interface

A browser UI could provide camera preview and controls through browser camera permissions, with local storage and/or a local export backend.

Questions include reliable interval scheduling, background-tab throttling, suspend behavior, persistent photo storage and FFmpeg integration. A local backend adds lifecycle and security responsibilities; it should not expose the camera or recordings over the network by default.

This is an alternative interface architecture, not an assumption that a browser eliminates platform-specific work.

### D. Android compatibility layer or emulator

Investigate running the existing APK inside an Android environment with webcam passthrough.

This may avoid some application porting, but adds Android-version, camera-passthrough, graphics and host-integration constraints. Current API 36 requirements must be checked against the chosen runtime. Nothing in this discussion validates emulator or Waydroid compatibility on this machine.

This is not the suggested first path for a small native webcam utility.

## 4. Candidate minimal workflow

A potential first prototype could support:

1. Explicitly select a webcam and preview it before recording.
2. Choose a fixed capture interval, output directory and export frame rate.
3. Start interval-photo capture with a clearly visible recording state.
4. Explicitly stop capture, retaining completed photos and timing metadata.
5. Export a timestamped H.264 MP4 and report its exact location.
6. Review the video before any separately confirmed source deletion.

Candidate scope boundaries:

- One camera and one active recording at a time.
- Local-only operation; no audio, uploads or automatic sharing.
- Fixed intervals first; adaptive sampling and automatic splitting deferred.
- No automatic source cleanup in the first exploration.
- No automatic start on login or background recording service.
- No work-session timer, journal, Android session import or cross-device sync initially.

These are proposed simplifications, not approved requirements. Preview/UI work could follow an initial command-line capture/export experiment.

## 5. Design questions worth resolving

### Capture and time semantics

- Enumerate capture-capable devices and supported modes; do not assume every `/dev/video*` node supplies images.
- Prefer stable device identities where available and explicitly handle missing or busy cameras. Do not silently switch to another camera.
- Record each frame's actual capture-request wall-clock time and original time zone. Describe this honestly as request timing, not sensor-exposure timing.
- Use monotonic time for cadence and active-duration calculations; decide what happens after missed slots, suspend, lock or interruption.
- If burning timestamps during later export, use the saved per-frame times, not the export-time clock. Live FFmpeg text overlays alone do not resolve this distinction.
- Decide landscape versus portrait output, cropping, preview mirroring and whether saved output is mirrored. Android's portrait defaults need not become Linux defaults.

### Storage, export and recovery

- Define a private session directory and metadata format; avoid overwriting unrelated files or recordings.
- Preserve completed sources on capture/export failure and detect low disk space.
- Keep capture and export timing separate: capture interval determines real-world sampling, while export fps determines playback duration.
- Decide whether initial export uses software H.264 only; hardware encoding is an optional later investigation, not a prerequisite.
- Validate output before declaring success. Any future automatic cleanup needs its own ownership, verification and recovery design.
- Adaptive selection limits video length, not retained-photo storage; do not imply a video cap bounds disk use.

### Privacy and desktop behavior

- Require explicit user action before camera activation, including preview, and make active camera use visible.
- Decide whether minimizing, closing the UI, screen locking or suspending pauses or ends capture. Do not silently assume Android foreground-only behavior maps to Linux.
- Do not change groups, udev rules, system services or NixOS configuration automatically to work around a permission failure.
- Keep notes and personal work details out of burned-in video unless explicitly selected.

## 6. Nix / NixOS packaging ideas

If implementation is later requested:

- Add a separate Linux development shell and, when useful, an executable package or app entry point.
- Preserve the current ADB-only shell's purpose rather than silently changing it.
- Pin dependencies and expose the required FFmpeg features explicitly.
- Avoid machine-specific absolute paths and reliance on globally installed packages.
- Treat device permissions and camera availability as runtime checks; a Nix package does not grant camera access.
- Keep system-level integration optional and separately approved. A system service is unnecessary for a user-launched prototype.

The presence of a working FFmpeg binary here is useful feasibility evidence, not a finished packaging design or permission to fetch dependencies.

## 7. Open product choices

Before implementation, discuss:

- Is a small Linux companion sufficient, or is eventual Android feature parity the goal?
- Is the preferred first interface command-line, native desktop or browser-based?
- What should happen when the laptop locks, suspends or the application loses focus?
- Should recordings default to landscape or portrait, and should webcam output be mirrored?
- Which Android features, if any, are essential for the first Linux version?
- Is this computer's integrated camera the only initial target, or should external USB webcams also be explored?

The user's request to save these ideas does not decide these questions.

## 8. Possible next investigation — not performed

If separately requested, a bounded next step would be to choose an interface direction, explicitly authorize a short webcam test, identify a usable capture mode, and verify a small timestamped capture/export pipeline in an agreed local directory. Runtime results would then guide packaging and architecture choices.

**No ticket has been created for this exploration. No implementation, dependency retrieval, installation, camera test or release is authorized by this document.**

## Related context

- [Project README](../README.md): current Android capabilities and build constraints.
- [Output-first work sessions exploration](OUTPUT_FIRST_WORK_SESSIONS.md): related product ideas; not a requirement to reproduce them on Linux.
