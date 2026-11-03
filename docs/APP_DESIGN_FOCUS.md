# Camera-home design and focus safety

## Presentation and navigation

Camera is home; there are no global tabs. Native Views share dark surfaces,
amber primary actions, light text, outlined controls and labelled destructive
states (`AppDesignPolicy` / `AppTheme`). Selected capture chips open the existing
Capture/Timestamp sheet. A session freezes lens, sampling, split, frame rate and
timestamp appearance, including while paused/recovered; the gear still permits
read-only inspection. Blackout remains editable. Raw invalid cap input is retained
and must be corrected before Start.

The direct front/rear button is available only before a session. An unavailable
selected lens is an error, never permission to choose a different lens.
Pause and eligible Finish/export are separate actions. Manual export, source
Delete and published-video actions remain available in Sessions. Details retains
active time, wall span, sampling and shutter/save statistics. Browsing keeps the
preview mounted, not a background capture service. Large text can scroll Camera
rather than pushing essential actions outside an unscrollable screen.

Sessions keeps recycled Today/older-date summaries and all-date attention. Its
processing/attention overview and current-session actions scroll with the list;
selected-session capture and output/recovery information use separate cards.
Timeboxes keeps unstarted/start-date grouping, archive/type/sort state, durable
drafts, independent Save/Start/End, descriptive edits, management guards and links.
End reminders is a child destination explaining its two separate permissions.
Return to Camera uses the existing unsaved-edit confirmation when necessary.

Browsers display LIVE only from the actual capture state, with owner-derived
active duration. Return to Camera is navigation, not Pause/Finish. Private-sync
pages retain parent windows; list filters, page offset, selected identities and
scroll positions are saved for recreation. Restoring a page performs no transfer,
enrollment, retry or edit. An enrollment token is never saved in browser state
and is cleared when its page closes. Unqueued descriptive-editor text is not a
durable proposal; only explicit Queue edit sends it to the existing sync owner.
Shared records remain stale source snapshots, never live remote timers.

## Focus admission and uninterrupted LIVE segments

`CaptureFocus` is a main-thread port. `CaptureViewModel` owns capture admission;
`CameraFocusController` supplies CameraX/Camera2 evidence through the pure,
generation-bound `FocusLockPolicy`:

1. Start/Resume establishes a monotonic deadline of **5,000 ms**. There are no
   shutters or LIVE state before a verified hold. The first failed gate does not
   create a session. Retry is explicit.
2. Fixed-focus optics qualify when the camera reports zero minimum focus distance
   and capture results confirm AF OFF. Autofocus optics first perform one centred
   AF acquisition with auto-cancel disabled. A subsequent focused/locked result
   supplies the distance; the controller requests AF OFF at that distance.
3. Two consecutive stable observations admit capture. Autofocus optics require
   AF OFF, stationary lens and a finite nonnegative distance within 0.01 dioptres
   of the requested distance. Missing/unsupported/rejected evidence fails closed.
4. Capture-result mismatches, capture failures, camera errors or observations
   absent for 1.5 seconds invalidate the hold. Freshness is also checked at shutter
   admission, not just by the watchdog. The model pauses, retains sources and
   offers Retry. An already admitted photo retains its existing completion owner.
5. Ordinary Pause, Finish, replacement binding, backgrounding and teardown cancel
   the focus generation. Old futures, observations and deadlines cannot resume it.
   Automatic session rollover preserves the same hold: no reacquisition, lens
   switch or new AF trigger across rollover, browsing or blackout.

Releasing a hold does **not** clear the last AF-OFF request while a photo drains.
Only an explicit next acquisition clears it, or camera unbinding replaces it.
There is no touch-to-focus handler, manual-distance UI, exposure lock or zoom.
Some lenses may refuse these checks and remain unusable for capture. This is a
safe failure, not a claim that every CameraX-compatible lens supports the feature.

## Blackout and output boundaries

The existing saved/default-on, 30-second eligible-idle policy, motion settling,
modal/error reveal, immersive bars and complete-first-return-input consumption
are unchanged. The opaque cover now presents LIVE, the running timebox title and
prominent remaining time, plus smaller active recording time. Without a running
timebox it says **No timebox running**; unavailable timebox state is not presented
as a known absence. The accessible cover is one reveal node.

Timebox remaining comes from its coordinator's monotonic deadline. Recording time
comes from the capture owner's active-time snapshot, excludes pauses and identifies
legacy/unknown-tail evidence. No display timer writes a new checkpoint or owns a
recording. Camera HUD and blackout content are siblings of the preview output
frame: none is stamped into JPEGs/MP4s. Original-zone shutter timestamps, front
reflection, 720×1280 output, saved appearance, cleanup verification, retry budgets,
media ownership, schemas and sync consent remain with their existing owners.

## Visual and physical verification checklist

The references are the accepted **capture-v003** and **full-app-v002** PNGs for
TTL-019, not generated application screenshots. Compare hierarchy, theme, enabled
states and reachable actions; synthetic scenes and example values are not pixels
to reproduce. This checklist is not a completed visual/UAT record.

| Reference states | Implementation surfaces | Required observation |
| --- | --- | --- |
| capture 01–03 Ready/LIVE/Paused | Camera HUD, chips, direct lens, Pause/Finish | Both lenses; session locks; true state; concurrent export |
| capture 04–05 Blackout/timebox absent | Shared owner clocks and opaque cover | Clock agreement; no-timebox; full first input consumed |
| capture 06–07 Capture/Timestamp settings | Grouped bottom sheet | Every range/default, raw input, reset and read-only session |
| capture 08 Permission | Permission/retry entry and retained Sessions | Denial/retry; no fallback/shutters; saved export still works |
| full-app 01–02 Sessions/details | Recycled date list and recovery cards | Attention, latest evidence, exact guarded actions and restoration |
| full-app 03–07 Timeboxes/planned/running/form/reminders | Existing coordinator-backed views | Draft/edit/IME/Back, independent clocks, management, two permissions |
| full-app 08–12 Private sync/record/enrollment/queue/video | In-Activity pages and explicit prompts | Offline/paging/conflicts, token clearing, per-attempt consent, verified media |

Check 360×640 and 412×892 dp at 100/130/200% system font scale, ≥48 dp touch
targets, ≥4.5:1 text and ≥3:1 control-indicator contrast, IME/insets and TalkBack.
Token contrast host tests do not prove rendered contrast or layout. Authorized,
isolated **Pixel-primary physical checks** must separately verify the five-second
gate, fixed/AF lens behavior, no LIVE refocus, loss/retention/Retry, rollover and
actual backgrounding. Synthetic focus-port tests cannot certify real optics.
Never clear/uninstall the normal app, remove personal media, enroll credentials
or perform network/device operations merely to make a test pass.

For an explicitly authorized fresh TTL-019 fixture context, the debug-only
`-Pttl019Verification=true` build uses `com.sinh.timelapse.ttl019verification`
(and `.test`), labelled **TTL-019 Verification (disposable)**. It is mutually
exclusive with the TTL-018 isolation properties; normal/release identity is
unchanged. Never clear an existing context to satisfy an empty-context guard.
`-e keepScreenAwake true` opts isolated UI fixtures into a test-view wake flag
across recreation, not a lock-screen bypass or global timeout change.
`PhysicalFocusTest` additionally requires `-e ttl019PhysicalFocus true`, CAMERA
permission, an unlocked device and a non-sensitive scene. It captures at least
three photos per lens into exact new private cache roots, retains them, verifies
hold generations while browsing, and never exports/deletes/enrolls. Its
fixture-owned capture also pauses on Activity stop. Synthetic and physical
adapter tests remain distinct from human defocus/full-app UAT.

Host tests/build/lint are not screenshots, device execution, CI, human UAT or
native requirements approval. Preserve the historical validator/CI contract
mismatch: the sync-accounting self-test currently asks for the TTL-011 full hosted
task profile while CI intentionally uses the later host-only profile. Its failure
must be reported, not hidden by changing CI or silently treating another profile
as approval. Candidate-bound results and routine deployment logs live outside Git.
