#!/usr/bin/env python3
"""TTL-006 begin/final accounting; no builds, phones, downloads or input cleanup.

Bindings are consistency checks, not operator permission or independent review.
Only --self-test removes files, within its own allocated temporary fixtures.
"""
import argparse
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile
import time
import unittest
from unittest.mock import patch
import uuid

# Accounting must not create an untracked __pycache__ in the candidate.
sys.dont_write_bytecode = True

# Reuse accounting primitives, NOT TTL-005 validate(), CLI, or authority constants.
from verify_session_results import (
    Invalid, need, sha, git, discover, source_methods, baseline_preserved,
    fresh, junit, lint, absent_write,
)

BASE = "c6715b580890378775516d3ee6c2021626197ba8"
SOURCE = "requirements:agent-teams/requirements/artifacts/2026-10-22-ttl-006-persistence-validation.md"
AUTHORITY = "77be789f712168fe585aa463ff958f2282febe1504928a2f7f52f3f7c37f2da4"
APPROVAL = "c-20261022145410122"
REVIEW = "app/build/ttl006-independent-review"
SCHEMA = "ttl006-accounting/v1"
STAGES = ("host", "apk", "lint", "device")
JDK = Path("/nix/store/04cqkgis3i4b0fm42h5s77i96dp9gmyg-openjdk-17.0.20+2")
SDK = Path("/home/sinh/android-sdk-writable")


def tool_paths():
    # Raw pinned tool inputs as well as all tracked source/build/wrapper/lock files.
    build_tools = SDK / "build-tools/35.0.0"
    need(build_tools.is_dir(), "Missing pinned build-tools")
    return [Path(sys.executable), JDK / "bin/java", JDK / "bin/javac", JDK / "lib/modules",
            SDK / "platforms/android-36/android.jar", *sorted(p for p in build_tools.rglob("*") if p.is_file())]


def bindings(root):
    feature = os.environ.get("PA_FEATURE_SHA", "")
    need(re.fullmatch(r"[0-9a-f]{40}", feature) is not None, "Missing/malformed Feature SHA")
    expected = {"PA_TICKET_ID": "TTL-006", "PA_MATRIX_SOURCE": SOURCE,
                "PA_MATRIX_AUTHORITY_SHA256": AUTHORITY, "PA_MATRIX_APPROVAL_EVIDENCE": APPROVAL,
                "PA_REPO": str(root)}
    need(all(os.environ.get(key) == value for key, value in expected.items()), "Wrong/missing authority/repository binding")
    return {**expected, "PA_FEATURE_SHA": feature}


def regular(path):
    need(path.is_file() and not path.is_symlink() and path.resolve() == path.absolute(), f"Missing/aliased file: {path}")
    need(path.stat().st_nlink == 1, f"Hardlinked input: {path}")


def snapshot(root, base, tools):
    authority = bindings(root)
    feature = authority["PA_FEATURE_SHA"]
    need(git(root, "rev-parse", "HEAD").decode().strip() == feature, "Candidate changed")
    need(git(root, "branch", "--show-current").decode().strip() == "feature/TTL-006-persistence-validation", "Wrong branch")
    status = git(root, "status", "--porcelain=v1", "-z", "--untracked-files=all")
    need(not status, "Dirty tracked/untracked inventory")
    subprocess.run(["git", "-C", str(root), "merge-base", "--is-ancestor", base, feature], check=True, capture_output=True)
    entries = {}
    for entry in git(root, "ls-tree", "-rz", "--full-tree", feature).split(b"\0"):
        if not entry:
            continue
        header, raw_name = entry.split(b"\t", 1)
        mode, kind, oid = header.decode().split()
        name = raw_name.decode("utf-8")
        need(kind == "blob" and mode in ("100644", "100755"), f"Unsupported tracked object: {name}")
        entries[name] = (mode, oid)
    tracked = set(filter(None, git(root, "ls-files", "-z").decode().split("\0")))
    need(tracked == set(entries), "Index/tree inventory mismatch")
    untracked = git(root, "ls-files", "--others", "--exclude-standard", "-z")
    need(not untracked, "Untracked input")
    inventory = {}
    for name, (mode, oid) in sorted(entries.items()):
        path = root / name
        regular(path)
        if Path(name).name == ".gitattributes":
            # Honor existing text/EOL attributes, never authorize a new executable filter.
            need(git(root, "show", f"{base}:{name}") == path.read_bytes(), "Attributes changed from approved base")
        clean_oid = subprocess.check_output(["git", "-C", str(root), "hash-object", f"--path={name}", "--stdin"], input=path.read_bytes()).decode().strip()
        need(clean_oid == oid, f"Checkout differs from candidate under Git attributes: {name}")
        need(bool(path.stat().st_mode & 0o111) == (mode == "100755"), f"Executable mode differs: {name}")
        inventory[name] = {"sha256": sha(path), "blob": oid, "mode": mode}
    for path in (root / "app/src").rglob("*"):
        need(not path.is_symlink(), f"Aliased source input: {path}")
        if path.is_file():
            need(path.relative_to(root).as_posix() in tracked, f"Ignored/untracked compiled source: {path}")
    local = root / "local.properties"
    local_input = None
    if local.exists() or local.is_symlink():
        regular(local)
        local_input = sha(local)
    tests = {}
    for source_set, floor in (("test", 50), ("androidTest", 53)):
        prefix = f"app/src/{source_set}"
        current = source_methods(root / prefix)
        baseline = set()
        for name in git(root, "ls-tree", "-r", "--name-only", base, prefix).decode().splitlines():
            if name.endswith(".kt"):
                original = git(root, "show", f"{base}:{name}")
                baseline.update(discover(original.decode()))
                # This focused ticket adds tests in new files; original assertions stay byte-exact.
                need(name in entries and git(root, "show", f"{feature}:{name}") == original, f"Baseline fixture/assertions changed: {name}")
        baseline_preserved(current, baseline, floor)
        tests[source_set] = {"methods": [list(method) for method in sorted(current)],
                             "baseline": [list(method) for method in sorted(baseline)]}
    pins = {}
    for path in tools:
        resolved = path.resolve(strict=True)
        need(resolved.is_file(), f"Missing tool input: {path}")
        pins[str(path)] = {"resolved": str(resolved), "sha256": sha(resolved)}
    return {"authority": authority, "baseSha": base, "source": inventory, "tests": tests,
            "tools": pins, "localPropertiesSha256": local_input,
            "trackedStatusHex": status.hex(), "untrackedHex": untracked.hex()}


def locations(root, review_root, output=None):
    need(review_root == root / REVIEW and review_root.resolve() == review_root and review_root.is_dir(), "Unexpected/missing/aliased review root")
    if output is not None:
        need(output == review_root / "results.json" and not output.exists() and not output.is_symlink(), "Output must be absent results.json in review root")


def begin(root, review_root, base, tools):
    locations(root, review_root)
    need(not list(review_root.iterdir()), "Begin requires an empty review root; retain previous evidence externally")
    for name in ("app/build/outputs/androidTest-results/connected/debug", "app/build/reports/androidTests/connected/debug"):
        need(not (root / name).exists() and not (root / name).is_symlink(), "Connected results already exist")
    started = time.time_ns()
    inputs = {"schemaVersion": SCHEMA, "runId": str(uuid.uuid4()), "startedNs": started,
              "snapshot": snapshot(root, base, tools)}
    # Recheck candidate/source after hashing inputs, before exclusive publication.
    need(inputs["snapshot"] == snapshot(root, base, tools), "Inputs changed during begin")
    absent_write(review_root / "inputs.json", inputs)
    return inputs


def strict_json(path):
    regular(path)
    def pairs(items):
        result = {}
        for key, value in items:
            need(key not in result, "Duplicate JSON key")
            result[key] = value
        return result
    try:
        need(path.stat().st_size <= 16 * 1024 * 1024, "Oversized begin input")
        return json.loads(path.read_text(), object_pairs_hook=pairs)
    except (ValueError, UnicodeError) as error:
        raise Invalid(f"Invalid begin JSON: {error}") from error


def validate(root, review_root, output, base, tools):
    locations(root, review_root, output)
    input_path = review_root / "inputs.json"
    inputs = strict_json(input_path)
    need(isinstance(inputs, dict) and set(inputs) == {"schemaVersion", "runId", "startedNs", "snapshot"} and inputs["schemaVersion"] == SCHEMA, "Malformed begin envelope")
    need(isinstance(inputs["runId"], str) and re.fullmatch(r"[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}", inputs["runId"]) is not None, "Malformed run identity")
    started = inputs["startedNs"]
    need(type(started) is int and 0 < started <= input_path.stat().st_mtime_ns <= time.time_ns(), "Stale/future/malformed begin time")
    initial_hash = sha(input_path)
    need(inputs["snapshot"] == snapshot(root, base, tools), "Authority/source/test/tool inputs changed since begin")
    markers = {}
    previous = input_path.stat().st_mtime_ns
    used = {}
    def unique(path):
        regular(path)
        info = path.stat()
        identity = (info.st_dev, info.st_ino)
        need(identity not in used, f"Aliased/reused input: {path}")
        used[identity] = str(path)
    unique(input_path)
    for stage in STAGES:
        marker = review_root / f"{stage}-start"
        unique(marker)
        stamp = marker.stat().st_mtime_ns
        need(previous <= stamp <= time.time_ns(), f"Stale/future/out-of-order {stage} marker")
        previous = stamp
        markers[stage] = marker
    consumed = []
    def artifact(path, marker):
        unique(path)
        result = fresh(path, marker)
        consumed.append((path, result["sha256"], path.stat().st_mtime_ns))
        return result
    results = {}
    for source_set, directory, stage in (
        ("test", "app/build/test-results/testDebugUnitTest", "host"),
        ("androidTest", "app/build/outputs/androidTest-results/connected/debug", "device"),
    ):
        reports = list((root / directory).rglob("*.xml"))
        for path in reports:
            artifact(path, markers[stage])
        expected = {tuple(method) for method in inputs["snapshot"]["tests"][source_set]["methods"]}
        results[source_set] = junit(reports, expected, markers[stage])
    artifacts = [artifact(root / name, markers[stage]) for name, stage in (
        ("app/build/outputs/apk/debug/app-debug.apk", "apk"),
        ("app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk", "apk"),
        ("app/build/reports/tests/testDebugUnitTest/index.html", "host"),
        ("app/build/reports/androidTests/connected/debug/index.html", "device"),
        ("app/build/reports/lint-results-debug.html", "lint"),
    )]
    lint_path = root / "app/build/reports/lint-results-debug.xml"
    artifact(lint_path, markers["lint"])
    lint_result = lint(lint_path, markers["lint"])
    marker_info = {stage: {"path": str(path), "mtimeNs": path.stat().st_mtime_ns} for stage, path in markers.items()}
    self_tests = self_test(quiet=True)
    need(inputs["snapshot"] == snapshot(root, base, tools), "Inputs changed during final accounting")
    need(sha(input_path) == initial_hash, "Begin input mutated during accounting")
    for path, digest, stamp in consumed:
        regular(path)
        need(sha(path) == digest and path.stat().st_mtime_ns == stamp, "Artifact changed during accounting")
    need(marker_info == {stage: {"path": str(path), "mtimeNs": path.stat().st_mtime_ns} for stage, path in markers.items()}, "Marker changed during accounting")
    report = {"schemaVersion": SCHEMA, "scope": "software-report-accounting-only; not permission, independent review or human UAT",
              "featureSha": inputs["snapshot"]["authority"]["PA_FEATURE_SHA"], "inputsSha256": initial_hash,
              "inputs": inputs, "markers": marker_info, "tests": results, "artifacts": artifacts,
              "lint": lint_result, "selfTests": self_tests}
    absent_write(output, report)
    return report


class PersistenceAccountingTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="ttl006-selftest-")
        self.addCleanup(self.temp.cleanup)
        self.git_environment = patch.dict(os.environ)
        self.git_environment.start()
        self.addCleanup(self.git_environment.stop)
        for key in list(os.environ):
            if key.startswith("GIT_"):
                del os.environ[key]
        self.root = Path(self.temp.name).resolve()
        template = self.root / ".empty-template"
        template.mkdir()
        os.environ.update(GIT_CONFIG_GLOBAL="/dev/null", GIT_CONFIG_NOSYSTEM="1", GIT_TEMPLATE_DIR=str(template))
        git(self.root, "init", "-q", "-b", "feature/TTL-006-persistence-validation")
        self.put(".gitignore", "app/build/\n")
        self.put(".gitattributes", "* text=auto eol=lf\n*.bat text eol=crlf\n")
        self.put("gradlew.bat", "echo fixture\r\n")
        for source_set, count in (("test", 50), ("androidTest", 53)):
            self.put(f"app/src/{source_set}/Fixture.kt", "package p\nclass " + source_set + " {\n" +
                     "\n".join(f"@Test fun case{i}() {{ assert(true) }}" for i in range(count)) + "\n}\n")
        self.commit()
        self.base = git(self.root, "rev-parse", "HEAD").decode().strip()
        self.put("app/src/test/Addition.kt", "package p\nclass Addition { @Test fun extra() {} }\n")
        self.commit()
        self.feature = git(self.root, "rev-parse", "HEAD").decode().strip()
        (self.root / "gradlew.bat").write_bytes(b"echo fixture\r\n")
        git(self.root, "add", "gradlew.bat")  # Refresh only this fixture's EOL-converted index stat.
        self.tool = self.root / "tool"  # External-to-source fixture, ignored by the test repo.
        git(self.root, "config", "core.excludesFile", str(self.root / ".git/fixture-excludes"))
        (self.root / ".git/fixture-excludes").write_text("/tool\n")
        self.tool.write_bytes(b"pinned tool")
        self.tools = [self.tool]
        self.review = self.root / REVIEW
        self.review.mkdir(parents=True)
        self.env = {"PA_REPO": str(self.root), "PA_FEATURE_SHA": self.feature, "PA_TICKET_ID": "TTL-006",
                    "PA_MATRIX_SOURCE": SOURCE, "PA_MATRIX_AUTHORITY_SHA256": AUTHORITY, "PA_MATRIX_APPROVAL_EVIDENCE": APPROVAL}
        self.environment = patch.dict(os.environ, self.env)
        self.environment.start()
        self.addCleanup(self.environment.stop)
    def tearDown(self):
        pass  # addCleanup restores environments and removes only this allocated temporary fixture.
    def put(self, name, text):
        path = self.root / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text)
        return path
    def commit(self):
        git(self.root, "add", ".")
        git(self.root, "-c", "user.name=Fixture", "-c", "user.email=fixture@example.invalid", "-c", "commit.gpgsign=false", "commit", "-qm", "fixture")
    def start(self):
        return begin(self.root, self.review, self.base, self.tools)
    def outputs(self):
        inputs = self.start()
        for stage in STAGES:
            (self.review / f"{stage}-start").touch()
        for source_set, directory in (("test", "app/build/test-results/testDebugUnitTest"), ("androidTest", "app/build/outputs/androidTest-results/connected/debug")):
            methods = inputs["snapshot"]["tests"][source_set]["methods"]
            cases = "".join(f'<testcase classname="{cls}" name="{method}"/>' for cls, method in methods)
            self.put(f"{directory}/TEST-fixture.xml", f'<testsuite tests="{len(methods)}" failures="0" errors="0" skipped="0">{cases}</testsuite>')
        for name in ("outputs/apk/debug/app-debug.apk", "outputs/apk/androidTest/debug/app-debug-androidTest.apk",
                     "reports/tests/testDebugUnitTest/index.html", "reports/androidTests/connected/debug/index.html", "reports/lint-results-debug.html"):
            self.put("app/build/" + name, "fixture")
        self.put("app/build/reports/lint-results-debug.xml", '<issues><issue id="style" severity="Warning"/></issues>')
    def finish(self):
        # Avoid recursive self-test invocation; every real final runs the full suite.
        with patch(__name__ + ".self_test", return_value={"fixture": True}):
            return validate(self.root, self.review, self.review / "results.json", self.base, self.tools)
    def rejected(self, operation):
        with self.assertRaises((Invalid, OSError, subprocess.SubprocessError)):
            operation()
        self.assertFalse((self.review / "results.json").exists())
    def test_complete_begin_final_and_crlf(self):
        self.outputs()
        result = self.finish()
        self.assertEqual(51, result["tests"]["test"]["executed"])
        self.assertEqual(53, result["tests"]["androidTest"]["executed"])
        self.assertEqual(sha(self.root / "gradlew.bat"), result["inputs"]["snapshot"]["source"]["gradlew.bat"]["sha256"])
        with self.assertRaises(Invalid): self.finish()
    def test_wrong_missing_malformed_authority(self):
        for key in self.env:
            for value in ("", "wrong", self.env[key] + "x"):
                with self.subTest(key=key, value=value), patch.dict(os.environ, {key: value}):
                    self.rejected(self.start)
        self.assertFalse((self.review / "inputs.json").exists())
    def test_binding_mutation_after_begin(self):
        self.outputs()
        for key in self.env:
            with self.subTest(key=key), patch.dict(os.environ, {key: "mutated"}):
                self.rejected(self.finish)
    def test_absent_only_begin(self):
        self.start()
        before = (self.review / "inputs.json").read_bytes()
        self.rejected(self.start)
        self.assertEqual(before, (self.review / "inputs.json").read_bytes())
    def test_missing_begin(self): self.rejected(self.finish)
    def test_malformed_begin(self):
        for raw in ('{}', '[]', 'null', '{bad', '{"x":1,"x":2}'):
            path = self.put(REVIEW + "/inputs.json", raw)
            self.rejected(self.finish)
            path.unlink()
    def test_missing_mutated_begin_fields(self):
        self.outputs()
        path = self.review / "inputs.json"
        original = json.loads(path.read_text())
        for key in original:
            data = dict(original)
            del data[key]
            path.write_text(json.dumps(data))
            self.rejected(self.finish)
        for key, value in (("runId", "not-uuid"), ("startedNs", -1), ("startedNs", True), ("startedNs", time.time_ns() + 10**12), ("schemaVersion", "wrong"), ("snapshot", {})):
            data = {**original, key: value}
            path.write_text(json.dumps(data))
            self.rejected(self.finish)
    def test_raw_crlf_run_drift(self):
        self.outputs()
        (self.root / "gradlew.bat").write_bytes(b"echo fixture\n")
        self.rejected(self.finish)  # Git-clean equivalence does not excuse raw run drift.
    def test_tool_input_drift(self):
        self.outputs()
        self.tool.write_bytes(b"changed")
        self.rejected(self.finish)
    def test_untracked_and_modified_inputs(self):
        self.outputs()
        path = self.put("unexpected.txt", "untracked")
        self.rejected(self.finish)
        path.unlink()
        self.put("gradlew.bat", "changed\n")
        self.rejected(self.finish)
    def test_hidden_tracked_drift(self):
        self.outputs()
        git(self.root, "update-index", "--assume-unchanged", "gradlew.bat")
        self.put("gradlew.bat", "changed\n")
        self.rejected(self.finish)
    def test_changed_candidate(self):
        self.outputs()
        self.put("new.txt", "candidate change")
        self.commit()
        self.rejected(self.finish)
    def test_baseline_assertion_mutation(self):
        path = self.root / "app/src/test/Fixture.kt"
        path.write_text(path.read_text().replace("assert(true)", "assert(false)", 1))
        self.commit()
        with patch.dict(os.environ, {"PA_FEATURE_SHA": git(self.root, "rev-parse", "HEAD").decode().strip()}):
            self.rejected(self.start)
    def test_baseline_removed_or_disabled(self):
        path = self.root / "app/src/androidTest/Fixture.kt"
        original = path.read_text()
        for change in (original.replace("@Test fun case0()", "fun case0()"), original.replace("@Test fun case0()", "@Ignore @Test fun case0()")):
            path.write_text(change)
            self.commit()
            with patch.dict(os.environ, {"PA_FEATURE_SHA": git(self.root, "rev-parse", "HEAD").decode().strip()}):
                self.rejected(self.start)
    def test_baseline_floors(self):
        with self.assertRaises(Invalid): baseline_preserved({("T", "one")}, set(), 50)
    def test_missing_stale_future_markers(self):
        self.outputs()
        path = self.review / "host-start"
        original = path.stat().st_mtime_ns
        for stamp in (1, time.time_ns() + 10**12):
            os.utime(path, ns=(stamp, stamp))
            self.rejected(self.finish)
        os.utime(path, ns=(original, original))
        path.unlink()
        self.rejected(self.finish)
    def test_stale_future_empty_missing_artifacts(self):
        self.outputs()
        path = self.root / "app/build/outputs/apk/debug/app-debug.apk"
        original = path.stat().st_mtime_ns
        for stamp in (1, time.time_ns() + 10**12):
            os.utime(path, ns=(stamp, stamp))
            self.rejected(self.finish)
        os.utime(path, ns=(original, original))
        path.write_bytes(b"")
        self.rejected(self.finish)
        path.unlink()
        self.rejected(self.finish)
    def test_symlink_and_hardlink_reports(self):
        self.outputs()
        path = self.root / "app/build/reports/lint-results-debug.html"
        target = self.root / "app/build/reports/lint-results-debug.xml"
        path.unlink()
        path.symlink_to(target)
        self.rejected(self.finish)
        path.unlink()
        os.link(target, path)
        self.rejected(self.finish)
    def test_aliased_marker_and_begin(self):
        self.outputs()
        path = self.review / "host-start"
        path.unlink()
        path.symlink_to(self.review / "apk-start")
        self.rejected(self.finish)
        path.unlink()
        path.touch()
        inputs = self.review / "inputs.json"
        inputs.rename(self.review / "original.json")
        inputs.symlink_to(self.review / "original.json")
        self.rejected(self.finish)
    def test_bad_junit(self):
        self.outputs()
        path = self.root / "app/build/test-results/testDebugUnitTest/TEST-fixture.xml"
        original = path.read_text()
        variants = [original.replace('name="extra"', 'name="unknown"'),
                    original.replace('name="extra"/>', 'name="extra"><failure/></testcase>'),
                    original.replace('name="extra"/>', 'name="extra"><skipped/></testcase>'),
                    original.replace('failures="0"', 'failures="1"'),
                    original.replace('errors="0"', 'errors="1"'),
                    original.replace('skipped="0"', 'skipped="1"'),
                    original.replace('skipped="0"', 'ignored="1"'),
                    original.replace('name="extra"/>', 'name="extra" status="notrun"/>'),
                    original.replace('name="extra"/>', 'name="extra" disabled="true"/>'),
                    original.replace('<testcase classname="p.Addition" name="extra"/>', ''),
                    '<!DOCTYPE testsuite [<!ENTITY x "bad">]><testsuite/>', '<broken']
        for raw in variants:
            path.write_text(raw)
            self.rejected(self.finish)
        path.write_text(original)
        duplicate = self.put("app/build/test-results/testDebugUnitTest/copy.xml", original)
        self.rejected(self.finish)
        duplicate.unlink()
        path.unlink()
        self.rejected(self.finish)
    def test_lint_error(self):
        self.outputs()
        self.put("app/build/reports/lint-results-debug.xml", '<issues><issue severity="Error"/></issues>')
        self.rejected(self.finish)
    def test_existing_connected_evidence(self):
        self.put("app/build/outputs/androidTest-results/connected/debug/old.xml", "retained")
        self.rejected(self.start)
    def test_aliased_review_root_and_output(self):
        alias = self.root / "alias"
        alias.symlink_to(self.review, target_is_directory=True)
        self.rejected(lambda: begin(self.root, alias, self.base, self.tools))
        alias.unlink()
        self.outputs()
        (self.review / "results.json").symlink_to(self.root / "absent")
        with self.assertRaises(Invalid): self.finish()
    def test_ignored_compiled_input_and_local_properties_drift(self):
        self.outputs()
        self.put("local.properties", "sdk.dir=changed\n")
        git(self.root, "config", "core.excludesFile", str(self.root / ".git/fixture-excludes"))
        (self.root / ".git/fixture-excludes").write_text("/tool\n/local.properties\napp/src/test/build/\n")
        self.rejected(self.finish)
        (self.root / "local.properties").unlink()
        self.put("app/src/test/build/Injected.kt", "package p\nclass Injected { @Test fun extra() {} }")
        self.rejected(self.finish)
    def test_wrong_branch_and_ancestry(self):
        git(self.root, "branch", "-m", "wrong")
        self.rejected(self.start)
        git(self.root, "branch", "-m", "feature/TTL-006-persistence-validation")
        self.rejected(lambda: begin(self.root, self.review, "0" * 40, self.tools))


def self_test(quiet=False):
    import io
    suite = unittest.defaultTestLoader.loadTestsFromTestCase(PersistenceAccountingTests)
    result = unittest.TextTestRunner(stream=io.StringIO() if quiet else sys.stderr, verbosity=1).run(suite)
    need(result.wasSuccessful() and result.testsRun >= 25 and not result.skipped, "TTL-006 accounting self-tests failed")
    return {"executed": result.testsRun, "failures": 0, "errors": 0, "skipped": 0}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--self-test", action="store_true")
    parser.add_argument("--begin", action="store_true")
    parser.add_argument("--review-root", type=Path)
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    try:
        if args.self_test:
            need(not args.begin and args.review_root is None and args.output is None, "Self-test takes no other options")
            print(json.dumps(self_test(), sort_keys=True))
        else:
            need(args.review_root is not None, "--review-root required")
            root = Path(git(Path.cwd(), "rev-parse", "--show-toplevel").decode().strip()).resolve()
            review = args.review_root.absolute()
            if args.begin:
                need(args.output is None, "Begin takes no output option")
                result = begin(root, review, BASE, tool_paths())
                print(json.dumps({"inputs": str(review / "inputs.json"), "runId": result["runId"]}))
            else:
                need(args.output is not None, "--output required")
                result = validate(root, review, args.output.absolute(), BASE, tool_paths())
                print(json.dumps({"report": str(args.output), "featureSha": result["featureSha"], "scope": result["scope"]}))
    except (Invalid, OSError, subprocess.SubprocessError) as error:
        print(f"NOT VERIFIED: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
