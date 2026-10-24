# Output-first work sessions with optional timelapse

**Date:** 2026-10-23\
**Status:** Historical discussion record and initial proposed feature roadmap; not an implementation specification or current ticket status.\
**Original action requested:** Save this discussion and create a ticket for session setup in the Android app. No application implementation is part of this documentation task.

## 1. Origin and evolution

The initial brief is `timeboxing-timelapse-initial-ideas.md` in:

`/home/sinh/git-repos/sinh-x/tools/learning-management/projects/lapse-boxing/`

It explores combining an intention, a timebox, a result, reflection, and an optional visual record into a Visual Time Journal. It deliberately separates user direction from suggestions, leaves expected outcome optional, and recommends a manual-first pilot before a full application.

During review, Sinh questioned whether reflection was necessary after every session and emphasized the output/product of the process. The discussion shifted to:

> Produce something useful within a time boundary; inspect the process when there is a reason to.

Sinh accepted this output-first direction, asked what features it would add to the timelapse app/video, and then requested this record and a ticket for session setup.

## 2. Decision and proposal boundaries

| Status | Direction |
| --- | --- |
| Accepted conceptual direction | Useful output or progress is the purpose; reflection is selective, not a compulsory ritual after every session. |
| Requested next action | Document the discussion and create an in-app session-setup ticket. |
| Retained proposal | Three required setup inputs: activity title, activity type, and planned duration. Expected outcome remains optional. |
| Proposed first-version roadmap | Setup, timebox reminder, independent recording controls, optional result note, and linked session history. These are not all included in the setup ticket. |
| Optional later ideas | Video title/result cards, moment markers, review-later flags, and selective export details. |
| Still to refine | Exact setup navigation, defaults and validation limits, work-session lifecycle, timer behavior, storage schema, and recording-to-work-session association. |

This record does not approve a final implementation design, test matrix, device operation, or release. The ticket should preserve unresolved choices rather than silently turn them into requirements.

## 3. Product principles

- **Output first.** The session exists to support the work, not to produce a perfect journal entry.
- **Progress is broader than a finished artifact.** Understanding a concept, eliminating an approach, or identifying a blocker may be useful progress.
- **Activity is not outcome.** “Draft API documentation” and “Finish the diagram and three endpoint descriptions” answer different questions.
- **Timebox completion is not task completion.** A completed timebox may have a partial result.
- **Time is not focus.** Wall-clock elapsed time, active camera time, and accelerated clip duration are different quantities; none establishes cognitive focus.
- **Video is optional evidence.** A missing, failed, deleted, or intentionally disabled recording must not invalidate the work record.
- **No compulsory review.** A satisfactory session can end without watching a clip or answering reflection questions.
- **Private by default.** Do not introduce audio, uploads, sharing, or burned-in personal notes by default.

## 4. Proposed lightweight workflow

### Before work

Enter activity title, activity type, and planned duration. Optionally describe useful progress with an expected outcome. Working notes remain optional and are not required in the first setup slice.

Use sensible defaults and recent choices where useful. Roughly one minute of setup is a design target, not a measured performance claim.

### During work

Work without repeated data-entry prompts. Record only when appropriate. Pausing or stopping the camera should not implicitly end a future work session.

At the planned boundary, the proposed behavior is a reminder with explicit End work / Extend choices, not automatic work closure. Preserve the original planned duration. This belongs to a later timer/lifecycle slice, not session setup.

### At the end

Offer an optional short result: “What changed?” A sentence or a link to the output may be enough. Do not require the original three reflection answers to close a session. If result capture becomes too burdensome, allow it to be skipped or added later.

### Reflect when useful

Possible reasons to inspect notes or selected footage:

- The result differed substantially from the intention.
- The same blocker or overrun keeps recurring.
- Something went particularly well and is worth understanding.
- A recording contains a technique or process worth revisiting.

The original prompts remain useful optional aids: what was accomplished, what disrupted the session, and what to adjust next time.

## 5. Proposed feature roadmap

| Slice | Feature | Scope boundary |
| --- | --- | --- |
| First ticket | Session setup | Capture and retain the work intention and original timebox. |
| Later | Timebox reminder | Remaining time, boundary reminder, explicit end/extend; not the exported-video length cap. |
| Later | Independent recording/work controls | Separate work lifecycle from camera pause, stop, split, and export. |
| Later | Quick result | Optional result text and, if useful, an output link/photo. No mandatory questionnaire. |
| Later | Work-session history | Bring intention, planned/actual timing, result, and associated clips together; support entries without video. |

A work session and a recording segment are not the same entity. Existing automatic splitting means one work session may eventually reference multiple recording segments. Do not use a recording folder as an implicit permanent one-to-one work-session identity.

### Video presentation: optional and deferred

- Optional opening card: activity title and intended timebox.
- Retain the existing capture timestamps as visual context.
- Optional closing card: actual work-session duration and user-selected result text, once those facts are available.
- Keep editable notes outside the video by default. Burning them in makes corrections difficult and may expose private information when sharing.

Opening/closing cards are not needed to validate session setup. Do not infer actual work duration from clip length or active camera time.

### Later conveniences

Moment markers (blocker, milestone, review this), a review-later flag, and choosing which details appear in a shared export are candidates, not commitments.

### Explicit non-goals for now

AI productivity/focus judgments, compulsory scores or reflections, advanced dashboards, automatic public sharing, cloud synchronization, multi-user workflows, and a video-editing subsystem.

## 6. Existing app context and constraints

The repository already has Android interval capture, timestamp rendering, adaptive sampling, recording pause/finish behavior, automatic recording splits, export, and recording-session review. These are documented capabilities; no fresh runtime validation was performed for this discussion.

Important integration constraints:

- Capture is foreground-only. Leaving the app pauses capture; a same-phone timer/form workflow must account for this.
- Existing 5–600-second adaptive maximums limit exported video length, not the duration of the work timebox or retained-photo storage.
- Automatic splits create multiple recording sessions. They must not silently duplicate work intentions or create independent work sessions.
- Private source photos and exported videos have different storage exposure. Exports go to MediaStore/Gallery; device backup/synchronization must not be assumed private or disabled.
- Preserve legacy capture/export, strict metadata validation, exact ownership checks, and non-destructive recovery. Adding work metadata must not weaken media cleanup rules.
- TTL-006 already owns capture-default persistence/recreation validation. Coordinate shared preferences or recreation-test changes; do not duplicate its scope or acceptance claims.

## 7. First ticket brief: in-app session setup

### User story

As someone beginning a bounded piece of work, I want to enter what I will work on and how long I intend to spend, with an optional desired outcome, so the recording can later be understood in the context of the work.

### Proposed field model

| Field | Requirement | Notes |
| --- | --- | --- |
| Activity title | Required | Describe the activity; reject blank/whitespace-only input. |
| Activity type | Required | Small initial category list; defaults/recent choice are useful. Exact taxonomy remains to refine. |
| Original planned duration | Required | Positive duration in minutes. Suggested shortcuts: 25, 45, 60, 90; custom duration proposed. Exact supported limits remain to refine. |
| Expected outcome | Optional | “What would useful progress look like?” Do not block setup when empty. |
| Working notes | Optional, deferred | Not required in the first setup ticket. |
| Work-session ID | Automatic | Stable identity independent of recording filenames/segments. |
| Creation time | Automatic | Do not confuse saving setup with the time actual work starts. |

The eventual field/storage contract should distinguish a work-session record from existing `CaptureSession` media metadata. This document does not choose a database, schema version, or library.

### Proposed acceptance criteria to refine during requirements

1. A discoverable setup action lets the user enter the three required fields and optional expected outcome without replacing existing capture settings.
2. Blank titles, missing activity type, and invalid/non-positive durations receive clear inline validation. An empty expected outcome is valid.
3. Saving valid setup creates one identifiable work-intention record, preserves the original planned duration, and makes the saved values inspectable in the app. Saving must not itself imply that work or recording started.
4. Repeated submission cannot create accidental duplicate records. Cancelling new setup creates no work record and starts no capture/export.
5. Entered form values survive Activity recreation; saved records survive app restart. Define unsaved-draft persistence across process death explicitly before implementation rather than promising it implicitly.
6. Work metadata is not lost when recording sources are cleaned up. Existing recordings without work metadata remain usable without forced backfill.
7. Work duration is clearly separate from maximum MP4 length. Setup must not change sampling, fps, lens, timestamps, rollover, export, or deletion policy.
8. Add focused validation/model tests and UI/persistence regression coverage. Existing safety tests remain intact. Physical device installation/testing requires separate explicit authorization.

### Excluded from this ticket

Countdown execution, notifications, reminders, end/extend/pause semantics for work, outcome/result capture, reflection, multi-clip history UI, automatic recording integration, video cards/overlays, and sharing/synchronization. A full work session without video is an eventual product goal; its complete start/end workflow is not claimed delivered by setup alone.

### Questions for requirements refinement

- Should setup be a separate action or an optional path beside Start capture? Preserve recorder-only use.
- What initial category choices, duration defaults/limits, and field-length limits are appropriate?
- What minimal work-record persistence and inspection UI fits the existing app without building a full journal?
- How should setup hand off to a future explicit work-start action and optional recording association?

## 8. Pilot and learning

An initial five-to-ten-session pilot remains a suggestion, not a user-approved sample size. Use one recurring activity and assess setup/closing friction as well as output usefulness.

Evaluate video separately from journaling: **What did watching the clip reveal that notes or the output artifact did not?** A diff or finished document may explain digital work better; footage may be more valuable for hands-on processes. No productivity improvement or causal effect is assumed.

## 9. Original delivery record

The following records the initial documentation task on 2026-10-23. Later requirements, implementation and ticket-state changes are tracked in TTL-011; the original proposals above do not supersede them.

Created **TTL-011 — Add output-first work-session setup in the Android app** in PA project `timestamp-timelapse`.

- Status: `idea`; assignee: `requirements/team-manager`.
- Intake priority: medium; provisional estimate: M.
- This document is linked as the ticket's primary discussion attachment, not as final approved implementation requirements.
- The ticket includes the proposed field model, validation/persistence acceptance criteria, exclusions, and open design questions above.
- No application implementation, build/device test, commit, or merge was performed for this documentation task.
