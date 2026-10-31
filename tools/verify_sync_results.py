#!/usr/bin/env python3
"""TTL-014 candidate-bound fixture/accounting helper, not review admission or human UAT.

Only an independently admitted executor may run the approved nine-command matrix.
This helper authenticates source/artifact equality and the durable human device
selection; environment bindings and generated reports do not grant authority.
Self-tests without matrix bindings are isolated implementation checks only.
Never starts/restarts ADB, clears/uninstalls an app, provisions tools, configures
Tailscale/certificates/firewall, activates a service or fills a real disk.
"""
import argparse
from collections import Counter
import hashlib
import io
import json
import os
from pathlib import Path
import re
import selectors
import shutil
import signal
import socket
import stat
import subprocess
import sys
import tempfile
import time
import unittest
from unittest.mock import patch

sys.dont_write_bytecode = True
from verify_session_results import (Invalid, need, sha, git, source_methods, discover, junit, lint)
from verify_timebox_results import safe_file, workflow_contract

BASE = "bbe3be26d2e9985c4ceca2e0caed57411d8a0fa5"
SOURCE = "requirements:agent-teams/requirements/artifacts/2026-10-27-ttl-014-private-sync-server.md"
AUTHORITY = "7fc670fd691576d3bd3ffa0ec5fb5244061575bc8d1171a335caa49edf2ce9c9"
APPROVAL = "c-20261027124822121"
ROOT = Path(__file__).resolve().parents[1]
LIMIT = 16 * 1024 * 1024
PERMISSIONS = {"installUpdate": True, "instrumentation": True, "selfOwnedFixtures": True,
               "keepInstalled": True, "noClearUninstallDowngrade": True}
JAVA = "/nix/store/04cqkgis3i4b0fm42h5s77i96dp9gmyg-openjdk-17.0.20+2"
SDK = "/home/sinh/android-sdk-writable"


def canonical(value):
    return json.dumps(value, sort_keys=True, separators=(",", ":"), ensure_ascii=False)


def no_alias(path):
    path = path.absolute()
    need(path.resolve() == path, "Aliased path rejected")
    for parent in (path, *path.parents):
        need(not parent.is_symlink(), "Symlink rejected")
    if path.exists() and path.is_file():
        need(path.stat().st_nlink == 1, "Hardlink rejected")


def write(path, value):
    no_alias(path)
    path.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
    no_alias(path.parent)
    with path.open("xb") as stream:
        os.chmod(path, 0o600)
        stream.write((canonical(value) + "\n").encode())
        stream.flush(); os.fsync(stream.fileno())


def read(path):
    no_alias(path); safe_file(path)
    need(0 < path.stat().st_size <= LIMIT, "Bounded nonempty JSON required")
    def unique(pairs):
        result = {}
        for k, v in pairs:
            need(k not in result, "Duplicate JSON key")
            result[k] = v
        return result
    return json.loads(path.read_text(), object_pairs_hook=unique)


def bindings(env):
    need(env.get("PA_TICKET_ID") == "TTL-014", "Wrong ticket binding")
    need(re.fullmatch(r"[0-9a-f]{40}", env.get("PA_FEATURE_SHA", "")), "Exact Feature SHA required")
    need(env.get("PA_MATRIX_SOURCE") == SOURCE and env.get("PA_MATRIX_AUTHORITY_SHA256") == AUTHORITY and
         env.get("PA_MATRIX_APPROVAL_EVIDENCE") == APPROVAL, "Approved matrix binding mismatch")
    need(Path(env.get("PA_REPO", "")).absolute() == ROOT, "Wrong physical checkout")
    return {"ticket": "TTL-014", "feature": env["PA_FEATURE_SHA"], "base": BASE,
            "source": SOURCE, "authority": AUTHORITY, "approval": APPROVAL}


def go_methods(root):
    result = set()
    need((root / "server/go.mod").read_text().startswith("module timelapse\n"), "Unknown Go module")
    for p in sorted((root / "server").rglob("*_test.go")):
        # Remove Go strings/comments before recognizing top-level test functions.
        code = re.sub(r'//[^\n]*|/\*.*?\*/|`[^`]*`|"(?:\\.|[^"\\])*"|\'(?:\\.|[^\'\\])*\'',
                      lambda m: "\n" * m[0].count("\n") + " ", p.read_text(), flags=re.S)
        tests = re.findall(r"(?m)^func ((?:Test|Fuzz)\w+)\(\w+ \*testing\.(?:T|F)\)\s*\{", code)
        need(not re.search(r"\b(?:t|f)\.Skip(?:f|Now)?\s*\(", code), "Conditional Go skip rejected")
        package = "timelapse/" + str(p.parent.relative_to(root / "server"))
        for method in tests:
            need((package, method) not in result, "Duplicate Go test")
            result.add((package, method))
    need(len(result) >= 50, "Reduced/missing server test inventory")
    return sorted([list(x) for x in result])


def candidate():
    binding = bindings(os.environ)
    need(sys.version_info[:3] == (3, 14, 7), "Pinned Python 3.14.7 required")
    need(git(ROOT, "rev-parse", "HEAD").decode().strip() == binding["feature"], "Feature SHA changed")
    need(git(ROOT, "branch", "--show-current").decode().strip() == "feature/TTL-014-private-sync-server", "Wrong ticket branch")
    need(not git(ROOT, "status", "--porcelain=v1", "-z", "--untracked-files=all"), "Candidate must be clean")
    subprocess.run(["git", "-C", str(ROOT), "merge-base", "--is-ancestor", BASE, binding["feature"]], check=True)
    need(all(v.startswith(b"H ") for v in git(ROOT, "ls-files", "-v", "-z").split(b"\0") if v), "Hidden index inputs")
    inputs = {}
    for entry in filter(None, git(ROOT, "ls-files", "--stage", "-z").split(b"\0")):
        header, raw = entry.split(b"\t", 1); mode, oid, stage = header.split()
        need(mode in (b"100644", b"100755") and stage == b"0", "Unsupported index input")
        name = os.fsdecode(raw); p = ROOT / name; no_alias(p); safe_file(p)
        need(git(ROOT, "check-attr", "filter", "--", name).strip().endswith((b": unspecified", b": unset")), "External Git filter rejected")
        need(git(ROOT, "hash-object", "--path=" + name, "--", name).strip() == oid, "Source blob drift")
        inputs[name] = sha(p)
    need(not (ROOT / "local.properties").exists(), "Uninventoried local toolchain override")
    for name in ("flake.lock", "gradle/wrapper/gradle-wrapper.properties", "gradle/wrapper/gradle-wrapper.jar",
                 "build.gradle.kts", "app/build.gradle.kts", "settings.gradle.kts", "gradle.properties"):
        need(git(ROOT, "show", f"{BASE}:{name}") == (ROOT / name).read_bytes(), "Pinned toolchain changed")
    methods = {}
    for kind in ("test", "androidTest"):
        current = source_methods(ROOT / f"app/src/{kind}")
        original = set()
        for name in filter(None, git(ROOT, "ls-tree", "-r", "--name-only", "-z", BASE, f"app/src/{kind}").split(b"\0")):
            if name.endswith(b".kt"): original.update(discover(git(ROOT, "show", f"{BASE}:{os.fsdecode(name)}").decode()))
        need(original and original <= current, "Original test method removed")
        for p in (ROOT / f"app/src/{kind}").rglob("*.kt"):
            need(not re.search(r"\b(?:assumeTrue|assumeFalse|assumeNotNull|Assume\.)", p.read_text()), "Conditional Kotlin skip")
        methods[kind] = sorted([list(x) for x in current])
    methods["go"] = go_methods(ROOT)
    workflow_contract((ROOT / ".github/workflows/android.yml").read_text())
    return {"binding": binding, "inputs": inputs, "methods": methods}


def stage_root(output):
    output = output.absolute(); root = ROOT / ".validation/TTL-014"
    need(output.parent == root, "Approved evidence directory required")
    no_alias(root); root.mkdir(parents=True, exist_ok=True, mode=0o700); os.chmod(root, 0o700)
    need(not output.exists(), "Output already exists; preserve earlier evidence")
    return root


def frozen(root):
    begin = read(root / "inventory.json")
    need(begin["schema"] == "ttl014-inventory/v1", "Inventory schema")
    need(begin["candidate"] == candidate(), "Candidate/input/method inventory drift")
    need(0 < begin["beganNs"] <= time.time_ns(), "Begin time invalid")
    return begin


def artifact(path, began):
    no_alias(path); safe_file(path)
    need(path.stat().st_size > 0 and began <= path.stat().st_mtime_ns <= time.time_ns(), "Stale/empty/future output")
    return {"path": str(path.relative_to(ROOT)), "sha256": sha(path), "bytes": path.stat().st_size}


def go_results(path, expected, began):
    need(path.stat().st_mtime_ns >= began, "Stale Go result")
    safe_file(path)
    need(0 < path.stat().st_size <= LIMIT, "Missing/oversized Go evidence")
    starts = Counter(); passes = Counter(); package_passes = Counter(); running = set()
    packages = {p for p, _ in expected}
    for line in path.read_text().splitlines():
        event = json.loads(line)
        action = event.get("Action"); package = event.get("Package"); test = event.get("Test")
        need(package in packages, "Unexpected Go package")
        need(action not in ("fail", "skip") and "WARNING: DATA RACE" not in event.get("Output", ""), "Failed/skipped/racy Go test")
        if test and action == "run":
            key = (package, test); need(key not in running, "Repeated Go method"); running.add(key)
            if "/" not in test: starts[key] += 1
        if test and action == "pass":
            key = (package, test); need(key in running, "Go result without run"); running.remove(key)
            if "/" not in test: passes[key] += 1
        if not test and action == "pass": package_passes[package] += 1
    target = Counter({tuple(x): 1 for x in expected})
    need(not running and starts == passes == target, "Incomplete/duplicate/extra Go methods")
    need(package_passes == Counter({p: 1 for p in packages}), "Incomplete/duplicate package results")
    return {"methods": sorted([list(k) for k in passes]), "passed": len(passes), "skipped": 0, "races": 0}


def run(argv, directory, log, timeout=600, env=None, expected=0):
    no_alias(log); need(not log.exists(), "Retain previous raw output")
    started = time.time_ns()
    with log.open("xb") as out:
        os.chmod(log, 0o600)
        process = subprocess.Popen(argv, cwd=directory, env=env, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, start_new_session=True)
        timed_out = False; exceeded = False; count = 0; deadline = time.monotonic() + timeout
        selector = selectors.DefaultSelector(); selector.register(process.stdout, selectors.EVENT_READ)
        try:
            while True:
                remaining = deadline - time.monotonic()
                if remaining <= 0: timed_out = True; break
                events = selector.select(min(0.2, remaining))
                if not events: continue
                chunk = os.read(process.stdout.fileno(), 65536)
                if not chunk: break
                allowed = min(len(chunk), LIMIT - count)
                out.write(chunk[:allowed]); count += allowed
                if allowed != len(chunk): exceeded = True; break
            if timed_out or exceeded:
                # Only this newly owned process group, never shared ADB or another deployment.
                try: os.killpg(process.pid, signal.SIGTERM)
                except ProcessLookupError: pass
                time.sleep(0.1)
                try: os.killpg(process.pid, signal.SIGKILL)
                except ProcessLookupError: pass
            result = process.wait(timeout=10)
        finally:
            selector.close(); process.stdout.close()
    receipt = {"argv": argv, "startedNs": started, "endedNs": time.time_ns(), "exitCode": result,
               "timedOut": timed_out, "outputExceeded": exceeded, "outputSha256": sha(log)}
    write(log.with_name(log.name + ".receipt.json"), receipt)
    need(log.stat().st_size <= LIMIT, "Process output bound exceeded; retain raw output")
    need(not timed_out and not exceeded and result == expected, "Fixture process failed; retain original raw output and receipt")
    return receipt


def binary_fixtures(binary, work):
    """Native CLI smoke only; TLS/auth/range/fault behavior is executed in Go's owned fixtures."""
    no_alias(binary); safe_file(binary)
    secret = work / "password"; secret.write_text("private synthetic fixture password\n"); secret.chmod(0o600)
    credentials = work / "credentials.json"; token = work / "token"
    device = "11111111-1111-4111-8111-111111111111"
    commands = []
    def call(args, expected=0):
        log = work / f"native-{len(commands)}.log"
        commands.append(run([str(binary), *args], work, log, 120, expected=expected))
        need(b"private synthetic fixture password" not in log.read_bytes(), "Secret in native output")
    call(["init-auth", "-credentials", str(credentials), "-username", "fixture", "-password-file", str(secret)])
    call(["enroll", "-credentials", str(credentials), "-device", device, "-name", "fixture", "-token-file", str(token)])
    value = token.read_text().strip(); need(re.fullmatch(r"[0-9a-f]{64}", value), "Wrong token entropy shape")
    raw = credentials.read_text(); need(value not in raw, "Plaintext token persisted server-side")
    need(read(credentials)["devices"][device]["hash"] == hashlib.sha256(value.encode()).hexdigest(), "Wrong token digest")
    call(["revoke", "-credentials", str(credentials), "-device", device])
    need(read(credentials)["devices"][device]["revoked"] is True, "Revocation not durable")
    call(["enroll", "-credentials", str(credentials), "-device", device, "-name", "fixture", "-token-file", str(work / "second-token")], 1)
    call(["serve", "-config", str(work / "absent-config")], 1) # rejection before any listener
    need(all(value.encode() not in p.read_bytes() for p in work.glob("*.log")), "Token in native output")
    return commands


def server_fixtures(binary, output):
    root = stage_root(output); inventory = frozen(root); began = time.time_ns()
    binary = binary.absolute(); need(binary == root / "timelapse-server", "Exact V3 binary required")
    binary_input = artifact(binary, inventory["beganNs"])
    build_info = subprocess.check_output(["go", "version", "-m", str(binary)], timeout=30).decode()
    need("go1.26.8" in build_info and "path\\ttimelapse/cmd/timelapse-server" in build_info and
         f"vcs.revision={inventory['candidate']['binding']['feature']}" in build_info and "vcs.modified=false" in build_info,
         "V3 binary lacks exact clean candidate/toolchain build identity")
    work = root / "server-fixture-raw"; work.mkdir(mode=0o700)
    commands = binary_fixtures(binary, work)
    env = os.environ | {"GOTOOLCHAIN": "local", "GOPROXY": "off"}
    need(subprocess.check_output(["go", "version"], env=env).decode().startswith("go version go1.26.8 "), "Go toolchain mismatch")
    log = work / "go-fixtures.jsonl"
    commands.append(run(["go", "test", "-race", "-count=1", "-json", "./..."], ROOT / "server", log, 420, env))
    tests = go_results(log, inventory["candidate"]["methods"]["go"], began)
    expression = f'let f = builtins.getFlake "path:{ROOT}"; in import {ROOT}/nix/module-tests.nix {{ nixpkgs = f.inputs.nixpkgs; module = f.nixosModules.timelapse-server; }}'
    module_log = work / "module.json"
    commands.append(run(["nix", "--extra-experimental-features", "nix-command flakes", "eval", "--offline", "--no-update-lock-file", "--no-write-lock-file", "--impure", "--json", "--expr", expression], ROOT, module_log, 120))
    need(read(module_log) == {"passed": True, "fixture": "optional-module-isolation", "liveEffects": False}, "Module fixture failed")
    need(sha(binary) == binary_input["sha256"], "Native binary changed")
    frozen(root)
    write(output, {"schema": "ttl014-server/v1", "candidate": inventory["candidate"], "beganNs": began,
        "binary": binary_input, "tests": tests, "commands": commands,
        "raw": [artifact(p, began) for p in sorted(work.iterdir()) if p.stat().st_size and
                (p.suffix in (".log", ".jsonl") or p.name.endswith(".receipt.json") or p.name == "module.json")],
        "scope": "Native binary admin/rejection; Go-owned TLS/auth/upload/merge/storage/fault/recovery/range tests; pure module evaluation. No live listener/service effects."})


def targets_document(path):
    no_alias(path); safe_file(path)
    need(stat.S_IMODE(path.stat().st_mode) == 0o600 and stat.S_IMODE(path.parent.stat().st_mode) == 0o700, "Private device selector required")
    target = read(path)
    need(set(target) == {"schema", "authorization", "devices"} and target["schema"] == "ttl014-devices/v1", "Target schema mismatch")
    need(re.fullmatch(r"c-[0-9]+", target["authorization"]), "Durable human authorization reference required")
    devices = target["devices"]
    need(set(devices) == {"pixel", "oppo"}, "Exactly two declared phones required")
    for key, d in devices.items():
        need(set(d) == {"serial", "model", "api", "build", "user", "context"}, "Incomplete device tuple/context")
        need(all(isinstance(v, str) and v and len(v) <= 256 and "\n" not in v for v in d.values()), "Invalid private target value")
        need(re.fullmatch(r"[A-Za-z0-9_-]+", d["serial"]) and re.fullmatch(r"[0-9]+", d["api"]) and re.fullmatch(r"[0-9]+", d["user"]), "Unsafe target identifiers")
    need(devices["pixel"]["serial"] != devices["oppo"]["serial"], "Device alias rejected")
    return target


def device_authority(target, ticket):
    payload_hash = hashlib.sha256(canonical({"schema": "ttl014-devices/v1", "devices": target["devices"]}).encode()).hexdigest()
    expected = "TTL-014 authorized-fixture-devices\n" + canonical({"ticket": "TTL-014", "selectorPayloadSha256": payload_hash, "permission": PERMISSIONS})
    comments = [c for c in ticket.get("comments", []) if c.get("id") == target["authorization"]]
    need(ticket.get("id") == "TTL-014" and len(comments) == 1 and comments[0].get("author") == "sinh" and
         comments[0].get("content") == expected, "Exact durable Sinh fixture authorization absent/mismatched")


def adb_existing(adb):
    need(adb.is_file(), "Existing SDK ADB required")
    version = subprocess.check_output([str(adb), "version"], timeout=10).decode()
    need(version.startswith("Android Debug Bridge version 1.0.41\n"), "Unsupported ADB client")
    # Talk only to an ALREADY running local server; never let a client auto-start/restart it.
    with socket.create_connection(("127.0.0.1", 5037), timeout=3) as sock:
        payload = b"host:version"; sock.sendall(f"{len(payload):04x}".encode() + payload)
        def exact(n):
            data = b""
            while len(data) < n:
                chunk = sock.recv(n - len(data)); need(chunk, "ADB protocol incomplete"); data += chunk
            return data
        need(exact(4) == b"OKAY", "ADB server refused version")
        size = int(exact(4), 16); need(size == 4 and exact(size) == b"0029", "ADB server/client mismatch; do not restart shared server")


def android_fixtures(device, targets, output):
    need(device in ("pixel", "oppo"), "Select exactly one declared phone")
    root = stage_root(output); inventory = frozen(root)
    need(targets.absolute() == root / "approved-devices.json", "Exact private selector required")
    target = targets_document(targets); selector_hash = sha(targets)
    # Native read-only ticket API, not a supplied comment blob or a builder-written passed flag.
    ticket = json.loads(subprocess.check_output(["ppa", "ticket", "show", "TTL-014", "--json"], timeout=30))
    device_authority(target, ticket)
    need(os.environ.get("JAVA_HOME") == JAVA and os.environ.get("ANDROID_HOME") == SDK, "Fixed JDK/SDK required")
    adb = Path(SDK) / "platform-tools/adb"; adb_existing(adb)
    d = target["devices"][device]
    need(d["user"] == "0", "This Gradle adapter only supports an explicitly authorized owner-user context; no profile fallback")
    env = os.environ | {"ANDROID_SERIAL": d["serial"], "ADB_SERVER_SOCKET": "tcp:localhost:5037"}
    def shell(*args):
        adb_existing(adb)
        return subprocess.check_output([str(adb), "-s", d["serial"], "shell", *args], env=env, timeout=15).decode().strip()
    actual = {"model": shell("getprop", "ro.product.model"), "api": shell("getprop", "ro.build.version.sdk"),
              "build": shell("getprop", "ro.build.display.id"), "user": shell("am", "get-current-user")}
    need(all(actual[k] == d[k] for k in actual), "Current phone tuple changed; new exact approval required")
    need(d["context"].strip(), "Disposable/self-owned test context not declared")
    installed = shell("pm", "path", "--user", "0", "com.sinh.timelapse")
    if installed:
        # Never run synthetic capture/work fixtures through an already enrolled live sync owner.
        # Read only the one app-owned configuration; never print/copy its credential ciphertext.
        shell("run-as", "com.sinh.timelapse", "id", "-u")
        probe = subprocess.run([str(adb), "-s", d["serial"], "shell", "run-as", "com.sinh.timelapse", "test", "-e", "files/private-sync/state.json"],
                               env=env, capture_output=True, timeout=15)
        need(probe.returncode in (0, 1), "Cannot establish existing app sync isolation")
        if probe.returncode == 0:
            state = json.loads(shell("run-as", "com.sinh.timelapse", "cat", "files/private-sync/state.json"))
            need(state.get("version") == 1 and state.get("enabled") is False and state.get("endpoint") == "" and state.get("credential") == "",
                 "Enrolled/unknown existing sync context retained; select a fresh disposable context, do not reset it")
    # No permission, data reset, downgrade, uninstall, shared-server restart or fixture bypass.
    began = time.time_ns(); work = root / f"{device}-raw"; work.mkdir(mode=0o700)
    original = ROOT / "app/build/outputs/androidTest-results/connected/debug"
    html = ROOT / "app/build/reports/androidTests/connected/debug"
    for i, p in enumerate((original, html)):
        if p.exists():
            no_alias(p); shutil.move(str(p), str(work / f"retained-before-{i}"))
    apks = [ROOT / "app/build/outputs/apk/debug/app-debug.apk", ROOT / "app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"]
    apk_inputs = [artifact(p, inventory["beganNs"]) for p in apks]
    classes = ",".join(sorted({c for c, _ in inventory["candidate"]["methods"]["androidTest"]}))
    argv = ["./gradlew", "--offline", "--no-daemon", "--console=plain", "-Pandroid.builder.sdkDownload=false",
        "-Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true", "-Pandroid.experimental.testOptions.uninstallIncompatibleApks=false",
        "-Pandroid.testInstrumentationRunnerArguments.class=" + classes, ":app:connectedDebugAndroidTest", "--rerun-tasks"]
    command = run(argv, ROOT, work / "instrumentation.log", 1650, env)
    need(original.is_dir() and html.is_dir(), "Missing native instrumentation outputs")
    shutil.copytree(original, work / "xml"); shutil.copytree(html, work / "html")
    result = junit(sorted((work / "xml").rglob("*.xml")), {tuple(x) for x in inventory["candidate"]["methods"]["androidTest"]}, work / "instrumentation.log")
    need([sha(p) for p in apks] == [a["sha256"] for a in apk_inputs], "APK changed during instrumentation")
    need(sha(targets) == selector_hash and targets_document(targets) == target, "Private selector changed")
    frozen(root)
    # Raw tuple/serial logs remain private. Do not copy this report into public summaries.
    write(output, {"schema": "ttl014-android/v1", "candidate": inventory["candidate"], "device": device,
        "selectorSha256": selector_hash, "authorization": target["authorization"], "tuple": d,
        "beganNs": began, "command": command, "apks": apk_inputs, "results": result,
        "raw": [artifact(p, began) for p in sorted(work.rglob("*")) if p.is_file() and "retained-before-" not in str(p)]})


def verify_report(root, name, schema, inventory):
    path = root / name; report = read(path)
    need(report.get("schema") == schema and report.get("candidate") == inventory["candidate"], "Report candidate/schema mismatch")
    for item in report.get("raw", []):
        need(artifact(ROOT / item["path"], report["beganNs"]) == item, "Raw artifact drift")
    return report


def account(root, output):
    need(root.absolute() == ROOT / ".validation/TTL-014", "Exact evidence root required")
    stage_root(output); begin = frozen(root)
    selftest = read(root / "self-tests.json")
    need(selftest.get("candidate") == begin["candidate"] and selftest.get("failures") == selftest.get("errors") == 0 and selftest.get("tests", 0) >= 10, "Self-test evidence incomplete")
    server = verify_report(root, "server-results.json", "ttl014-server/v1", begin)
    need(server["tests"]["methods"] == begin["candidate"]["methods"]["go"], "Server fixture methods incomplete")
    need(go_results(root / "server-fixture-raw/go-fixtures.jsonl", begin["candidate"]["methods"]["go"], server["beganNs"]) == server["tests"], "Server raw fixture result drift")
    go = go_results(root / "go-tests.jsonl", begin["candidate"]["methods"]["go"], begin["beganNs"])
    host_dir = ROOT / "app/build/test-results/testDebugUnitTest"
    host = junit(sorted(host_dir.rglob("*.xml")), {tuple(x) for x in begin["candidate"]["methods"]["test"]}, root / "inventory.json")
    lint_result = lint(ROOT / "app/build/reports/lint-results-debug.xml", root / "inventory.json")
    need(all(w["severity"] != "Ignore" for w in lint_result["warnings"]), "Ignored lint result")
    reports = [verify_report(root, f"{device}-results.json", "ttl014-android/v1", begin) for device in ("pixel", "oppo")]
    expected = begin["candidate"]["methods"]["androidTest"]
    for device, report in zip(("pixel", "oppo"), reports):
        need(report["device"] == device and [list(x) for x in report["results"]["methods"]] == expected and
             report["results"]["executed"] == len(expected), "Missing/wrong device method coverage")
        for item in report["apks"]: need(artifact(ROOT / item["path"], begin["beganNs"]) == item, "APK mismatch across phones")
        # Reparse raw XML, never trust a report's successful counts.
        checked = junit(sorted((root / f"{device}-raw/xml").rglob("*.xml")), {tuple(x) for x in expected}, root / f"{device}-raw/instrumentation.log")
        need(checked == report["results"], "Changed instrumentation XML/results")
    need(reports[0]["selectorSha256"] == reports[1]["selectorSha256"] == sha(root / "approved-devices.json"), "Device selector drift")
    need(reports[0]["tuple"]["serial"] != reports[1]["tuple"]["serial"], "Same phone substituted twice")
    artifacts = [artifact(ROOT / p, begin["beganNs"]) for p in (
        "app/build/outputs/apk/debug/app-debug.apk", "app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk",
        "app/build/reports/lint-results-debug.xml", "app/build/reports/lint-results-debug.html", "app/build/reports/tests/testDebugUnitTest/index.html")]
    need(artifact(root / "timelapse-server", begin["beganNs"]) == server["binary"], "V3 native binary drift")
    # Confirm V6's cached realized package without building/provisioning or changing the lock.
    package = subprocess.check_output(["nix", "--extra-experimental-features", "nix-command flakes", "path-info", "--offline",
        "--no-update-lock-file", "--no-write-lock-file", ".#timelapse-server"], cwd=ROOT, timeout=120).decode().strip()
    need(re.fullmatch(r"/nix/store/[a-z0-9]{32}-[^/]+", package), "Missing/ambiguous native package")
    native = Path(package) / "bin/timelapse-server"; need(native.is_file() and os.access(native, os.X_OK), "Native Nix output missing")
    build_outputs = [artifact(p, begin["beganNs"]) for p in sorted((ROOT / "app/build/outputs").rglob("*")) if p.is_file()]
    frozen(root)
    write(output, {"schema": "ttl014-summary/v1", "candidate": begin["candidate"], "beganNs": begin["beganNs"],
        "go": go, "host": host, "android": {d: len(expected) for d in ("pixel", "oppo")}, "lint": lint_result,
        "artifacts": artifacts, "buildOutputs": build_outputs, "nix": {"path": package, "binarySha256": sha(native)},
        "projectArtifactAccountingPassed": True, "reviewAccepted": False, "executorLedgerRequired": True,
        "limits": "Artifact reconciliation only. Independent executor must separately reconcile all NINE ordered command exits/timeouts/outputs including V9, original failures, Critical/Major findings, every Minor rationale, CI and human UAT. No live deployment authorized."})


class SelfTest(unittest.TestCase):
    def test_bindings_reject_missing_changed_authority(self):
        env = {"PA_TICKET_ID": "TTL-014", "PA_FEATURE_SHA": "a" * 40, "PA_MATRIX_SOURCE": SOURCE,
               "PA_MATRIX_AUTHORITY_SHA256": AUTHORITY, "PA_MATRIX_APPROVAL_EVIDENCE": APPROVAL, "PA_REPO": str(ROOT)}
        bindings(env)
        for key in env:
            with self.assertRaises(Invalid): bindings(env | {key: env[key] + "wrong"})
    def test_absent_only_private_output(self):
        with tempfile.TemporaryDirectory() as temp:
            path = Path(temp) / "result"; write(path, {"a": 1})
            self.assertEqual(stat.S_IMODE(path.stat().st_mode), 0o600)
            with self.assertRaises(FileExistsError): write(path, {})
    def test_duplicate_json_rejected(self):
        with tempfile.TemporaryDirectory() as temp:
            path = Path(temp) / "bad"; path.write_text('{"a":1,"a":2}')
            with self.assertRaises(Invalid): read(path)
    def test_symlink_and_hardlink_rejected(self):
        with tempfile.TemporaryDirectory() as temp:
            path = Path(temp) / "a"; write(path, {})
            alias = Path(temp) / "alias"; alias.symlink_to(path)
            with self.assertRaises(Invalid): read(alias)
            alias.unlink(); os.link(path, alias)
            with self.assertRaises(Invalid): read(path)
    def test_go_all_methods_once_and_negative_events(self):
        expected = [["p", "TestOne"]]
        events = [{"Action": "run", "Package": "p", "Test": "TestOne"},
                  {"Action": "pass", "Package": "p", "Test": "TestOne"}, {"Action": "pass", "Package": "p"}]
        with tempfile.TemporaryDirectory() as temp:
            path = Path(temp) / "go"
            def put(values): path.write_text("\n".join(map(json.dumps, values)))
            put(events); self.assertEqual(go_results(path, expected, 1)["passed"], 1)
            for bad in (events[:-1], events[1:], events + events, events[:1] + [{"Action": "skip", "Package": "p", "Test": "TestOne"}],
                        events + [{"Action": "output", "Package": "p", "Output": "WARNING: DATA RACE"}],
                        events + [{"Action": "run", "Package": "wrong", "Test": "TestOne"}]):
                put(bad)
                with self.assertRaises(Invalid): go_results(path, expected, 1)
    def test_stale_go_rejected(self):
        with tempfile.TemporaryDirectory() as temp:
            path = Path(temp) / "go"; path.write_text("{}")
            with self.assertRaises(Invalid): go_results(path, [["p", "TestOne"]], time.time_ns() + 100000)
    def test_junit_complete_missing_duplicate_skip_fail(self):
        with tempfile.TemporaryDirectory() as temp:
            folder = Path(temp); marker = folder / "marker"; marker.touch(); path = folder / "test.xml"
            good = '<testsuite tests="1" failures="0" errors="0" skipped="0"><testcase classname="C" name="m"/></testsuite>'
            path.write_text(good); self.assertEqual(junit([path], {("C", "m")}, marker)["executed"], 1)
            for bad in (good.replace('failures="0"', 'failures="1"'), good.replace('skipped="0"', 'skipped="1"'),
                        good.replace('name="m"', 'name="other"'), good.replace('/>', '><skipped/></testcase>'),
                        good.replace('tests="1"', 'tests="2"')):
                path.write_text(bad)
                with self.assertRaises(Invalid): junit([path], {("C", "m")}, marker)
            path.write_text(good)
            with self.assertRaises(Invalid): junit([path, path], {("C", "m")}, marker)
            with self.assertRaises(Invalid): junit([], {("C", "m")}, marker)
    def test_xml_entities_rejected(self):
        with tempfile.TemporaryDirectory() as temp:
            path = Path(temp) / "xml"; path.write_text('<!DOCTYPE x [<!ENTITY x SYSTEM "file:///etc/passwd">]><testsuite/>')
            with self.assertRaises(Invalid): junit([path], {("C", "m")}, path)
    def test_device_authority_exact_human_and_tuple(self):
        target = {"authorization": "c-123", "devices": {"pixel": {"serial": "fixture-one"}, "oppo": {"serial": "fixture-two"}}}
        payload_hash = hashlib.sha256(canonical({"schema": "ttl014-devices/v1", "devices": target["devices"]}).encode()).hexdigest()
        content = "TTL-014 authorized-fixture-devices\n" + canonical({"ticket": "TTL-014", "selectorPayloadSha256": payload_hash, "permission": PERMISSIONS})
        comment = {"id": "c-123", "author": "sinh", "content": content}
        ticket = {"id": "TTL-014", "comments": [comment]}; device_authority(target, ticket)
        for change in ({"author": "builder"}, {"content": content + "changed"}, {"id": "c-456"}):
            with self.assertRaises(Invalid): device_authority(target, ticket | {"comments": [comment | change]})
    def test_target_schema_private_two_distinct_phones(self):
        with tempfile.TemporaryDirectory() as temp:
            path = Path(temp) / "targets"
            d = {"serial": "fixture", "model": "synthetic", "api": "36", "build": "fixture", "user": "10", "context": "disposable fixtures only"}
            value = {"schema": "ttl014-devices/v1", "authorization": "c-123", "devices": {"pixel": d, "oppo": d | {"serial": "other"}}}
            write(path, value); targets_document(path)
            path.chmod(0o644)
            with self.assertRaises(Invalid): targets_document(path)
            path.chmod(0o600); path.write_text(canonical(value | {"devices": {"pixel": d, "oppo": d}}))
            with self.assertRaises(Invalid): targets_document(path)
    def test_source_inventory_includes_original_and_sync_methods(self):
        for kind in ("test", "androidTest"):
            methods = source_methods(ROOT / f"app/src/{kind}")
            self.assertTrue(any("Sync" in c for c, _ in methods))
        self.assertGreaterEqual(len(go_methods(ROOT)), 50)
    def test_failed_and_timed_out_processes_retain_receipts(self):
        with tempfile.TemporaryDirectory() as temp:
            folder = Path(temp)
            log = folder / "failed.log"
            with self.assertRaises(Invalid): run([sys.executable, "-c", "raise SystemExit(2)"], folder, log, 10)
            self.assertEqual(read(folder / "failed.log.receipt.json")["exitCode"], 2)
            timeout = folder / "timeout.log"
            with self.assertRaises(Invalid): run([sys.executable, "-c", "import time;time.sleep(10)"], folder, timeout, 0.05)
            self.assertTrue(read(folder / "timeout.log.receipt.json")["timedOut"])
            bounded = folder / "bounded.log"
            with patch(__name__ + ".LIMIT", 128):
                with self.assertRaises(Invalid): run([sys.executable, "-c", "print('x'*1024)"], folder, bounded, 10)
            self.assertEqual(bounded.stat().st_size, 128)
            self.assertTrue(read(folder / "bounded.log.receipt.json")["outputExceeded"])
    def test_missing_stale_and_changed_raw_artifacts_rejected(self):
        with tempfile.TemporaryDirectory() as temp, patch(__name__ + ".ROOT", Path(temp)):
            root = Path(temp); p = root / "raw"; p.write_text("actual output")
            item = artifact(p, 1)
            inv = {"candidate": {"fixture": True}}
            report = root / "report.json"
            write(report, {"schema": "fixture", "candidate": inv["candidate"], "beganNs": 1, "raw": [item]})
            verify_report(root, report.name, "fixture", inv)
            p.write_text("changed output")
            with self.assertRaises(Invalid): verify_report(root, report.name, "fixture", inv)
            with self.assertRaises(Invalid): artifact(p, time.time_ns() + 100000)
            p.unlink()
            with self.assertRaises(Invalid): verify_report(root, report.name, "fixture", inv)
    def test_absent_adb_server_is_never_started(self):
        with tempfile.TemporaryDirectory() as temp:
            adb = Path(temp) / "adb"; adb.touch()
            with patch.object(subprocess, "check_output", return_value=b"Android Debug Bridge version 1.0.41\nfixture" ) as call, \
                 patch.object(socket, "create_connection", side_effect=ConnectionRefusedError):
                with self.assertRaises(ConnectionRefusedError): adb_existing(adb)
                call.assert_called_once_with([str(adb), "version"], timeout=10)
    def test_workflow_still_read_only(self):
        code = (ROOT / ".github/workflows/android.yml").read_text(); workflow_contract(code)
        with self.assertRaises(Invalid): workflow_contract(code.replace("contents: read", "contents: write"))


def self_tests(output):
    stream = io.StringIO(); result = unittest.TextTestRunner(stream=stream, verbosity=2).run(unittest.defaultTestLoader.loadTestsFromTestCase(SelfTest))
    print(stream.getvalue(), end="")
    need(result.wasSuccessful() and not result.skipped, "Accounting self-tests failed")
    current = None
    if os.environ.get("PA_FEATURE_SHA"):
        root = stage_root(output); began = time.time_ns(); current = candidate()
        write(root / "inventory.json", {"schema": "ttl014-inventory/v1", "beganNs": began, "candidate": current})
    write(output, {"schema": "ttl014-self-tests/v1", "tests": result.testsRun, "failures": len(result.failures),
                   "errors": len(result.errors), "candidate": current, "scope": "Pure positive/negative accounting fixtures; not review admission or device execution"})


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    mode = parser.add_mutually_exclusive_group(required=True)
    for name in ("self-test", "server-fixtures", "android-fixtures", "account"): mode.add_argument("--" + name, action="store_true")
    parser.add_argument("--output", required=True, type=Path); parser.add_argument("--binary", type=Path)
    parser.add_argument("--device", choices=("pixel", "oppo")); parser.add_argument("--targets", type=Path); parser.add_argument("--root", type=Path)
    args = parser.parse_args()
    try:
        if args.self_test: self_tests(args.output)
        elif args.server_fixtures:
            need(args.binary is not None, "Binary required"); server_fixtures(args.binary, args.output)
        elif args.android_fixtures:
            need(args.device and args.targets, "Exact device selector required"); android_fixtures(args.device, args.targets, args.output)
        else:
            need(args.root is not None, "Evidence root required"); account(args.root, args.output)
        return 0
    except (Invalid, OSError, ValueError, KeyError, subprocess.SubprocessError):
        print("TTL-014 verification stopped; original evidence retained. No acceptance claimed.", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
