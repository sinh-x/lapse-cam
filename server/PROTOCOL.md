# TTL-014 protocol v1 — implementation milestone, not a released client

Authority: approved r002 requirements/UAT attached to TTL-014, comment
`c-20261027124822121`. This document explains the implementation; it does not
replace that authority or approve deployment. Android transport/enrollment/history
and the validation wrapper are implemented; physical two-phone integration,
independent review, human UAT and live deployment remain separately gated.

## Network and authorization

Production `serve` requires a non-root process, an exact address currently assigned
to an up `tailscale0` interface, a lowercase certificate-matching `.ts.net` hostname,
an unprivileged port (module default 8443), a current certificate and regular mode0600
key. It binds that address only, rejects non-tailnet peer IPs (including forwarded
identity claims), serves TLS >=1.2 only, and has no HTTP, public, proxy or LAN fallback.
This still requires operator tailnet enrollment/ACL review. No Tailscale configuration
is performed. Client chain/hostname verification must remain enabled.

Each enrolled source is a canonical lowercase UUID. A device token represents 32
random bytes encoded as 64 lowercase hex characters. Use `Authorization: Bearer TOKEN`,
never a URL parameter. The credentials file contains only SHA-256 token hashes.
Revocation is reread before every request; an already authorized in-flight bounded
request may finish. The account uses PBKDF2-HMAC-SHA256/600000 and an independent
16-byte salt. Browser sessions are memory-only, expire after eight hours and are
invalidated by logout, password-verifier changes or server restart. Cookies are
Secure/HttpOnly/SameSite=Strict, path `/`, with no Domain attribute. Login requires
exact Origin; logout additionally requires `X-CSRF-Token` from `/v1/session`.
Five failed logins per rolling minute are allowed per configured identity and peer.
Throttling is process-local; restarting the service invalidates sessions and resets
its rate-limit memory. Browser account credentials never authorize API mutations.

Only the login page and fixed CSS/JS (no archive data) are unauthenticated.
The embedded UI is read-only and inserts metadata using `textContent`, not HTML.

## Request contract

All version-one JSON properties are explicit, including nullable clocks and false
flags. Unknown/duplicate properties, invalid UTF-8, unpaired Unicode surrogates,
trailing JSON and missing properties are rejected. Metadata requests are <=2 MiB,
source batches contain 1–100 records, upload chunks <=8 MiB and pages <=50 entries.
Mutation IDs are canonical UUIDs in `X-Mutation-ID`. Persist the exact request and
ID before sending; do not generate a new ID on network retry.

Successful mutation responses are durably stored with actor, request digest and
mutation ID. A lost response returns the same historical acknowledgment, not a new
edit. Reusing an ID with different content returns 409. Fetch current history after
reconciling an old receipt; never overwrite a newer replica with an old response.

Errors: 400 invalid contract/field/base (including stale resolution), 401 missing or
revoked application authorization, 403 forbidden origin/account/network scope,
404 absent identity, 409 identity/sequence/offset conflict, 413 request bounds,
415 wrong metadata content type, 429 login throttle, 503 storage/auth recovery or
publication uncertainty. Error bodies intentionally omit request data and secrets.
Any non-2xx response is **not** an acknowledgment. Retain pending work; a 503 may
require operator recovery/restart, not an unbounded automatic retry loop.

### Source snapshots — `POST /v1/source`

The body is an array of objects:

```json
[{
  "id": "11111111-1111-4111-8111-111111111111",
  "sequence": 1,
  "source": {
    "createdAt": 100,
    "plannedMinutes": 25,
    "state": "PLANNED",
    "archived": false,
    "deleted": false,
    "timing": {"start": null, "deadline": null, "endElapsed": null,
               "reason": null, "confirmedElapsed": 0},
    "links": []
  },
  "initial": {"title": "Study", "type": "study", "description": "",
              "expectedOutcome": "Read a chapter"}
}]
```

The source namespace comes **only** from the credential, not a body device field.
Sequences strictly increase per source record; duplicate requests reconcile by
mutation ID. Existing records ignore `initial`: source snapshots cannot clobber
server-merged descriptive fields. A newly created record requires exactly the four
valid initial values. A deleted source identity cannot be undeleted/reused.

Timing uses original source clocks, never server wall time for timer execution:
`start={boot,elapsed,epoch}`, `deadline`, `endElapsed`, `reason=USER|EXPIRY|REBOOT`,
`confirmedElapsed`. Planned records have null anchors/zero elapsed. Running records
have start/deadline but no end/reason; ended records preserve confirmed elapsed and
uncertainty. The server validates duration arithmetic/overflow and does not run a timer.

Each capture-time link is
`{id,workId,recordingId,spanId,boot,startElapsed,endElapsed,startEpoch,endEpoch,unknownTail,open}`.
`id=workId:spanId`; all links must belong to this source-local work identity, have a
valid recording ID (`epoch-UUID`), same-boot half-open positive intervals within the
work's original boundaries, and matching elapsed/wall interval lengths. Open links
are provisional. They are **not** compressed-video seek offsets. Empty links are
explicit, not fabricated. Saved work only; drafts never become source snapshots.

### Shared descriptive edits — `POST /v1/patch`

```json
{"device":"aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
 "id":"11111111-1111-4111-8111-111111111111",
 "fields":{"title":{"value":"New title","base":1,"resolve":false}}}
```

Only `title`, `type`, `description`, `expectedOutcome` are legal. Title is nonblank,
1–120 Unicode scalars; description/outcome are 0–2000; category is one of `work`,
`study`, `personal`, `social`, `to-be-sorted`. Accepted edits are never truncated.
Execution, planned duration, archive/deletion and media/link fields are rejected.
The source uses this same patch protocol for later descriptive edits.

A field has `{value,revision,history,variants,changes}`. History maps revision numbers
to accepted values; changes preserve value/base/baseValue/device/mutation provenance.
Revisions start at 1. Independent fields merge. Equal values coalesce without a false
conflict. Unequal stale proposals append preserved variants and advance the field
revision; they do not replace the acknowledged value. An unresolved conflict remains
unresolved even if a later ordinary edit has a fresh base. To resolve, explicitly
send `resolve:true` against the current conflict revision. A stale resolution fails
without erasing any proposal. Revisions, not device wall clocks, are authoritative.

### MP4 upload

1. `POST /v1/uploads/EXPORT_UUID` with mutation ID and
   `{size,sha256,recording}`. Size is a positive byte length, SHA-256 lowercase hex;
   recording is an exact source recording ID or empty for unattributed legacy output.
   The body cannot provide owner/offset/complete. The stable identity is source+export
   UUID, independent of content digest. Conflicting reuse returns 409.
2. `GET /v1/uploads/EXPORT_UUID` reconciles the source's durable manifest/offset.
   Other devices cannot inspect or advance this upload, even if they know its UUID.
3. `PUT /v1/uploads/EXPORT_UUID/chunk` streams binary bytes with `X-Offset` and
   `X-Chunk-SHA256`. Only the exact committed offset is accepted. A chunk's bytes and
   manifest must be fsynced before its offset is acknowledged. For a lost chunk reply,
   GET the manifest rather than assuming a repeated PUT means failure or completion.
4. `POST /v1/uploads/EXPORT_UUID/complete` with a durable mutation ID verifies every
   chunk plus whole size/SHA-256 and publishes the blob before the complete manifest.
   Only then is it listed/playable. This validates transfer integrity, **not** an
   independent MP4 codec/decode inspection; source export verification remains required.
5. `POST /v1/uploads/EXPORT_UUID/missing` with mutation ID records source disappearance;
   it never removes the archive. Local source deletion is not an archive purge.

Only proven app-owned published MP4s may enter the Android queue. No Gallery
search, raw JPEGs, unfinished exports, guessed historical recording links, re-encoding
or source cleanup delay is authorized by this protocol. The server authenticates the
source and transfer integrity; it cannot independently prove a phone's local ownership.

## Reading

- `GET /v1/identity`: bearer source UUID, for explicit enrollment verification.
- `GET /v1/history`: `{records,videos,next}`, complete videos only, no download on list.
  Optional filters: `device`, `type`, `status=PLANNED|RUNNING|ENDED|ARCHIVED|DELETED`,
  `since`, `until` (epoch milliseconds), `limit` (1–50), `after` (opaque last key).
  Pass `next` as `after` with the same filters. Work dates use creation time; video
  dates use archive publication time. Type/status apply to work, not unlinked exports.
  Pagination covers both collections without skipping their different page boundaries.
  This is not an immutable transaction snapshot across pages: refresh from the first
  page to pick up concurrent inserts/edits. Do not use creation-date filtering as an
  incremental edit cursor. Android fetches must build a separate replica and
  retry an interrupted refresh rather than replacing its private WorkSessionStore.
- `GET|HEAD /v1/media/SOURCE_UUID/EXPORT_UUID`: authenticated MP4, byte-range seeking,
  digest ETag and safe MIME/filename. No credential in the URL. Explicit download or
  playback only; capture-time links do not imply MP4 frame/seek correspondence.
- `GET /v1/status`: `{lowStorage,serverTime}`. Low storage means filesystem available
  blocks strictly below 10%; no quota, reserve or automatic deletion. Status is read
  fresh per request; the browser polls at 30s. Phones must fetch it after reconnect.

## Durability and recovery

The private archive is an exclusive-flock-owned, mode0700 directory, with regular
unaliased mode0600 files. `state.json` version 1 contains revisioned records, manifests
and exact mutation receipts. Chunks/blobs are immutable. Every publication follows
write -> file fsync -> rename -> directory fsync. Data becomes durable before a state
reference/acknowledgment. Initialization first commits empty state; an existing lock
with missing state blocks rather than recreating an empty archive.

Faults at every write/publication boundary retain bytes, return no successful receipt
and block uncertain mutations until restart. Restart reads only the committed state,
validates manifest/chunk/blob hashes and preserves all unreferenced staging files.
A retry may reuse an unreferenced chunk/blob **only if byte-identical**; different
retained content blocks recovery rather than being overwritten. No automatic partial
purge exists. Out-of-owner state changes, unknown versions, symlinks and hardlink
aliases fail closed. Private-tree checking is deliberately conservative and currently
linear in archive file count; scale/performance validation remains part of later work.

The archive retains acknowledged copies indefinitely. It is one host, **not an
independent backup**. See [operator/recovery instructions](../docs/PRIVATE_SYNC_SERVER.md).
