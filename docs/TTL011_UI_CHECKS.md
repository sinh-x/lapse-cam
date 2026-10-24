# TTL-011 targeted Pixel UI and sound follow-up

Deployment `d-eeedd5`, following Sinh's reports of an oversized Work Back button,
missing title keyboard, unclear reminder-permission buttons and inaudible expiry.
These are targeted implementation checks, **not** independent review or complete
TS-1–14 acceptance. At this checkpoint source was uncommitted on the selected
TTL-011 branch; subsequent Archive/Delete work and authorized commit/CI progress
are described in [the extension](TTL011_ARCHIVE_DELETE.md) and external ticket receipts.

## Changes made

- Replaced Work's dynamic AlertDialog with a native same-Activity `WorkDialog`.
  AlertDialog inferred a read-only initial page and could set ALT_FOCUSABLE_IM
  before the setup editors existed. The new window explicitly permits IME input.
- Replaced the large footer Back button with an accessible 48dp top-left arrow.
  Header Back navigates inside Work; system Back first dismisses the IME. Copy
  setup Back returns to the ended source detail, retaining the draft.
- Added explicit title input type/Next actions, persistent field labels, wrapping
  type prompt, scrolling status/error content, a visible Resume draft entry and
  current-work View/End controls. Save locks the form during publication and
  focuses the first invalid field; a stored setup route waits for work loading
  before creating/restoring its draft.
- Always show the empty-link explanation, and display creation dates locally.
- Replaced the two unexplained permission actions with an expandable **End reminder
  settings** group. It separately explains and shows notification/background-timing
  access. Already-allowed actions say Allowed and are disabled. A blocked channel
  directs to Android notification settings; runtime denial gives explicit feedback
  and an optional settings action. No permission is requested automatically.
- Added pure access-policy tests and Android window/editor, draft-load and
  reminder-control regression fixtures. Android fixtures are compiled, not run.

## Latest local checks / installation

Existing JDK17/SDK36, offline aggregate
`testDebugUnitTest assembleDebug assembleDebugAndroidTest lintDebug`: **exit0**.
Host XML: **63/63**, failures/errors/skips/ignored0. Android inventory: **75**,
compiled only. Both APKs build. Lint **0 errors / 56 warnings**: SetTextI18n39,
UseKtx13, LockedOrientationActivity1, DiscouragedApi1, DataExtractionRules1,
ViewConstructor1. Prior group-level proposed maintenance dispositions still apply;
these are not independent review sign-off. Accounting self-tests pass6/6 and19/19.
`git diff --check` passes. Original test files/media safeguards remain unchanged.

Installed app SHA-256 (local and on-device base.apk match):
`fb7c1e72299fdd80765097e6346d52d942a248a9372afe107eac59b258790a35`.
Built test APK, **not installed in this follow-up**:
`50ff48cee146c5c4782c815722447dbdefd1a6cd81dc858e8b234a85489b0678`.

Sinh explicitly authorized updating and checking Work with synthetic records,
then separately authorized using the existing blank-title Work/60-minute draft
for that test. Updates used `adb install -r --user 0`, without clearing or
uninstalling. Device identity was explicitly checked: Pixel8Pro/API37/
build2026100601/user0. No camera capture/export, source deletion, permission
change by the agent, force-stop, reboot, push or merge was performed.

## Actual UI observations

On the updated physical Pixel, with its existing font/display configuration:

- Title tap visibly opened the soft keyboard. `mInputShown=true`, a live editor
  input connection and the actual screenshot independently confirmed this.
  The compact Back target measured108px at density2.25 (=48dp). Form fields,
  Save/Discard and the header were reachable above the keyboard.
- Blank title was rejected while retaining the form. A synthetic title and the
  original60-minute values survived Back → Resume draft exactly. Save created a
  planned intention without capture or automatic Start.
- Synthetic B was explicitly saved, started and manually ended. No LIVE indicator
  appeared. Copy as new prepopulated its metadata; Back returned to its ended
  source detail. The retained copied draft was explicitly discarded, leaving the
  saved source record intact.
- A fresh setup started blank with25 minutes and an unselected type. Zero duration
  was rejected without a work record/Start control. Correcting to1 minute saved
  C planned; omitted type became To-be-sorted and blank outcome was accepted.
- C started explicitly and really expired after one minute. Its detail showed
  ended/EXPIRY and confirmed elapsed60000ms, preserving original plan1. Android's
  SystemUI also displayed the generic Timebox ended notification. The heads-up
  notification temporarily replaced the accessibility hierarchy; automation
  snapshots during that transition are not app failures.
- End reminder settings visibly showed Notifications: Allowed / Background timing:
  Allowed, with both already-granted buttons disabled. This verifies permission
  feedback, not guaranteed audible delivery.

Synthetic work records were deliberately retained (Work has no delete action):
A planned, B manually ended, C expired, and two subsequent expired sound trials.
They are labelled `TTL011 ...`; no existing recording/source was removed.

## Sound diagnosis and human confirmation

Read-only checks showed notification volume5/7, speaker route, a DEFAULT-importance
Timebox reminders channel with the system notification sound and no DND bypass.
DND was **on** with reminders suppressed, explaining why an end notification could
be posted without audible playback. The default sound resolved to **Pixie Dust**.
The agent did not change channel settings, volume or bypass DND.

Sinh chose to disable DND personally. The agent then verified zen_mode0 and ran a
fresh, identity-distinct one-minute timebox through the production UI/scheduler.
Android posted its matching notification; the first listening response was
**Unsure — I missed it**, so that trial was not claimed audibly passing.

Sinh requested another trial. A fresh one-minute copied timebox was saved/started,
and a warning was given at approximately7 seconds remaining. Its new notification
was posted with DND still0. Sinh then explicitly answered **Yes — I heard it**.
This is human-confirmed audible end-notification playback on this device/config,
not a custom two-note sound, a timing SLA, DND bypass, or every-device guarantee.
DND remains under the operator's control; the agent did not restore/change it.

## Evidence and remaining gaps

Host reports/logs, restricted UI XML/screenshots and synthetic notification-ID
receipts are retained under ignored
`app/build/ttl011-phase-evidence/d-eeedd5-ui-followup/` and the restricted host
`/tmp/ttl011-work-ui-check/`. Initial failures are retained: empty /dev/tty dumps
until allocating a TTY, auto-lock/transient SystemUI focus, a synthetic ASCII label
transformed by the Vietnamese keyboard, and an ElementTree truthiness error in a
host-side keep-visible helper. The latter helper failed to keep the display awake;
the actual timer still expired and posted its notification. These were not hidden,
relabelled as full passes or solved by clearing data/relaxing application tests.
A bounded wake-key helper was stopped; no screen-timeout setting was changed.

Still required: full75 Android execution in a genuinely suitable authorized
context, independent exact-candidate V1–V7/review, matching hosted CI and complete
TS-1–14. This trial did not certify real TalkBack,200% system-font/insets combinations,
active-camera navigation/rollover links, permission revocation, force-stop or reboot.
At this checkpoint, small UX follow-ups remained: singular duration grammar read
“1 minutes”, and field errors cleared only on the next Save. Both were subsequently
fixed in the Archive/Delete extension.
Reminder Ready describes permissions, not a check of DND/volume/channel audibility;
an explicit sound-check/status UI is a possible later refinement. Do not silently
bypass system quiet settings to promise an audible alert.
