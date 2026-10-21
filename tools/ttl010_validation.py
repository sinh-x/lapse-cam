#!/usr/bin/env python3
"""TTL-010 Phase 1 PARTIAL: source/evidence storage, NOT an authorized runner.

Only selftest is implemented end-to-end. Other CLI commands fail closed before
observing real state, creating stage records, or launching any process. Library
code supports restricted evidence storage and read-only frozen source discovery;
it is not yet integrated with a trusted launch/operator gate. Comparators and
stored process receipts do not authenticate permission, completion or raw proof.
Selftests use isolated temporary files, never the production evidence root.
No caller JSON, environment string, comment ID or made-up token grants authority.
"""

import argparse
import contextlib
import fcntl
import hashlib
import io
import json
import os
import re
import stat
import subprocess
import tempfile
import unittest
import uuid
import xml.etree.ElementTree as ET
from collections import Counter
from pathlib import Path


LEDGER_ROOT = "/home/sinh/Documents/ai-usage/agent-teams/builder/artifacts/TTL-010/validation/"
BASE_SHA = "4b4a6ee17492035c9f41a91b02fd93d5ff3f89f0"
FEATURE_BRANCH = "feature/TTL-010-encoder-diagnosis"
TEST_ROOTS = {"host": "app/src/test", "device": "app/src/androidTest"}
TEST_FLOORS = {"host": 42, "device": 32}
MAX_RECORD = 16 * 1024 * 1024
CHECKOUT_ATTRIBUTES = b"* text=auto eol=lf\n*.bat text eol=crlf\n*.jar binary\n"
PIN_PATHS = frozenset((
    ".gitattributes", "build.gradle.kts", "settings.gradle.kts", "app/build.gradle.kts",
    "gradle.properties", "gradle/wrapper/gradle-wrapper.properties",
    "gradle/wrapper/gradle-wrapper.jar", "gradlew", "gradlew.bat",
    "flake.nix", "flake.lock",
))
NATIVE = ("./gradlew", "--offline", "--no-daemon", "--console=plain",
          "--rerun-tasks", "-Pandroid.builder.sdkDownload=false")
HOST_ARGV = NATIVE + (":app:testDebugUnitTest", ":app:assembleDebug",
                      ":app:assembleDebugAndroidTest", ":app:lintDebug")
PRESERVATION = ("-Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true",
                "-Pandroid.experimental.testOptions.uninstallIncompatibleApks=false")
FINAL_CLASSES = "com.sinh.timelapse.MediaPipelineTest,com.sinh.timelapse.AdaptiveSessionTest,com.sinh.timelapse.TimestampAppearanceTest"
FOCUSED_CLASSES = ",".join((
    "com.sinh.timelapse.MediaPipelineTest#ambiguousOwnershipAndCleanupFailureRetainRecordsForExplicitRetry",
    "com.sinh.timelapse.MediaPipelineTest#interruptedExportsCleanOnlyTheirPendingOutputsAndPreservePublishedVideos",
    "com.sinh.timelapse.AdaptiveSessionTest#adaptiveMediaUsesExactlyFrozenSelectionAtEveryFpsWithoutModifyingPhotos",
    "com.sinh.timelapse.MediaPipelineTest#repeatedRapidExportsKeepExactFramesAcrossFpsCountsAndScenes",
))
INCOMPLETE = (
    "trusted launch/operator authorization and cache/writer-exclusion integration",
    "integration of read-only source/HEAD/branch/clean/pin/method binding with ticket/tool admission",
    "trusted admission/exclusion and production integration of restricted shared evidence store",
    "preflight/host/focused/final lifecycle and authenticated process settlement (not supplied receipts)",
    "absent-only report archival, fresh markers and restricted raw stream/exit capture",
    "native host execution, fresh APK/XML/lint collection and warning dispositions",
    "non-starting ADB server/client compatibility and private explicit device selector",
    "fresh device/disposable-context permission and installed APK hash preservation",
    "single focused/final invocation, own-process-tree timeout and uncertain custody",
    "raw 48-vector completion, exact media timing/counts/cap and JPEG invariance",
    "raw-evidence verification, original-failure links and causal/unresolved diagnosis",
    "production redacted summary/hash manifest and separate pending gate records",
)


class Invalid(ValueError):
    """Generic errors only: never expose untrusted XML, paths or device identities."""


def require(condition: bool, reason: str) -> None:
    if not condition:
        raise Invalid(reason)


def digest(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def hashes_equal(before: dict[str, str], after: dict[str, str], *, pins: bool = False) -> None:
    require(bool(before) and before == after, "hash inventory drift or missing inventory")
    require(all(re.fullmatch(r"[0-9a-f]{64}", v) for v in before.values()), "invalid hash")
    if pins:
        require(set(before) == PIN_PATHS, "incomplete pin inventory")


def safe_xml(raw: bytes) -> ET.Element:
    """Bounded UTF-8 XML only; no DTD, entities, external reads or namespaces."""
    require(0 < len(raw) <= MAX_RECORD, "XML size rejected")
    try:
        text = raw.decode("utf-8")
        require("\x00" not in text and "<!DOCTYPE" not in text.upper()
                and "<!ENTITY" not in text.upper(), "DTD/entities/encoding rejected")
        root = ET.fromstring(text)
    except (UnicodeError, ET.ParseError):
        raise Invalid("invalid XML") from None
    require(all(isinstance(node.tag, str) and not node.tag.startswith("{") for node in root.iter()),
            "XML namespaces rejected")
    return root


def xml_methods(reports: list[tuple[bytes, int]], marker_ns: int,
                expected: set[tuple[str, str]]) -> int:
    """Compare raw UTF-8 XML bytes/mtimes to an independently established method set.

    Neither timestamps nor expected sets are authenticated here. Production source
    discovery, no-follow artifact reads, marker/clock provenance remain incomplete.
    """
    require(bool(reports) and marker_ns > 0 and bool(expected), "missing XML/marker/methods")
    seen: list[tuple[str, str]] = []
    for raw, modified_ns in reports:
        require(modified_ns > marker_ns, "stale XML")
        root = safe_xml(raw)
        require(root.tag in ("testsuite", "testsuites"), "unknown XML root")
        suites = [root] if root.tag == "testsuite" else list(root)
        require(bool(suites) and all(s.tag == "testsuite" for s in suites), "unknown XML suites")
        if root.tag == "testsuites":
            require(root.get("tests", str(len(root.findall('.//testcase'))))
                    == str(len(root.findall('.//testcase'))), "aggregate count mismatch")
            require(all(root.get(k, "0") == "0" for k in ("failures", "errors", "skipped", "disabled")),
                    "nonzero aggregate outcome")
        for suite in suites:
            cases = suite.findall("testcase")
            require(suite.get("tests") == str(len(cases)), "suite count mismatch")
            require(all(suite.get(k) == "0" for k in ("failures", "errors", "skipped"))
                    and suite.get("disabled", "0") == "0", "nonzero/missing suite outcome")
            require(all(n.tag in ("testcase", "properties", "system-out", "system-err")
                        for n in suite), "unknown/nested suite content")
            for case in cases:
                require(not list(case) and case.get("status", "run") == "run"
                        and case.get("disabled", "false") == "false"
                        and case.get("result", "completed") == "completed", "non-passing case")
                seen.append((case.get("classname", ""), case.get("name", "")))
        require(len(root.findall(".//testcase")) == sum(len(s.findall("testcase")) for s in suites),
                "hidden testcase")
    require(Counter(seen) == Counter(expected), "duplicate/missing/extra/wrong-class method")
    return len(seen)


def lint_occurrences(raw: bytes) -> dict[str, dict]:
    """Index every occurrence in native lint format 6, not just warning IDs.

    Values can contain private absolute paths/messages. Do not print them. Keys
    bind all parsed attributes/locations; the separate raw digest binds original
    bytes too. This comparison cannot detect a suppressed detector or authenticate
    the invocation/configuration. Source review and native process proof remain
    separate gates. Unsupported report shapes/severities fail closed.
    """
    root = safe_xml(raw)
    require(root.tag == "issues" and root.get("format") == "6", "unsupported lint report")
    require(not (root.text or "").strip(), "unexpected lint text")
    found = {}
    for issue in root:
        require(issue.tag == "issue" and issue.get("id") and issue.get("message")
                and not (issue.text or "").strip() and not (issue.tail or "").strip(),
                "invalid lint occurrence")
        severity = issue.get("severity")
        require(severity in ("Warning", "Information"), "lint errors or unsupported severity")
        locations = []
        for location in issue:
            require(location.tag == "location" and location.get("file")
                    and not list(location) and not (location.text or "").strip()
                    and not (location.tail or "").strip(), "unsupported lint location")
            for key in ("line", "column"):
                value = location.get(key)
                require(value is None or re.fullmatch(r"[1-9][0-9]*", value),
                        "invalid lint coordinate")
            locations.append(dict(location.attrib))
        require(bool(locations), "lint location missing")
        observation = {"attributes": dict(issue.attrib), "locations": locations}
        key = digest(canonical_json(observation))
        require(key not in found, "duplicate lint occurrence")
        found[key] = observation
    return found


def lint_dispositions(raw: bytes, ledger: dict) -> dict:
    """Compare an exact occurrence ledger; caller rationales are NOT approval.

    Empty reports need an explicitly empty ledger. No baseline count, ID-level
    blanket waiver, supplied error count or historical digest can supply a pass.
    Only digests/counts are returned, not private paths, messages or rationales.
    """
    require(isinstance(ledger, dict) and set(ledger) == {"lint_sha256", "rationales"}
            and ledger["lint_sha256"] == digest(raw), "lint ledger/report binding mismatch")
    occurrences = lint_occurrences(raw)
    rationales = ledger["rationales"]
    require(isinstance(rationales, dict) and set(rationales) == set(occurrences),
            "lint disposition occurrence mismatch")
    require(all(isinstance(reason, str) and 0 < len(reason.strip()) <= 4096
                and not any(ord(c) < 32 and c not in "\n\t" for c in reason)
                for reason in rationales.values()), "lint rationale missing or unsafe")
    counts = Counter(value["attributes"]["severity"] for value in occurrences.values())
    return {"lint_sha256": digest(raw), "warnings": counts["Warning"],
            "information": counts["Information"], "occurrence_sha256": sorted(occurrences),
            "comparison_only": True, "approval_authenticated": False}


def encoder_mismatches(raw: bytes) -> dict:
    """Extract the CURRENT VideoExporter diagnostic from a restricted UTF-8 log.

    Missing diagnostics do not mean no failure, and matching fields do not prove
    a cause. Retain primary/suppressed errors and the full original independently.
    No XML/escaped-log decoding, wrapped-line repair, codec inference or truncation
    is performed. Output still belongs in restricted evidence (codec/PTS values).
    """
    require(0 < len(raw) <= MAX_RECORD, "diagnostic log size rejected")
    try:
        text = raw.decode("utf-8")
    except UnicodeError:
        raise Invalid("diagnostic log is not UTF-8") from None
    prefix = "Encoder produced an unexpected frame count:"
    pattern = re.compile(re.escape(prefix) +
        r" codec=([A-Za-z0-9_.-]{1,128}), fps=([0-9]+), expected=([0-9]+), "
        r"submitted=([0-9]+), samples=([0-9]+), configBuffers=([0-9]+), "
        r"eosFlags=([0-9]+), recentPtsUs=\[(-?[0-9]+(?:, -?[0-9]+)*)?\]")
    matches = list(pattern.finditer(text))
    require(len(matches) == text.count(prefix), "incomplete encoder diagnostic")
    observations = []
    for match in matches:
        # Limit digits BEFORE int conversion; bound every field to Kotlin's type.
        fields = match.groups()
        require(all(len(value) <= 10 for value in fields[1:7]), "oversized diagnostic integer")
        fps, expected, submitted, samples, configs, eos = map(int, fields[1:7])
        require(1 <= fps <= 30 and 1 <= expected <= 2147483647
                and all(0 <= value <= 2147483647 for value in (submitted, samples, configs, eos)),
                "diagnostic integer out of range")
        times = fields[7].split(", ") if fields[7] else []
        require(len(times) <= 8 and all(len(value) <= 20 for value in times),
                "diagnostic PTS bound exceeded")
        pts = [int(value) for value in times]
        require(all(-(2 ** 63) <= value < 2 ** 63 for value in pts), "diagnostic PTS out of range")
        observations.append({"codec": fields[0], "fps": fps, "expected": expected,
                             "submitted": submitted, "samples": samples, "config_buffers": configs,
                             "eos_flags": eos, "recent_pts_us": pts})
    return {"raw_sha256": digest(raw), "observations": observations,
            "cause": "unknown", "comparison_only": True}


def device_facts(rows: str, selected: str, state: str, identity: tuple[str, str, str],
                 expected: tuple[str, str, str]) -> None:
    """Pure comparison only; must never query ADB or act as device authorization."""
    lines = rows.splitlines()
    require(bool(lines) and lines[0] == "List of devices attached", "invalid device listing")
    parsed = [line.split() for line in lines[1:] if line.strip()]
    require(bool(selected) and len(parsed) == 1 and len(parsed[0]) >= 2, "device row count")
    require(parsed[0][:2] == [selected, "device"] and state == "device", "device selection/state")
    require(identity == expected and all(identity), "device identity mismatch")


def fixture_row_matches(allocation: dict[str, str], row: dict[str, str]) -> bool:
    """Read-only corroboration, NOT deletion permission; no cleanup operation exists."""
    keys = {"uri", "name", "owner", "path", "id"}
    if set(allocation) != keys or not all(allocation.values()):
        return False
    try:
        if str(uuid.UUID(allocation["id"])) != allocation["id"]:
            return False
    except ValueError:
        return False
    return (row == allocation and allocation["name"] == f'Timelapse-{allocation["id"]}.mp4'
            and re.fullmatch(r"content://media/external/video/media/[1-9][0-9]*", allocation["uri"]) is not None
            and allocation["owner"] == "com.sinh.timelapse"
            and allocation["path"] == "Movies/TimestampTimelapse/")


def redact_known(text: str, serial: str) -> str:
    """Only exact known-serial redaction. Unknown/encoded identifiers need raw audit.

    This is not a generic safe-to-publish classifier and is not used for raw logs.
    """
    require(bool(serial) and not any(c.isspace() for c in serial), "missing private selector")
    return text.replace(serial, "[device]")


def device_argv(stage: str) -> tuple[str, ...]:
    require(stage in ("focused", "final"), "invalid device stage")
    classes = FINAL_CLASSES if stage == "final" else FOCUSED_CLASSES
    return NATIVE + PRESERVATION + (":app:connectedDebugAndroidTest",
                                   "-Pandroid.testInstrumentationRunnerArguments.class=" + classes)


class LedgerFixture:
    """Storage seam used ONLY inside isolated selftests, never the production root.

    An already-open owner-only directory FD avoids ancestor symlink traversal here.
    Production root admission, shared locks, process receipts and reconciliation
    are NOT implemented. Existence consumes a fixture slot even after a crash.
    """

    def __init__(self, directory_fd: int):
        info = os.fstat(directory_fd)
        require(stat.S_ISDIR(info.st_mode) and stat.S_IMODE(info.st_mode) == 0o700
                and info.st_uid == os.getuid(), "unsafe ledger directory")
        self.fd = directory_fd

    def read(self, name: str) -> bytes:
        require(re.fullmatch(r"(?:focused|final)\.(?:intent|result)", name) is not None, "unsafe record name")
        fd = os.open(name, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK, dir_fd=self.fd)
        try:
            info = os.fstat(fd)
            require(stat.S_ISREG(info.st_mode) and stat.S_IMODE(info.st_mode) == 0o600
                    and info.st_uid == os.getuid() and info.st_nlink == 1
                    and info.st_size <= 65536, "unsafe ledger record")
            return os.read(fd, 65537)
        finally:
            os.close(fd)

    def claim(self, stage: str, binding: bytes) -> None:
        require(stage in ("focused", "final") and 0 < len(binding) <= 65536, "invalid claim")
        # O_EXCL is the atomic ticket-slot exclusion primitive. Partial writes are
        # intentionally retained: never treat an incomplete intent as free budget.
        fd = os.open(stage + ".intent", os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW,
                     0o600, dir_fd=self.fd)
        try:
            with os.fdopen(fd, "wb") as stream:
                stream.write(binding)
                stream.flush()
                os.fsync(stream.fileno())
        finally:
            os.fsync(self.fd)

    def result(self, stage: str, binding: bytes, exit_code: int) -> None:
        require(type(exit_code) is int, "unknown completion")
        require(self.read(stage + ".intent") == binding, "binding drift")
        temporary = ".result-" + uuid.uuid4().hex
        fd = os.open(temporary, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW,
                     0o600, dir_fd=self.fd)
        try:
            with os.fdopen(fd, "wb") as stream:
                stream.write(binding + b"\nexit=" + str(exit_code).encode("ascii"))
                stream.flush()
                os.fsync(stream.fileno())
            # Atomic absent-only publication, never replace an original result.
            os.link(temporary, stage + ".result", src_dir_fd=self.fd,
                    dst_dir_fd=self.fd, follow_symlinks=False)
        finally:
            os.unlink(temporary, dir_fd=self.fd)
            os.fsync(self.fd)


def strict_json(raw: bytes) -> object:
    """Bounded canonical-ledger reader; reject duplicate keys and non-JSON numbers."""
    require(0 < len(raw) <= MAX_RECORD, "invalid JSON record size")
    def pairs(items):
        result = {}
        for key, value in items:
            require(key not in result, "duplicate JSON key")
            result[key] = value
        return result
    def invalid_number(value):
        raise Invalid("non-finite JSON number")
    try:
        value = json.loads(raw.decode("utf-8"), object_pairs_hook=pairs, parse_constant=invalid_number)
        def bounded(item, depth=0):
            require(depth <= 64, "JSON record too deep")
            if isinstance(item, dict):
                for key, child in item.items():
                    require(not any(0xD800 <= ord(c) <= 0xDFFF for c in key), "invalid JSON scalar")
                    bounded(child, depth + 1)
            elif isinstance(item, list):
                for child in item:
                    bounded(child, depth + 1)
            elif isinstance(item, str):
                require(not any(0xD800 <= ord(c) <= 0xDFFF for c in item), "invalid JSON scalar")
        bounded(value)
        return value
    except (UnicodeError, ValueError, RecursionError):
        raise Invalid("invalid JSON record") from None


def canonical_json(value: object) -> bytes:
    return (json.dumps(value, sort_keys=True, separators=(",", ":"), ensure_ascii=True,
                       allow_nan=False) + "\n").encode("ascii")


def regular_bytes(directory_fd: int, name: str, *, private: bool = False,
                  limit: int = MAX_RECORD) -> bytes:
    """Bounded no-follow read relative to an already admitted directory FD."""
    require(name not in ("", ".", "..") and "/" not in name and "\x00" not in name,
            "unsafe leaf name")
    fd = os.open(name, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK, dir_fd=directory_fd)
    try:
        before = os.fstat(fd)
        require(stat.S_ISREG(before.st_mode) and before.st_nlink == 1 and before.st_size <= limit,
                "unsafe or oversized regular file")
        if private:
            require(before.st_uid == os.getuid() and stat.S_IMODE(before.st_mode) == 0o600,
                    "unsafe private file")
        chunks = []
        size = 0
        while True:
            chunk = os.read(fd, min(65536, limit + 1 - size))
            if not chunk:
                break
            chunks.append(chunk)
            size += len(chunk)
            require(size <= limit, "file exceeds bound")
        after = os.fstat(fd)
        require((before.st_dev, before.st_ino, before.st_size, before.st_mtime_ns, before.st_ctime_ns)
                == (after.st_dev, after.st_ino, after.st_size, after.st_mtime_ns, after.st_ctime_ns),
                "file changed during read")
        return b"".join(chunks)
    finally:
        os.close(fd)


@contextlib.contextmanager
def directory_at(path: Path, *, private: bool = False):
    """Traverse from / with O_NOFOLLOW; never mkdir/chmod an existing authority root.

    All ancestors must be root/current-user owned and not group/world writable.
    Only the final ledger directory must be owner-only. No caller pathname is an
    authorization receipt. Tests use a private directory inside system temp;
    that entry is admitted with an already-open FD instead of this traversal.
    """
    require(path.is_absolute() and ".." not in path.parts, "absolute normalized root required")
    fd = os.open("/", os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW)
    try:
        for part in path.parts[1:]:
            child = os.open(part, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW, dir_fd=fd)
            os.close(fd)
            fd = child
            info = os.fstat(fd)
            require(info.st_uid in (0, os.getuid()) and not info.st_mode & 0o022,
                    "unsafe directory ancestor")
        info = os.fstat(fd)
        if private:
            require(info.st_uid == os.getuid() and stat.S_IMODE(info.st_mode) == 0o700,
                    "unsafe private directory")
        yield fd
    finally:
        os.close(fd)


class EvidenceStore:
    """Restricted absent-only records with a shared lock and crash-consumed slots.

    Implements storage, not permission, process settlement, or launch authority.
    Callers must hold locked() over admission/claim/launch and independently prove
    process settlement before publishing a result. A result is never inferred
    merely from a PID disappearing. A malformed intent still consumes its slot.
    Production CLI does not open this store until a trusted gate is supported.
    """

    def __init__(self, fd: int):
        info = os.fstat(fd)
        require(stat.S_ISDIR(info.st_mode) and info.st_uid == os.getuid()
                and stat.S_IMODE(info.st_mode) == 0o700, "unsafe evidence directory")
        self.fd = os.dup(fd)
        self.lock_fd = None

    def close(self) -> None:
        require(self.lock_fd is None, "cannot close locked store")
        os.close(self.fd)

    @contextlib.contextmanager
    def locked(self):
        require(self.lock_fd is None, "nested evidence lock")
        # flock on the admitted directory inode avoids a removable lock-file alias.
        # Open a new description so separate store instances actually contend.
        fd = os.open(".", os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW, dir_fd=self.fd)
        try:
            fcntl.flock(fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
            self.lock_fd = fd
            yield self
        finally:
            self.lock_fd = None
            os.close(fd)

    def _locked(self) -> None:
        require(self.lock_fd is not None, "shared evidence lock required")

    def read(self, name: str) -> bytes:
        self._locked()
        return regular_bytes(self.fd, name, private=True)

    def publish(self, name: str, raw: bytes) -> None:
        """Atomic absent-only publication; leave failed writes as restricted debris."""
        self._locked()
        require(re.fullmatch(r"[a-z0-9][a-z0-9.-]{0,159}", name) is not None
                and 0 < len(raw) <= MAX_RECORD, "invalid evidence record")
        temporary = ".pending-" + uuid.uuid4().hex
        fd = os.open(temporary, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW,
                     0o600, dir_fd=self.fd)
        with os.fdopen(fd, "wb") as stream:
            stream.write(raw)
            stream.flush()
            os.fsync(stream.fileno())
        # A crash before link leaves no published record, so this primitive is NOT
        # used for budget claims. A crash after link retains an immutable record;
        # a surviving temporary hardlink fails strict reads until reconciliation.
        os.link(temporary, name, src_dir_fd=self.fd, dst_dir_fd=self.fd, follow_symlinks=False)
        os.unlink(temporary, dir_fd=self.fd)
        os.fsync(self.fd)

    def claim_device(self, stage: str, binding: dict) -> str:
        """Claim immediately before invocation; NEVER use atomic temp publication.

        Even a zero-byte intent means unknown/consumed across all candidates.
        The store cannot prove authorization or whether a device was accessed.
        """
        self._locked()
        require(stage in ("focused", "final"), "invalid budget stage")
        validate_binding(binding)
        # An orphan result is contradictory evidence, not a free slot. Preserve
        # FileExistsError for ordinary occupied intents and never overwrite them.
        require(self.budget_state(stage) == "available", "device slot already consumed")
        name = stage + ".device.intent"
        raw = canonical_json({"schema": "ttl010-device-intent/v1", "binding": binding})
        fd = os.open(name, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW,
                     0o600, dir_fd=self.fd)
        try:
            with os.fdopen(fd, "wb") as stream:
                stream.write(raw)
                stream.flush()
                os.fsync(stream.fileno())
        finally:
            os.fsync(self.fd)
        return digest(raw)

    def budget_state(self, stage: str) -> str:
        self._locked()
        require(stage in ("focused", "final"), "invalid budget stage")
        # lstat first: a dangling link, directory or unreadable/partial file is
        # occupied, never FileNotFoundError interpreted as fresh budget.
        try:
            os.stat(stage + ".device.intent", dir_fd=self.fd, follow_symlinks=False)
        except FileNotFoundError:
            try:
                os.stat(stage + ".device.result", dir_fd=self.fd, follow_symlinks=False)
            except FileNotFoundError:
                return "available"
            raise Invalid("orphan result; budget reconciliation required") from None
        try:
            self.read(stage + ".device.result")
        except FileNotFoundError:
            return "unknown-consumed"
        return "recorded-consumed"  # content still requires independent verification

    def settle_device(self, stage: str, intent_sha256: str, receipt: dict) -> None:
        """Persist original receipt only; does NOT authenticate supplied receipt."""
        self._locked()
        require(stage in ("focused", "final"), "invalid budget stage")
        raw = self.read(stage + ".device.intent")
        require(digest(raw) == intent_sha256, "intent binding drift")
        intent = strict_json(raw)
        require(isinstance(intent, dict) and set(intent) == {"schema", "binding"}
                and intent["schema"] == "ttl010-device-intent/v1"
                and canonical_json(intent) == raw, "malformed device intent")
        validate_binding(intent["binding"])
        require(set(receipt) == {"exit", "stdout_sha256", "stderr_sha256", "settled"}
                and type(receipt["exit"]) is int and receipt["settled"] is True
                and all(re.fullmatch(r"[0-9a-f]{64}", receipt[k])
                        for k in ("stdout_sha256", "stderr_sha256")), "incomplete process receipt")
        self.publish(stage + ".device.result", canonical_json({
            "schema": "ttl010-device-result/v1", "intent_sha256": intent_sha256, "receipt": receipt}))


def validate_binding(binding: dict) -> None:
    require(set(binding) == {"candidate", "source_sha256", "pins_sha256", "methods_sha256", "run"},
            "incomplete candidate binding")
    require(re.fullmatch(r"[0-9a-f]{40}", binding["candidate"]) is not None
            and all(re.fullmatch(r"[0-9a-f]{64}", binding[k]) for k in
                    ("source_sha256", "pins_sha256", "methods_sha256")), "invalid candidate binding")
    try:
        run = uuid.UUID(binding["run"])
        require(str(run) == binding["run"] and run.version == 4, "invalid run identity")
    except (ValueError, AttributeError):
        raise Invalid("invalid run identity") from None


def kotlin_tokens(raw: bytes) -> list[tuple[str, str]]:
    """Small fail-closed Kotlin lexer, not a general compiler/parser.

    Strings/comments cannot manufacture annotations or declarations. Backtick
    names are preserved (host JUnit emits the spaces literally). Unknown test
    declaration/annotation shapes are rejected by discover_methods, never skipped.
    """
    require(len(raw) <= MAX_RECORD, "source too large")
    try:
        text = raw.decode("utf-8")
    except UnicodeError:
        raise Invalid("source is not UTF-8") from None
    tokens = []
    i = 0
    while i < len(text):
        if text[i].isspace():
            i += 1
        elif text.startswith("//", i):
            newline = text.find("\n", i)
            i = len(text) if newline < 0 else newline + 1
        elif text.startswith("/*", i):
            depth = 1
            i += 2
            while i < len(text) and depth:
                if text.startswith("/*", i):
                    depth += 1
                    i += 2
                elif text.startswith("*/", i):
                    depth -= 1
                    i += 2
                else:
                    i += 1
            require(depth == 0, "unterminated source comment")
        elif text.startswith('"""', i):
            end = text.find('"""', i + 3)
            require(end >= 0, "unterminated raw source string")
            tokens.append(("literal", ""))
            i = end + 3
        elif text[i] in "\"'":
            quote = text[i]
            i += 1
            while i < len(text) and text[i] != quote:
                require(text[i] not in "\r\n", "unterminated source literal")
                i += 2 if text[i] == "\\" else 1
            require(i < len(text), "unterminated source literal")
            tokens.append(("literal", ""))
            i += 1
        elif text[i] == "`":
            end = text.find("`", i + 1)
            require(end > i + 1 and not any(c in text[i + 1:end] for c in "\n\r"),
                    "invalid backtick name")
            tokens.append(("name", text[i + 1:end]))
            i = end + 1
        else:
            match = re.match(r"[A-Za-z_$][A-Za-z0-9_$]*", text[i:])
            if match:
                tokens.append(("name", match.group()))
                i += len(match.group())
            else:
                tokens.append(("symbol", text[i]))
                i += 1
    return tokens


def discover_methods(raw: bytes) -> set[tuple[str, str]]:
    tokens = kotlin_tokens(raw)
    values = [value for _, value in tokens]
    package = re.match(r"package\s+([A-Za-z_][\w.]*)", " ".join(values).replace(" . ", "."))
    require(package is not None, "missing test package")
    # Exactly one top-level test class is supported. Local helper classes are
    # legal, but @Test below class depth one is rejected later.
    classes = []
    brace_depth = 0
    for index, value in enumerate(values):
        if value == "class" and brace_depth == 0 and (index == 0 or values[index - 1] != ":"):
            classes.append(index)
        brace_depth += (value == "{") - (value == "}")
    require(len(classes) == 1 and classes[0] + 1 < len(tokens), "unsupported test class shape")
    klass = values[classes[0] + 1]
    require(tokens[classes[0] + 1][0] == "name", "missing test class name")
    blocked = {"Ignore", "Disabled", "Parameterized", "TestFactory", "RepeatedTest", "assumeTrue",
               "assumeFalse", "Assume", "Assumptions", "FixMethodOrder"}
    require(not blocked.intersection(values), "disabled/conditional/unsupported tests")
    annotations = []
    i = 0
    depth = 0
    while i < len(tokens):
        value = values[i]
        if value == "{":
            depth += 1
        elif value == "}":
            depth -= 1
            require(depth >= 0, "unbalanced test source")
        elif value == "@":
            start = i
            i += 1
            require(i < len(tokens) and tokens[i][0] == "name", "invalid annotation")
            name = values[i]
            while i + 2 < len(tokens) and values[i + 1] == ".":
                i += 2
                name = values[i]
            require(name in ("Test", "RunWith", "Suppress"), "unsupported or aliased test annotation")
            if name == "Test":
                require(depth == 1, "nested or top-level test unsupported")
                i += 1
                if i < len(tokens) and values[i] == "(":
                    parens = 1
                    i += 1
                    while i < len(tokens) and parens:
                        parens += (values[i] == "(") - (values[i] == ")")
                        i += 1
                    require(parens == 0, "unterminated test annotation")
                if i < len(tokens) and values[i] == "public":
                    i += 1
                require(i + 4 < len(tokens) and values[i] == "fun"
                        and tokens[i + 1][0] == "name" and values[i + 2:i + 4] == ["(", ")"]
                        and values[i + 4] in ("{", ":", "="), "unsupported test declaration")
                annotations.append((package.group(1) + "." + klass, values[i + 1]))
                i -= 1
            else:
                i = start  # scan argument braces normally
        i += 1
    require(depth == 0 and annotations and len(set(annotations)) == len(annotations),
            "empty/duplicate/unbalanced test source")
    return set(annotations)


def git_output(root: Path, *args: str) -> bytes:
    """Read-only Git, with hooks/fsmonitor/external diff disabled and bounded capture.

    Only repository metadata/source commands below are allowed; never invoke a
    shell, build, network or device command. Output never goes to shared stdout.
    """
    require(args and args[0] in ("rev-parse", "symbolic-ref", "status", "ls-files", "ls-tree", "show"),
            "non-readonly git command")
    env = {k: v for k, v in os.environ.items()
           if not k.startswith("GIT_") and k not in ("LD_PRELOAD", "LD_LIBRARY_PATH")}
    env.update({"GIT_OPTIONAL_LOCKS": "0", "GIT_CONFIG_NOSYSTEM": "1", "GIT_CONFIG_GLOBAL": "/dev/null"})
    try:
        result = subprocess.run(["git", "--no-pager", "-c", "core.fsmonitor=false",
                                 "-c", "core.hooksPath=/dev/null", "-c", "diff.external=", *args],
                                cwd=root, env=env, capture_output=True, timeout=30, check=False)
    except (OSError, subprocess.TimeoutExpired):
        raise Invalid("read-only Git unavailable") from None
    require(result.returncode == 0 and len(result.stdout) <= MAX_RECORD, "Git observation failed")
    return result.stdout


def nul_paths(raw: bytes) -> list[str]:
    require(raw.endswith(b"\0") or not raw, "incomplete NUL-safe inventory")
    try:
        paths = [os.fsdecode(item) for item in raw.split(b"\0")[:-1]]
    except UnicodeError:
        raise Invalid("invalid source paths") from None
    require(len(paths) == len(set(paths)) and all(p and not p.startswith("/")
            and all(part not in ("", ".", "..") for part in p.split("/")) for p in paths),
            "unsafe or duplicate source paths")
    return paths


def source_tree_paths(root_fd: int, relative: str) -> set[str]:
    """Enumerate source inputs including ignored files; no ignored input can hide.

    Generated build outputs are outside app/src. Unknown source files, links and
    non-regular inputs fail closed instead of trusting Git's ignore rules.
    """
    require(relative and all(p not in ("", ".", "..") for p in relative.split("/")),
            "unsafe source root")
    fd = os.dup(root_fd)
    try:
        for part in relative.split("/"):
            child = os.open(part, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW, dir_fd=fd)
            os.close(fd)
            fd = child
        found = set()
        def visit(parent_fd, prefix):
            require(len(found) < 10000, "source tree too large")
            require(prefix.count("/") < 64, "source tree too deep")
            names = os.listdir(parent_fd)
            require(len(names) < 10000, "source tree too large")
            for name in names:
                info = os.stat(name, dir_fd=parent_fd, follow_symlinks=False)
                path = prefix + "/" + name
                if stat.S_ISDIR(info.st_mode):
                    child = os.open(name, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW, dir_fd=parent_fd)
                    try:
                        visit(child, path)
                    finally:
                        os.close(child)
                else:
                    require(stat.S_ISREG(info.st_mode) and info.st_nlink == 1, "unsafe source tree entry")
                    found.add(path)
        visit(fd, relative)
        return found
    finally:
        os.close(fd)


def source_bytes(root_fd: int, relative: str) -> bytes:
    parts = relative.split("/")
    require(all(p not in ("", ".", "..") for p in parts), "unsafe source path")
    fd = os.dup(root_fd)
    try:
        for part in parts[:-1]:
            child = os.open(part, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW, dir_fd=fd)
            os.close(fd)
            fd = child
        return regular_bytes(fd, parts[-1])
    finally:
        os.close(fd)


def retained_artifact(root_fd: int, relative: str, marker_ns: int) -> tuple[bytes, dict]:
    """Read an already archived owner-only artifact under an admitted private FD.

    No archival, marker creation, traversal of authority roots or process launch.
    Supplied marker/FD ownership is not run provenance. Require raw mtime AFTER
    the marker; never refresh an old artifact's timestamp or normalize its bytes.
    The manifest deliberately omits the potentially serial-bearing path.
    """
    require(type(marker_ns) is int and marker_ns > 0, "invalid artifact marker")
    parts = relative.split("/")
    require(all(part not in ("", ".", "..") and "\x00" not in part for part in parts),
            "unsafe artifact path")
    fd = os.dup(root_fd)
    try:
        for part in parts[:-1]:
            child = os.open(part, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW, dir_fd=fd)
            os.close(fd)
            fd = child
            info = os.fstat(fd)
            require(info.st_uid == os.getuid() and stat.S_IMODE(info.st_mode) == 0o700,
                    "unsafe artifact directory")
        info = os.fstat(fd)
        require(stat.S_ISDIR(info.st_mode) and info.st_uid == os.getuid()
                and stat.S_IMODE(info.st_mode) == 0o700, "unsafe artifact root")
        before = os.stat(parts[-1], dir_fd=fd, follow_symlinks=False)
        require(before.st_mtime_ns > marker_ns, "stale artifact")
        raw = regular_bytes(fd, parts[-1], private=True)
        after = os.stat(parts[-1], dir_fd=fd, follow_symlinks=False)
        identity = lambda s: (s.st_dev, s.st_ino, s.st_size, s.st_mtime_ns, s.st_ctime_ns)
        require(identity(before) == identity(after), "artifact changed during collection")
        return raw, {"sha256": digest(raw), "size": len(raw), "mtime_ns": before.st_mtime_ns}
    finally:
        os.close(fd)


def checkout_bytes(relative: str, committed: bytes) -> bytes:
    """Expected raw checkout bytes under the exact approved .gitattributes.

    Do NOT normalize observed bytes/hashes or invoke configurable Git filters.
    Only the existing Windows wrapper has a known checkout transformation; any
    future transformed path must receive its own reviewed support.
    """
    if relative == "gradlew.bat":
        require(b"\r" not in committed, "unsupported wrapper blob line endings")
        return committed.replace(b"\n", b"\r\n")
    return committed


def candidate_snapshot(root: Path, candidate: str, *, observe=git_output) -> dict:
    """Read-only source binding; clean all-path inventory, not selective exclusions.

    Ignores generated ignored build results, which must be separately archived
    and checked by execution stages. Repeats Git state and source reads to detect
    drift. Exclusion/checkout admission is still a separate trusted launch gate.
    """
    require(re.fullmatch(r"[0-9a-f]{40}", candidate) is not None, "full candidate SHA required")
    head = observe(root, "rev-parse", "HEAD").strip().decode("ascii")
    branch = observe(root, "symbolic-ref", "--short", "HEAD").strip().decode("utf-8")
    require(head == candidate and branch == FEATURE_BRANCH, "candidate/branch mismatch")
    status = observe(root, "status", "--porcelain=v2", "-z", "--untracked-files=all", "--ignore-submodules=none")
    require(status == b"", "candidate has staged/unstaged/untracked changes")
    paths_raw = observe(root, "ls-files", "-z")
    paths = nul_paths(paths_raw)
    require(paths and PIN_PATHS.issubset(paths), "incomplete repository inventory")
    with directory_at(root) as fd:
        expected_inputs = {path for path in paths if path.startswith("app/src/")}
        require(source_tree_paths(fd, "app/src") == expected_inputs, "untracked/ignored source inputs")
        blobs = {path: source_bytes(fd, path) for path in paths}
        sources = {path: digest(raw) for path, raw in blobs.items()}
        require(blobs[".gitattributes"] == CHECKOUT_ATTRIBUTES
                and observe(root, "show", BASE_SHA + ":.gitattributes") == CHECKOUT_ATTRIBUTES,
                "unsupported or changed checkout attributes")
        # Git's clean status can hide assume-unchanged/skip-worktree paths.
        # Bind every working-tree byte to the candidate's expected checkout too.
        require(all(digest(checkout_bytes(path, observe(root, "show", candidate + ":" + path))) == sources[path]
                    for path in paths), "working tree differs from candidate objects")
        pins = {path: sources[path] for path in PIN_PATHS}
        approved = {path: digest(checkout_bytes(path, observe(root, "show", BASE_SHA + ":" + path))) for path in PIN_PATHS}
        hashes_equal(approved, pins, pins=True)
        base_paths = nul_paths(observe(root, "ls-tree", "-r", "-z", "--name-only", BASE_SHA,
                                       "--", *TEST_ROOTS.values()))
        require(base_paths, "missing approved-base test inventory")
        methods = {}
        for kind, prefix in TEST_ROOTS.items():
            files = [p for p in paths if p.startswith(prefix + "/")]
            require(files and all(p.endswith(".kt") for p in files), "unsupported test source file")
            discovered = set()
            for path in files:
                current = discover_methods(blobs[path])
                require(not discovered.intersection(current), "duplicate source methods")
                discovered.update(current)
            baseline = set()
            base_files = [p for p in base_paths if p.startswith(prefix + "/")]
            require(base_files and all(p.endswith(".kt") for p in base_files), "unsupported baseline test source")
            for path in base_files:
                baseline.update(discover_methods(observe(root, "show", BASE_SHA + ":" + path)))
            require(len(baseline) >= TEST_FLOORS[kind] and baseline.issubset(discovered),
                    "approved baseline methods removed or replaced")
            require(len(discovered) >= TEST_FLOORS[kind], "test method floor shrank")
            methods[kind] = sorted(discovered)
        require(all(digest(source_bytes(fd, path)) == sources[path] for path in paths)
                and source_tree_paths(fd, "app/src") == expected_inputs,
                "source changed during snapshot")
    require(observe(root, "rev-parse", "HEAD").strip().decode("ascii") == head
            and observe(root, "symbolic-ref", "--short", "HEAD").strip().decode("utf-8") == branch
            and observe(root, "status", "--porcelain=v2", "-z", "--untracked-files=all", "--ignore-submodules=none") == status
            and observe(root, "ls-files", "-z") == paths_raw, "repository changed during snapshot")
    return {"schema": "ttl010-source-binding/v1", "candidate": head, "branch": branch,
            "source": sources, "pins": pins, "methods": methods,
            "source_sha256": digest(canonical_json(sources)), "pins_sha256": digest(canonical_json(pins)),
            "methods_sha256": digest(canonical_json(methods))}


class PrivateArgumentParser(argparse.ArgumentParser):
    def error(self, message: str) -> None:
        # argparse's default error echoes arbitrary arguments, possibly a serial.
        raise Invalid("invalid CLI arguments; use --help for the interface")


def parser() -> argparse.ArgumentParser:
    result = PrivateArgumentParser(description=__doc__, allow_abbrev=False)
    commands = result.add_subparsers(dest="command", required=True)
    commands.add_parser("selftest", help="isolated offline fixtures; no production budget")
    for name in ("preflight", "host", "verify", "device"):
        command = commands.add_parser(name, help="INCOMPLETE: always fails closed")
        command.add_argument("--stage", choices=("focused", "final") if name == "device" else ("final",), required=True)
        if name == "device":
            for option in ("model", "api", "build", "classes"):
                command.add_argument("--" + option, required=True)
    return result


def blocked(command: str) -> dict:
    return {"schema": "ttl010-partial/v1", "command": command, "status": "blocked",
            "effects_started": False, "production_budget_claimed": False,
            "reason": "Trusted execution/evidence contracts unavailable; strings are not authority.",
            "diagnostic": {
                "condition": "Native validation stages unavailable; source-only partial implementation.",
                "source": "Installed PPA 0.1.141 protected-review-v1 contract and CLI help.",
                "reason": "Static preflight is observational; no documented project-stage authorization/settlement consumer was established.",
                "correction": "Specify and approve a supported authenticated launch-gate handoff; do not fabricate receipts or use self-approved JSON.",
                "resumeAction": "Integrate selected candidate/cache/device permissions and process custody, then authorize remaining native stages separately.",
            },
            "implemented_library_only": ["no-follow restricted evidence storage and shared locking",
                                         "crash-consumed absent-only device slots",
                                         "read-only clean candidate/pin/source/method binding"],
            "unimplemented": list(INCOMPLETE),
            "pending": ["Pixel8Pro/API37/build2026100601", "independent review", "CI",
                        "camera/Gallery human UAT", "release/merge"],
            "encoder_cause": "unknown"}


class OfflineTests(unittest.TestCase):
    def setUp(self) -> None:
        self.expected = {("Example", "one"), ("Example", "two")}
        self.one = '<testcase classname="Example" name="one"/>'
        self.two = '<testcase classname="Example" name="two"/>'

    def xml(self, cases: str, tests: int = 2, **attrs: str) -> bytes:
        fields = {"tests": str(tests), "failures": "0", "errors": "0", "skipped": "0", **attrs}
        return ('<testsuite ' + ' '.join(f'{k}="{v}"' for k, v in fields.items())
                + '>' + cases + '</testsuite>').encode()

    def test_fresh_xml_exact_method_set(self) -> None:
        raw = self.xml(self.one + self.two)
        self.assertEqual(xml_methods([(raw, 20)], 10, self.expected), 2)
        wrapped = b'<testsuites tests="2">' + raw + b'</testsuites>'
        self.assertEqual(xml_methods([(wrapped, 20)], 10, self.expected), 2)
        for reports, marker, expected in (([(raw, 10)], 10, self.expected),
                ([(raw, 20)], 0, self.expected), ([], 10, self.expected),
                ([(raw, 20)], 10, set()), ([(raw, 20)], 10, {("Other", "one")})):
            with self.assertRaises(Invalid):
                xml_methods(reports, marker, expected)

    def test_duplicate_missing_extra_failed_skipped_xml(self) -> None:
        fixtures = [self.xml(self.one + self.one), self.xml(self.one, 1),
                    self.xml(self.one + self.two + self.one, 3),
                    self.xml(self.one + self.two, tests=3),
                    self.xml(self.one + self.two, failures="1"),
                    self.xml(self.one + self.two, errors="1"),
                    self.xml(self.one + self.two, skipped="1"),
                    self.xml(self.one.replace('/>', '><skipped/></testcase>') + self.two),
                    self.xml(self.one.replace('/>', '><failure/></testcase>') + self.two),
                    self.xml(self.one.replace('/>', '><error/></testcase>') + self.two),
                    self.xml(self.one.replace('/>', ' status="notrun"/>') + self.two),
                    self.xml(self.one.replace('/>', ' disabled="true"/>') + self.two),
                    self.xml(self.one + self.two, disabled="1"),
                    b'<testsuites failures="1">' + self.xml(self.one + self.two) + b'</testsuites>',
                    b'<testsuite tests="0" failures="0" errors="0"/>']
        for raw in fixtures:
            with self.assertRaises(Invalid):
                xml_methods([(raw, 20)], 10, self.expected)
        with self.assertRaises(Invalid):
            xml_methods([(self.xml(self.one + self.two), 20)] * 2, 10, self.expected)

    def test_dtd_entities_malformed_and_nested_xml(self) -> None:
        for raw in (b'<!DOCTYPE x [<!ENTITY e SYSTEM "file:///unread">]><x>&e;</x>',
                    b'<!ENTITY e "x"><x/>', '<!DOCTYPE x><x/>'.encode('utf-16'), b'\xff', b'<broken',
                    b'<testsuite>\x00</testsuite>', b'<testsuites><unknown/></testsuites>',
                    self.xml('<testsuite>' + self.one + '</testsuite>' + self.two)):
            with self.assertRaises(Invalid):
                xml_methods([(raw, 20)], 10, self.expected)

    def test_pin_and_source_hash_inventory(self) -> None:
        pins = {p: digest(p.encode()) for p in PIN_PATHS}
        hashes_equal(pins, dict(pins), pins=True)
        photos = {"isolated.jpg": digest(b"original JPEG")}
        hashes_equal(photos, dict(photos))
        for before, after, pin in ((pins, {**pins, "flake.lock": digest(b"drift")}, True),
                ({}, {}, False), (photos, {}, False), (photos, {**photos, "extra": digest(b"x")}, False),
                (photos, {"isolated.jpg": digest(b"changed")}, False), ({"x": "bad"}, {"x": "bad"}, False),
                (photos, photos, True)):
            with self.assertRaises(Invalid):
                hashes_equal(before, after, pins=pin)

    def test_device_rows_and_identity(self) -> None:
        rows = "List of devices attached\nSYNTHETIC device product:fixture\n"
        identity = ("CPH2699", "36", "CPH2699_16.0.5.1200(EX01)")
        device_facts(rows, "SYNTHETIC", "device", identity, identity)
        for listing, serial, state, actual in ((rows + "EXTRA device\n", "SYNTHETIC", "device", identity),
                (rows, "OTHER", "device", identity), (rows, "SYNTHETIC", "offline", identity),
                (rows.replace(" device ", " unauthorized "), "SYNTHETIC", "device", identity),
                ("List of devices attached\n", "SYNTHETIC", "device", identity),
                (rows, "SYNTHETIC", "device", ("wrong", "36", identity[2]))):
            with self.assertRaises(Invalid):
                device_facts(listing, serial, state, actual, identity)

    def test_cleanup_unknown_ownership_and_redaction(self) -> None:
        ident = "12345678-1234-4234-8234-123456789abc"
        allocation = dict(id=ident, uri="content://media/external/video/media/1",
                          name=f"Timelapse-{ident}.mp4", owner="com.sinh.timelapse",
                          path="Movies/TimestampTimelapse/")
        self.assertTrue(fixture_row_matches(allocation, dict(allocation)))
        for key in allocation:
            self.assertFalse(fixture_row_matches(allocation, {**allocation, key: "unknown"}))
        self.assertFalse(fixture_row_matches({}, {}))
        self.assertFalse(fixture_row_matches({**allocation, "id": "*"}, allocation))
        self.assertEqual(redact_known("/SYNTHETIC/log SYNTHETIC", "SYNTHETIC"), "/[device]/log [device]")
        with self.assertRaises(Invalid):
            redact_known("private", "")

    def test_stage_reuse_uncertain_completion_and_atomic_results(self) -> None:
        # Never open LEDGER_ROOT: both directory and all records are temporary.
        with tempfile.TemporaryDirectory(prefix="ttl010-selftest-") as directory:
            fd = os.open(directory, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW)
            try:
                ledger = LedgerFixture(fd)
                binding = b"fixture candidate/source/pins/methods/argv binding"
                ledger.claim("focused", binding)
                with self.assertRaises(FileNotFoundError):
                    LedgerFixture(fd).read("focused.result")  # uncertain, not free
                with self.assertRaises(FileExistsError):
                    LedgerFixture(fd).claim("focused", b"different candidate")
                with self.assertRaises(Invalid):
                    ledger.result("focused", binding, None)
                with self.assertRaises(Invalid):
                    ledger.result("focused", b"drift", 0)
                ledger.result("focused", binding, 1)  # nonzero remains consumed
                original = ledger.read("focused.result")
                with self.assertRaises(FileExistsError):
                    ledger.result("focused", binding, 0)
                self.assertEqual(original, ledger.read("focused.result"))
                ledger.claim("final", binding)
                ledger.result("final", binding, 0)
                self.assertEqual(ledger.read("final.result"), binding + b"\nexit=0")
                with self.assertRaises(Invalid):
                    ledger.claim("../focused", binding)
            finally:
                os.close(fd)

    def test_unsafe_ledger_files(self) -> None:
        with tempfile.TemporaryDirectory(prefix="ttl010-selftest-") as directory:
            fd = os.open(directory, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW)
            try:
                ledger = LedgerFixture(fd)
                os.symlink("missing", "focused.intent", dir_fd=fd)
                with self.assertRaises(OSError):
                    ledger.read("focused.intent")
                with self.assertRaises(FileExistsError):
                    ledger.claim("focused", b"no follow")
                path = Path(directory) / "final.intent"
                path.write_bytes(b"partial")
                path.chmod(0o644)
                with self.assertRaises(Invalid):
                    ledger.read("final.intent")
                path.chmod(0o600)
                os.link(path, Path(directory) / "alias")
                with self.assertRaises(Invalid):
                    ledger.read("final.intent")
            finally:
                os.close(fd)

    def test_kotlin_method_discovery_and_backticks(self) -> None:
        raw = b'''package com.example
import org.junit.Test
// @Test fun fake() {}
/* nested /* @Ignore */ @Test */
class Example {
    val text = "@Test fun notATest() {}"
    val raw = """@Test fun alsoNotATest() {}"""
    @Test fun one() { class LocalHelper {} }
    @org.junit.Test(expected = IllegalArgumentException::class)
    fun `two with spaces`() { throw IllegalArgumentException() }
}
'''
        self.assertEqual(discover_methods(raw), {("com.example.Example", "one"),
                                                 ("com.example.Example", "two with spaces")})
        for mutated in (raw.replace(b"@Test fun one", b"@Ignore @Test fun one"),
                        raw.replace(b"fun one()", b"fun one(x: Int)"),
                        raw.replace(b"fun one", b"suspend fun one"),
                        raw.replace(b"fun one", b"fun `two with spaces`"),
                        raw + b"class Extra {}", raw[:-2],
                        raw.replace(b"@Test fun one", b"@OtherTest fun one"),
                        raw.replace(b"@Test fun one", b"@Test fun one" + b"WithParameter(x: Int)"),
                        raw.replace(b"class LocalHelper {}", b"class Nested { @Test fun hidden() {} }"),
                        raw.replace(b"class LocalHelper {}", b"assumeTrue(false)"),
                        b"/* unterminated", b"class Empty {}", b"\xff"):
            with self.subTest(source=digest(mutated)), self.assertRaises(Invalid):
                discover_methods(mutated)

    def test_directory_admission_is_nofollow_readonly_and_fails_closed(self) -> None:
        from types import SimpleNamespace
        from unittest.mock import patch
        for defect in (None, "public-ancestor", "public-leaf", "foreign-owner", "symlink"):
            opened, closed = [], []
            def open_directory(path, flags, **kwargs):
                self.assertTrue(flags & os.O_DIRECTORY)
                self.assertTrue(flags & os.O_NOFOLLOW)
                self.assertFalse(flags & (os.O_CREAT | os.O_TRUNC))
                if path == "root" and defect == "symlink":
                    raise OSError("synthetic no-follow denial")
                fd = 100 + len(opened)
                opened.append(fd)
                return fd
            def info(fd):
                mode = 0o700 if fd == 102 else 0o755
                if (defect == "public-ancestor" and fd == 101) or (defect == "public-leaf" and fd == 102):
                    mode = 0o777
                uid = os.getuid() + 1 if defect == "foreign-owner" else os.getuid()
                return SimpleNamespace(st_mode=stat.S_IFDIR | mode, st_uid=uid)
            with self.subTest(defect=defect), patch.object(os, "open", side_effect=open_directory), \
                    patch.object(os, "fstat", side_effect=info), patch.object(os, "close", side_effect=closed.append):
                if defect is None:
                    with directory_at(Path("/synthetic/root"), private=True) as fd:
                        self.assertEqual(fd, 102)
                else:
                    with self.assertRaises((Invalid, OSError)), directory_at(Path("/synthetic/root"), private=True):
                        self.fail("unsafe directory admitted")
            self.assertEqual(sorted(opened), sorted(closed))

    def test_source_paths_and_nofollow_reads(self) -> None:
        self.assertEqual(nul_paths(b"a b\0line\nfile\0"), ["a b", "line\nfile"])
        for raw in (b"partial", b"a\0a\0", b"/absolute\0", b"../escape\0", b"a//b\0", b"\0"):
            with self.assertRaises(Invalid):
                nul_paths(raw)
        with tempfile.TemporaryDirectory(prefix="ttl010-selftest-") as directory:
            root = Path(directory)
            (root / "one").write_bytes(b"safe")
            (root / "folder").mkdir()
            (root / "folder" / "nested").write_bytes(b"nested")
            (root / "link").symlink_to(root / "one")
            (root / "dirlink").symlink_to(root / "folder")
            os.mkfifo(root / "fifo")
            fd = os.open(root, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW)
            try:
                self.assertEqual(source_bytes(fd, "folder/nested"), b"nested")
                for path in ("link", "dirlink/nested", "fifo", "../escape", "/absolute"):
                    with self.subTest(path=path), self.assertRaises((Invalid, OSError)):
                        source_bytes(fd, path)
                with self.assertRaises(Invalid):
                    regular_bytes(fd, "one", limit=2)
                os.link(root / "one", root / "alias")
                with self.assertRaises(Invalid):
                    regular_bytes(fd, "one")
            finally:
                os.close(fd)

    def binding(self) -> dict:
        return {"candidate": "a" * 40, "source_sha256": digest(b"source"),
                "pins_sha256": digest(b"pins"), "methods_sha256": digest(b"methods"),
                "run": "12345678-1234-4234-8234-123456789abc"}

    def test_strict_json_and_candidate_bindings(self) -> None:
        self.assertEqual(strict_json(canonical_json(self.binding())), self.binding())
        for raw in (b'{"key":1,"key":2}', b'{"key":1,"\\u006bey":2}', b'{"x":NaN}',
                    b'{"x":Infinity}', b'"\\ud800"', b'\xff', b'{bad',
                    b'[' * 66 + b'0' + b']' * 66):
            with self.assertRaises(Invalid):
                strict_json(raw)
        for changed in ({**self.binding(), "candidate": "A" * 40},
                        {**self.binding(), "source_sha256": "missing"},
                        {**self.binding(), "run": "12345678-1234-1234-8234-123456789abc"},
                        {**self.binding(), "extra": "unsupported"}):
            with self.assertRaises(Invalid):
                validate_binding(changed)

    def test_shared_ledger_lock_and_immutable_budget(self) -> None:
        with tempfile.TemporaryDirectory(prefix="ttl010-selftest-") as directory:
            fd = os.open(directory, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW)
            first, second = EvidenceStore(fd), EvidenceStore(fd)
            os.close(fd)
            try:
                with self.assertRaises(Invalid):
                    first.claim_device("focused", self.binding())
                with first.locked():
                    with self.assertRaises(BlockingIOError), second.locked():
                        self.fail("shared lock did not exclude another holder")
                    self.assertEqual(first.budget_state("focused"), "available")
                    intent = first.claim_device("focused", self.binding())
                    self.assertEqual(first.budget_state("focused"), "unknown-consumed")
                    with self.assertRaises(Invalid):
                        first.claim_device("focused", {**self.binding(), "candidate": "b" * 40})
                    for receipt in ({}, {"exit": None, "stdout_sha256": digest(b""),
                                          "stderr_sha256": digest(b""), "settled": True}):
                        with self.assertRaises(Invalid):
                            first.settle_device("focused", intent, receipt)
                    receipt = {"exit": 1, "stdout_sha256": digest(b"failed output"),
                               "stderr_sha256": digest(b"error"), "settled": True}
                    first.settle_device("focused", intent, receipt)
                    original = first.read("focused.device.result")
                    with self.assertRaises(FileExistsError):
                        first.settle_device("focused", intent, {**receipt, "exit": 0})
                    self.assertEqual(original, first.read("focused.device.result"))
                    self.assertEqual(first.budget_state("focused"), "recorded-consumed")
                with second.locked():
                    self.assertEqual(second.read("focused.device.result"), original)
                    self.assertEqual(second.budget_state("final"), "available")
            finally:
                first.close()
                second.close()

    def test_partial_unsafe_or_orphan_ledger_records_never_free_budget(self) -> None:
        for kind in ("empty", "symlink", "directory", "public", "orphan"):
            with self.subTest(kind=kind), tempfile.TemporaryDirectory(prefix="ttl010-selftest-") as directory:
                root = Path(directory)
                intent = root / "final.device.intent"
                if kind == "symlink":
                    intent.symlink_to(root / "absent")
                elif kind == "directory":
                    intent.mkdir()
                elif kind == "orphan":
                    (root / "final.device.result").write_bytes(b"result")
                else:
                    intent.write_bytes(b"")
                    intent.chmod(0o644 if kind == "public" else 0o600)
                fd = os.open(root, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW)
                store = EvidenceStore(fd)
                os.close(fd)
                try:
                    with store.locked():
                        if kind == "orphan":
                            with self.assertRaises(Invalid):
                                store.budget_state("final")
                            with self.assertRaises(Invalid):
                                store.claim_device("final", self.binding())
                        else:
                            self.assertEqual(store.budget_state("final"), "unknown-consumed")
                            with self.assertRaises(Invalid):
                                store.claim_device("final", self.binding())
                        with self.assertRaises((Invalid, OSError)):
                            store.settle_device("final", "0" * 64, {})
                finally:
                    store.close()

    def test_checkout_policy_preserves_raw_pin_identity(self) -> None:
        committed = b"@echo off\necho pinned wrapper\n"
        checked_out = b"@echo off\r\necho pinned wrapper\r\n"
        self.assertEqual(checkout_bytes("gradlew.bat", committed), checked_out)
        self.assertNotEqual(digest(committed), digest(checked_out))
        # Not a permissive global CRLF normalization or a binary transformation.
        for name in ("flake.lock", "gradle/wrapper/gradle-wrapper.jar", "other.bat"):
            self.assertEqual(checkout_bytes(name, committed), committed)
        with self.assertRaises(Invalid):
            checkout_bytes("gradlew.bat", checked_out)

    def test_candidate_snapshot_uses_all_paths_and_base_pins(self) -> None:
        # Synthetic read-only Git observer, never initialize a repo/build/phone.
        with tempfile.TemporaryDirectory(prefix="ttl010-selftest-") as directory:
            root = Path(directory)
            blobs = {p: b"pin" for p in PIN_PATHS}
            blobs[".gitattributes"] = CHECKOUT_ATTRIBUTES
            blobs["gradlew.bat"] = b"@echo off\necho pinned wrapper\n"
            for kind, prefix in TEST_ROOTS.items():
                methods = "\n".join(f"@Test fun method{i}() {{}}" for i in range(TEST_FLOORS[kind]))
                blobs[prefix + "/Example.kt"] = ("package example\nclass Example {\n" + methods + "\n}").encode()
            blobs["docs/has spaces\nand newline.md"] = b"all paths are bound"
            for path, raw in blobs.items():
                target = root / path
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_bytes(checkout_bytes(path, raw))
            base_blobs = dict(blobs)
            # directory_at rejects /tmp's writable ancestor; test ONLY this
            # storage seam with a pre-admitted fixture FD. No production override.
            @contextlib.contextmanager
            def fixture_directory(path):
                self.assertEqual(path, root)
                fd = os.open(root, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW)
                try:
                    yield fd
                finally:
                    os.close(fd)
            def observe(path, *args):
                self.assertEqual(path, root)
                if args == ("rev-parse", "HEAD"):
                    return ("a" * 40 + "\n").encode()
                if args == ("symbolic-ref", "--short", "HEAD"):
                    return (FEATURE_BRANCH + "\n").encode()
                if args[0] == "status":
                    return b""
                if args == ("ls-files", "-z"):
                    return b"".join(os.fsencode(p) + b"\0" for p in sorted(blobs))
                if args[0] == "ls-tree":
                    return b"".join(os.fsencode(p) + b"\0" for p in sorted(base_blobs)
                                    if p.startswith(("app/src/test/", "app/src/androidTest/")))
                if args[0] == "show":
                    revision, relative = args[1].split(":", 1)
                    return base_blobs[relative] if revision == BASE_SHA else blobs[relative]
                self.fail("unexpected Git observation")
            from unittest.mock import patch
            with patch.dict(candidate_snapshot.__globals__, directory_at=fixture_directory):
                snapshot = candidate_snapshot(root, "a" * 40, observe=observe)
                self.assertEqual(len(snapshot["methods"]["host"]), 42)
                self.assertEqual(len(snapshot["methods"]["device"]), 32)
                self.assertEqual(set(snapshot["source"]), set(blobs))
                host_path = "app/src/test/Example.kt"
                original = blobs[host_path]
                blobs[host_path] = original[:-2] + b"@Test fun newlyAdded() {}\n}\n"
                (root / host_path).write_bytes(blobs[host_path])
                self.assertEqual(len(candidate_snapshot(root, "a" * 40, observe=observe)["methods"]["host"]), 43)
                blobs[host_path] = original
                (root / host_path).write_bytes(original)
                for bad in ("dirty", "head", "pin", "object-drift", "wrapper-checkout", "attributes", "ignored", "replacement", "floor"):
                    def reject(path, *args):
                        if bad == "dirty" and args[0] == "status":
                            return b"? unexpected\0"
                        if bad == "head" and args[0] == "rev-parse":
                            return b"b" * 40 + b"\n"
                        if bad == "pin" and args[0] == "show" and args[1].startswith(BASE_SHA + ":"):
                            return b"different pin"
                        if bad == "object-drift" and args[0] == "show" and not args[1].startswith(BASE_SHA + ":"):
                            return b"committed object differs despite clean status"
                        return observe(path, *args)
                    if bad == "wrapper-checkout":
                        (root / "gradlew.bat").write_bytes(blobs["gradlew.bat"])
                    if bad == "attributes":
                        (root / ".gitattributes").write_bytes(b"* text=auto eol=crlf\n")
                    if bad == "replacement":
                        blobs["app/src/test/Example.kt"] = blobs["app/src/test/Example.kt"].replace(b"method0", b"replacement")
                        (root / "app/src/test/Example.kt").write_bytes(blobs["app/src/test/Example.kt"])
                    if bad == "floor":
                        blobs["app/src/test/Example.kt"] = b"package example\nclass Example { @Test fun only() {} }"
                        (root / "app/src/test/Example.kt").write_bytes(blobs["app/src/test/Example.kt"])
                    extra = root / "app/src/test/Ignored.kt"
                    if bad == "ignored":
                        extra.write_bytes(b"ignored but still compiled by Gradle")
                    with self.subTest(case=bad), self.assertRaises(Invalid):
                        candidate_snapshot(root, "a" * 40, observe=reject)
                    if bad == "ignored":
                        extra.unlink()
                    for policy_path in ("gradlew.bat", ".gitattributes"):
                        (root / policy_path).write_bytes(checkout_bytes(policy_path, blobs[policy_path]))

    def test_lint_occurrences_require_exact_individual_dispositions(self) -> None:
        first = b'<issue id="UseKtx" severity="Warning" message="Style"><location file="/private/one.kt" line="1" column="2"/></issue>'
        second = first.replace(b'one.kt', b'two.kt')
        raw = b'<issues format="6">' + first + second + b'</issues>'
        occurrences = lint_occurrences(raw)
        self.assertEqual(len(occurrences), 2)
        ledger = {"lint_sha256": digest(raw), "rationales": {key: "Retain explicit API; review pending" for key in occurrences}}
        result = lint_dispositions(raw, ledger)
        self.assertEqual(result["warnings"], 2)
        self.assertFalse(result["approval_authenticated"])
        self.assertNotIn("private", json.dumps(result))
        for bad in ({**ledger, "lint_sha256": digest(b"historical")},
                    {**ledger, "rationales": {"UseKtx": "Blanket waiver"}},
                    {**ledger, "rationales": {}},
                    {**ledger, "rationales": {key: " " for key in occurrences}},
                    {**ledger, "rationales": {key: None for key in occurrences}},
                    {**ledger, "extra": 0}):
            with self.subTest(ledger=repr(bad)), self.assertRaises(Invalid):
                lint_dispositions(raw, bad)
        empty = b'<issues format="6"/>'
        self.assertEqual(lint_dispositions(empty, {"lint_sha256": digest(empty), "rationales": {}})["warnings"], 0)
        for bad in (raw.replace(b'Warning', b'Error'), raw.replace(b'Warning', b'Fatal'),
                    raw.replace(b'Warning', b'Ignore'), raw.replace(b'format="6"', b'format="7"'),
                    raw.replace(b'line="1"', b'line="0"'), raw.replace(b'<location ', b'<unknown '),
                    raw.replace(b'<issues format="6">', b'<issues format="6">unexpected'),
                    b'<issues format="6">' + first + first + b'</issues>',
                    b'<issues format="6"><issue id="X" severity="Warning" message="X"/></issues>',
                    b'<!DOCTYPE issues><issues format="6"/>',
                    b'<issues xmlns="private" format="6"/>'):
            with self.subTest(raw=digest(bad)), self.assertRaises(Invalid):
                lint_occurrences(bad)

    def test_encoder_diagnostic_extracts_observations_not_cause(self) -> None:
        raw = (b'java.lang.IllegalStateException: Encoder produced an unexpected frame count: '
               b'codec=c2.fixture.avc, fps=24, expected=4, submitted=4, samples=3, '
               b'configBuffers=1, eosFlags=4, recentPtsUs=[0, 41666, 83333]')
        result = encoder_mismatches(raw)
        self.assertEqual(result["raw_sha256"], digest(raw))
        self.assertEqual(result["cause"], "unknown")
        self.assertEqual(result["observations"][0]["samples"], 3)
        self.assertEqual(result["observations"][0]["recent_pts_us"], [0, 41666, 83333])
        self.assertEqual(len(encoder_mismatches(raw + b'\n' + raw)["observations"]), 2)
        self.assertEqual(encoder_mismatches(b'No structured diagnostic in this log')["observations"], [])
        empty = raw.replace(b'[0, 41666, 83333]', b'[]')
        self.assertEqual(encoder_mismatches(empty)["observations"][0]["recent_pts_us"], [])
        # Negative/non-monotonic PTS or absent EOS can be useful failure evidence;
        # do not reject them or reinterpret an observed defect as a passing vector.
        observed = raw.replace(b'[0, 41666, 83333]', b'[10, -2, 1]').replace(b'eosFlags=4', b'eosFlags=0')
        self.assertEqual(encoder_mismatches(observed)["observations"][0]["recent_pts_us"], [10, -2, 1])
        for bad in (raw[:-1], raw.replace(b'fps=24', b'fps=0'),
                    raw.replace(b'fps=24', b'fps=31'), raw.replace(b'expected=4', b'expected=0'),
                    raw.replace(b'samples=3', b'samples=2147483648'),
                    raw.replace(b'samples=3', b'samples=' + b'9' * 5000),
                    raw.replace(b'[0, 41666, 83333]', b'[0, 1, 2, 3, 4, 5, 6, 7, 8]'),
                    raw.replace(b'[0, 41666, 83333]', b'[9223372036854775808]'),
                    raw.replace(b'codec=c2.fixture.avc', b'codec=unsafe codec'), b'\xff'):
            with self.subTest(raw=digest(bad)), self.assertRaises(Invalid):
                encoder_mismatches(bad)

    def test_retained_artifact_freshness_privacy_and_raw_hashes(self) -> None:
        with tempfile.TemporaryDirectory(prefix="ttl010-selftest-") as directory:
            root = Path(directory)
            nested = root / "restricted"
            nested.mkdir(mode=0o700)
            artifact = nested / "fixture.xml"
            raw = b'raw\r\nbytes\n'
            artifact.write_bytes(raw)
            artifact.chmod(0o600)
            fd = os.open(root, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW)
            try:
                marker = artifact.stat().st_mtime_ns - 1
                collected, manifest = retained_artifact(fd, "restricted/fixture.xml", marker)
                self.assertEqual(collected, raw)
                self.assertEqual(manifest["sha256"], digest(raw))
                self.assertNotIn("fixture", json.dumps(manifest))
                for value in (artifact.stat().st_mtime_ns, artifact.stat().st_mtime_ns + 1, 0, True):
                    with self.assertRaises(Invalid):
                        retained_artifact(fd, "restricted/fixture.xml", value)
                for path in ("../fixture.xml", "/absolute", "restricted//fixture.xml", "restricted/./fixture.xml"):
                    with self.assertRaises(Invalid):
                        retained_artifact(fd, path, marker)
                artifact.chmod(0o644)
                with self.assertRaises(Invalid):
                    retained_artifact(fd, "restricted/fixture.xml", marker)
                artifact.chmod(0o600)
                nested.chmod(0o755)
                with self.assertRaises(Invalid):
                    retained_artifact(fd, "restricted/fixture.xml", marker)
                nested.chmod(0o700)
                (root / "alias").symlink_to(nested, target_is_directory=True)
                with self.assertRaises(OSError):
                    retained_artifact(fd, "alias/fixture.xml", marker)
                (nested / "link.xml").symlink_to(artifact)
                with self.assertRaises(OSError):
                    retained_artifact(fd, "restricted/link.xml", marker)
                os.link(artifact, nested / "hardlink.xml")
                with self.assertRaises(Invalid):
                    retained_artifact(fd, "restricted/fixture.xml", marker)
            finally:
                os.close(fd)

    def test_production_cli_never_probes_or_claims_without_trusted_gate(self) -> None:
        from unittest.mock import patch
        for command in ("preflight", "host", "verify", "device"):
            args = ["ttl010_validation.py", command, "--stage", "final"]
            if command == "device":
                args += ["--model", "CPH2699", "--api", "36", "--build", "fixture", "--classes", FINAL_CLASSES]
            output = io.StringIO()
            with self.subTest(command=command), patch("sys.argv", args), \
                    patch("sys.stdout", output), \
                    patch.dict(main.__globals__, EvidenceStore=unittest.mock.Mock(side_effect=AssertionError("store touched")),
                               git_output=unittest.mock.Mock(side_effect=AssertionError("Git probed")),
                               directory_at=unittest.mock.Mock(side_effect=AssertionError("root opened"))), \
                    patch.object(subprocess, "run", side_effect=AssertionError("process started")):
                self.assertEqual(main(), 2)
            result = strict_json(output.getvalue().encode())
            self.assertEqual(result["status"], "blocked")
            self.assertFalse(result["effects_started"])
            self.assertFalse(result["production_budget_claimed"])
            self.assertLess(len(canonical_json(result["diagnostic"])), 2000)
        output = io.StringIO()
        with patch("sys.argv", ["ttl010_validation.py", "device", "--SYNTHETIC-PRIVATE"]), patch("sys.stdout", output):
            self.assertEqual(main(), 2)
        self.assertNotIn("SYNTHETIC", output.getvalue())

    def test_cli_and_native_argv_are_inert(self) -> None:
        for command in ("preflight", "host", "verify", "device"):
            args = [command, "--stage", "final"]
            if command == "device":
                args += ["--model", "CPH2699", "--api", "36", "--build", "fixture", "--classes", FINAL_CLASSES]
            self.assertEqual(parser().parse_args(args).command, command)
            self.assertEqual(blocked(command)["status"], "blocked")
            self.assertFalse(blocked(command)["effects_started"])
        with self.assertRaises(Invalid) as caught:
            parser().parse_args(["selftest", "--private-SYNTHETIC"])
        self.assertNotIn("SYNTHETIC", str(caught.exception))
        self.assertEqual(HOST_ARGV[:6], NATIVE)
        for stage in ("focused", "final"):
            argv = device_argv(stage)
            self.assertEqual(argv[:6], NATIVE)
            self.assertEqual(argv[6:8], PRESERVATION)
            self.assertEqual(sum(a.startswith("-Pandroid.testInstrumentationRunnerArguments.class=") for a in argv), 1)
        with self.assertRaises(Invalid):
            device_argv("retry")


def main() -> int:
    try:
        args = parser().parse_args()
    except Invalid:
        print(json.dumps({"schema": "ttl010-partial/v1", "status": "blocked",
                          "reason": "invalid CLI arguments; use --help", "effects_started": False}))
        return 2
    if args.command != "selftest":
        print(json.dumps(blocked(args.command), sort_keys=True))
        return 2
    suite = unittest.defaultTestLoader.loadTestsFromTestCase(OfflineTests)
    names = [test.id().rsplit('.', 1)[-1] for test in suite]
    result = unittest.TextTestRunner(stream=io.StringIO()).run(suite)
    passed = result.wasSuccessful() and not result.skipped
    print(json.dumps({"schema": "ttl010-selftest/v1", "status": "passed" if passed else "failed", "cases": names,
                      "tests": result.testsRun, "failures": len(result.failures), "errors": len(result.errors),
                      "skipped": len(result.skipped), "failed_cases": [t.id() for t, _ in result.failures + result.errors],
                      "fixture_only": True, "production_budget_claimed": False, "runner_complete": False}, sort_keys=True))
    return 0 if passed else 1


if __name__ == "__main__":
    raise SystemExit(main())
