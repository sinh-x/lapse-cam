#!/usr/bin/env python3
"""TTL-011 fail-closed candidate/output accounting; never runs Gradle/ADB/network.

This verifies artifacts, not matrix command receipts, hosted CI, real device UAT,
review admission or human acceptance. All outputs are absent-only; retain failures.
"""
import argparse
import hashlib
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

# Never create __pycache__ in the candidate while doing read-only accounting.
sys.dont_write_bytecode = True

# Reuse only generic safe functions, never TTL-005 validate()/authority.
from verify_session_results import (Invalid, absent_write, baseline_preserved, discover,
    fresh, git, junit, lint, need, sha, source_methods)

BASE = "c6715b580890378775516d3ee6c2021626197ba8"
INTEGRATION_BASE = "62a021c0d72f2029340b040ac125a8b393836974"
SOURCE = "requirements:agent-teams/requirements/artifacts/2026-10-23-ttl-011-camera-ui-timeboxes-r003.md"
AUTHORITY = "faac964ef47ec78584f7e26996d84fa22f6896225a9c18ae1efad971b0a9158d"
APPROVAL = "c-20261023143950024"
PINS = {
    "actions/checkout": "11bd71901bbe5b1630ceea73d27597364c9af683",
    "actions/setup-java": "c5195efecf7bdfc987ee8bae7a71cb8b11521c00",
    "actions/upload-artifact": "ea165f8d65b6e75b540449e92b4886f43607fa02",
}
REQUIRED = {
    "test": {"WorkSessionTest", "TimeboxPolicyTest", "RecordingLinkTest", "CameraUiPolicyTest"},
    "androidTest": {"WorkSessionStoreTest", "TimeboxExecutionTest", "WorkSessionActivityTest",
        "TimeboxRecordingLinkTest", "CameraUiActivityTest", "CameraSettingsSheetTest"},
}


def safe_file(path):
    need(path.is_file() and not path.is_symlink() and path.resolve() == path.absolute(), f"Missing/aliased file: {path}")
    need(path.stat().st_nlink == 1, f"Hard-linked file: {path}")
    need(path.stat().st_mtime_ns <= time.time_ns(), f"Future file: {path}")


def bindings(env):
    feature = env.get("PA_FEATURE_SHA", "")
    need(re.fullmatch(r"[0-9a-f]{40}", feature) is not None, "Missing exact Feature SHA")
    need(env.get("PA_TICKET_ID") == "TTL-011", "Wrong ticket")
    need(env.get("PA_MATRIX_SOURCE") == SOURCE and env.get("PA_MATRIX_AUTHORITY_SHA256") == AUTHORITY and
         env.get("PA_MATRIX_APPROVAL_EVIDENCE") == APPROVAL, "Wrong approved matrix bindings")
    return {"ticket": "TTL-011", "feature": feature, "source": SOURCE, "authority": AUTHORITY, "approval": APPROVAL,
            "approvedBase": BASE, "integrationBase": INTEGRATION_BASE}


def workflow_contract(text):
    """Strict approved workflow template + exact pins; not a general YAML parser.

    YAML is deliberately not added as a dependency. Source review remains required.
    Negative fixtures guard privileged triggers/tokens and skipped core commands.
    """
    need("\t" not in text and "\r" not in text, "Noncanonical workflow")
    code = "\n".join(line.split(" #", 1)[0] for line in text.splitlines() if not line.lstrip().startswith("#"))
    for forbidden in ("pull_request_target", "secrets.", "write-all", "contents: write", "id-token:",
                      "continue-on-error", "sudo ", "curl ", "wget ", "uninstall", "connectedDebug", "emulator", "release", "deploy", "signing"):
        need(forbidden not in code, f"Forbidden workflow capability: {forbidden}")
    conditions = re.findall(r"(?m)^\s*if:\s*(.*)$", code)
    need(conditions == ["always()", "always()"], "Only failure-evidence/upload steps may be conditional")
    actions = re.findall(r"(?m)^\s*- uses: ([\w/-]+)@([^\s]+)", code)
    need(len(actions) == 3 and dict(actions) == PINS, "Unexpected/unpinned action")
    need(len(re.findall(r"\buses:", code)) == 3, "Unaccounted workflow action")
    need(re.search(r"(?m)^permissions:\n  contents: read$", code), "Wrong token scope")
    need(re.search(r"(?m)^on:\n  pull_request:\n  push:\n    branches: \[develop\]\n  workflow_dispatch:$", code), "Wrong triggers")
    for token in ("runs-on: ubuntu-24.04", "persist-credentials: false", "distribution: temurin", "java-version: '17.0.16+8'",
                  "sdkmanager 'platforms;android-36' 'build-tools;35.0.0'", "ref: ${{ env.FEATURE_SHA }}",
                  'sdkmanager_bin="$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager"',
                  'export PATH="$(dirname "$sdkmanager_bin"):$ANDROID_HOME/platform-tools:$PATH"',
                  '"$GITHUB_ENV"', '"$GITHUB_PATH"', 'app/build/ci/sdk-setup.log',
                  ":app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug",
                  "set -euo pipefail", "if: always()", "app/build/test-results/", "app/build/outputs/", "app/build/reports/"):
        need(token in code, f"Missing workflow contract: {token}")
    need(re.search(r"(?m)^            app/build/outputs/$", code), "Upload must retain the complete checksummed build-output tree")
    need("--offline" not in code, "Hosted checks may download approved dependencies")
    # Only evidence collection/upload may have a condition; core build cannot skip.
    blocks = re.split(r"(?m)^      - ", code)[1:]
    need(len(blocks) == 6, "Unexpected workflow steps")
    for block in blocks[:4]:
        need(not re.search(r"(?m)^\s*(?:if|continue-on-error):", block), "Skippable core CI step")
        need("|| true" not in block, "Masked core failure")
    need(code.count("jobs:") == 1 and len(re.findall(r"(?m)^  [\w-]+:\n    runs-on:", code)) == 1, "Extra workflow jobs")


def candidate(root):
    authority = bindings(os.environ)
    need(git(root, "rev-parse", "HEAD").decode().strip() == authority["feature"], "Candidate changed")
    need(not git(root, "status", "--porcelain=v1", "-z", "--untracked-files=all"), "Dirty tracked/untracked candidate")
    subprocess.run(["git", "-C", str(root), "merge-base", "--is-ancestor", INTEGRATION_BASE, authority["feature"]], check=True)
    inputs = {}
    flags = git(root, "ls-files", "-v", "-z").split(b"\0")
    need(all(entry.startswith(b"H ") for entry in flags if entry), "Hidden/skip-worktree index inputs rejected")
    entries = git(root, "ls-files", "--stage", "-z").split(b"\0")
    names = [entry.split(b"\t", 1)[1] for entry in entries if entry]
    attributes = subprocess.check_output(["git", "-C", str(root), "check-attr", "-z", "--stdin", "filter"], input=b"\0".join(names) + b"\0").split(b"\0")[:-1]
    need(len(attributes) == len(names) * 3 and all(value in (b"unspecified", b"unset") for value in attributes[2::3]), "External clean filters are not admitted")
    for entry in filter(None, entries):
        header, raw_name = entry.split(b"\t", 1)
        mode, oid, stage = header.split()
        name = os.fsdecode(raw_name)
        need(mode in (b"100644", b"100755") and stage == b"0", f"Unsupported index input: {name}")
        path = root / name
        safe_file(path)
        # --path applies .gitattributes (notably gradlew.bat CRLF); inventory still binds raw bytes.
        clean_oid = git(root, "hash-object", "--path=" + name, "--", name).strip()
        need(clean_oid == oid, f"Attribute-normalized blob drift: {name}")
        inputs[name] = sha(path)
    need(not (root / "local.properties").exists(), "Uninventoried local.properties overrides toolchain")
    methods = {}
    for source_set, floor in (("test", 50), ("androidTest", 53)):
        current = source_methods(root / f"app/src/{source_set}")
        original = set()
        for raw_name in git(root, "ls-tree", "-r", "--name-only", "-z", INTEGRATION_BASE, f"app/src/{source_set}").split(b"\0"):
            if raw_name.endswith(b".kt"):
                name = os.fsdecode(raw_name)
                blob = git(root, "show", f"{INTEGRATION_BASE}:{name}")
                original.update(discover(blob.decode("utf-8")))
                need(git(root, "show", f"{authority['feature']}:{name}") == blob, f"Original safety test bytes changed: {name}")
        baseline_preserved(current, original, floor)
        classes = {cls.rsplit(".", 1)[1] for cls, _ in current}
        need(REQUIRED[source_set] <= classes, f"Missing {source_set} test classes")
        for path in (root / f"app/src/{source_set}").rglob("*.kt"):
            need(not re.search(r"\b(?:assumeTrue|assumeFalse|assumeNotNull|Assume\.)", path.read_text()), f"Conditional skip: {path}")
        methods[source_set] = [list(case) for case in sorted(current)]
    workflow_contract((root / ".github/workflows/android.yml").read_text())
    return {"binding": authority, "inputs": inputs, "methods": methods}


def paths(review_root):
    root = Path(os.fsdecode(git(Path.cwd(), "rev-parse", "--show-toplevel")).strip()).absolute()
    review_root = review_root.absolute()
    need(review_root == root / "app/build/ttl011-independent-review" and review_root.resolve() == review_root, "Unexpected/aliased review root")
    return root, review_root


def begin(review_root):
    root, review_root = paths(review_root)
    need(not review_root.exists() and not review_root.is_symlink(), "Review root must be absent; retain earlier evidence")
    inventory = candidate(root)
    need(not (root / "app/build/outputs/androidTest-results/connected/debug").exists(), "Earlier connected results retained; owner must relocate before review")
    need(not (root / "app/build/reports/androidTests/connected/debug").exists(), "Earlier connected report retained")
    review_root.mkdir(parents=True, exist_ok=False)
    inventory.update({"schema": "ttl011-inputs/v1", "beganNs": time.time_ns()})
    absent_write(review_root / "inputs.json", inventory)
    absent_write(review_root / "authority.json", {"schema": "ttl011-accounting/v1", "inputsSha256": sha(review_root / "inputs.json"),
        "binding": inventory["binding"], "beganNs": inventory["beganNs"]})
    print("TTL-011 inventory captured; checks/CI/UAT NOT executed")


def validate(review_root, output):
    root, review_root = paths(review_root)
    output = output.absolute()
    need(output == review_root / "results.json" and not output.exists() and not output.is_symlink(), "Absent results.json required")
    safe_file(review_root / "inputs.json"); safe_file(review_root / "authority.json")
    inputs = json.loads((review_root / "inputs.json").read_text())
    authority = json.loads((review_root / "authority.json").read_text())
    need(authority["inputsSha256"] == sha(review_root / "inputs.json"), "Inventory modified")
    current = candidate(root)
    need(authority["binding"] == inputs["binding"] == current["binding"], "Authority drift")
    need(inputs["inputs"] == current["inputs"] and inputs["methods"] == current["methods"], "Raw source/method inventory changed")
    need(authority["beganNs"] == inputs["beganNs"] <= time.time_ns(), "Invalid begin time")
    markers = {}
    previous = inputs["beganNs"]
    for stage in ("host", "apk", "lint", "device"):
        marker = review_root / f"{stage}-start"
        safe_file(marker)
        stamp = marker.stat().st_mtime_ns
        need(previous <= stamp <= time.time_ns(), f"Out-of-order/future {stage} marker")
        previous = stamp; markers[stage] = marker
    results = {}
    artifacts = []
    for source_set, directory, marker, html in (
        ("test", "app/build/test-results/testDebugUnitTest", "host", "app/build/reports/tests/testDebugUnitTest/index.html"),
        ("androidTest", "app/build/outputs/androidTest-results/connected/debug", "device", "app/build/reports/androidTests/connected/debug/index.html"),
    ):
        reports = sorted((root / directory).rglob("*.xml"))
        for report in reports: safe_file(report)
        results[source_set] = junit(reports, {tuple(case) for case in inputs["methods"][source_set]}, markers[marker])
        safe_file(root / html); artifacts.append(fresh(root / html, markers[marker]))
    for name, stage in (("app/build/outputs/apk/debug/app-debug.apk", "apk"),
                        ("app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk", "apk"),
                        ("app/build/reports/lint-results-debug.html", "lint")):
        safe_file(root / name); artifacts.append(fresh(root / name, markers[stage]))
    safe_file(root / "app/build/reports/lint-results-debug.xml")
    results["lint"] = lint(root / "app/build/reports/lint-results-debug.xml", markers["lint"])
    need(candidate(root) == current, "Candidate drift during accounting")
    for artifact in artifacts + [item for suite in ("test", "androidTest") for item in results[suite]["inputs"]] + [results["lint"]["input"]]:
        need(sha(Path(artifact["path"])) == artifact["sha256"], "Output changed during accounting")
    marker_inventory = {stage: {"sha256": sha(path), "mtimeNs": path.stat().st_mtime_ns} for stage, path in markers.items()}
    absent_write(output, {"schema": "ttl011-results/v1", "binding": current["binding"], "inputsSha256": authority["inputsSha256"],
        "results": results, "artifacts": artifacts, "markers": marker_inventory, "completedNs": time.time_ns(), "accountingPassed": True,
        "limits": "Artifact accounting only, NOT command receipts, independent approval, hosted CI or human UAT"})
    print(f"TTL-011 artifact accounting passed: {output}")


class SelfTest(unittest.TestCase):
    def env(self):
        return {"PA_TICKET_ID": "TTL-011", "PA_FEATURE_SHA": "a" * 40, "PA_MATRIX_SOURCE": SOURCE,
                "PA_MATRIX_AUTHORITY_SHA256": AUTHORITY, "PA_MATRIX_APPROVAL_EVIDENCE": APPROVAL}
    def test_exact_authority(self):
        self.assertEqual(bindings(self.env())["feature"], "a" * 40)
        for key in self.env():
            env = self.env(); env[key] += "changed"
            with self.assertRaises(Invalid): bindings(env)
    def test_workflow_and_negative_contracts(self):
        workflow = (Path(__file__).resolve().parents[1] / ".github/workflows/android.yml").read_text()
        workflow_contract(workflow)
        for changed in (workflow.replace("pull_request:", "pull_request_target:"), workflow.replace("contents: read", "contents: write"),
                        workflow.replace(PINS["actions/checkout"], "v4"), workflow.replace(":app:lintDebug", ""),
                        workflow.replace("runs-on: ubuntu-24.04", "runs-on: ubuntu-latest"),
                        workflow.replace('export PATH="$(dirname "$sdkmanager_bin"):$ANDROID_HOME/platform-tools:$PATH"', "# missing SDK PATH"),
                        workflow.replace('            app/build/outputs/\n', '            app/build/outputs/apk/\n'),
                        workflow.replace("      - name: Host checks", "      - if: false\n        name: Host checks")):
            with self.assertRaises(Invalid): workflow_contract(changed)
    def test_hosted_sdk_discovery_without_sdkmanager_on_path(self):
        workflow = (Path(__file__).resolve().parents[1] / ".github/workflows/android.yml").read_text()
        start = workflow.index('          export ANDROID_HOME=')
        end = workflow.index("          printf 'SDK manager:", start)
        snippet = "\n".join(line[10:] for line in workflow[start:end].splitlines())
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            executable = root / "sdk/cmdline-tools/12.0/bin/sdkmanager"
            executable.parent.mkdir(parents=True)
            executable.write_text("#!/bin/sh\nprintf 'sdk-fixture-12.0\\n'\n")
            executable.chmod(0o700)
            env = {**os.environ, "ANDROID_HOME": str(root / "sdk"), "GITHUB_ENV": str(root / "env"), "GITHUB_PATH": str(root / "path")}
            result = subprocess.check_output(["bash", "-euc", snippet + '\nprintf "%s\\n" "$sdkmanager_bin"; sdkmanager --version'], env=env, cwd=root, text=True)
            self.assertEqual(result.splitlines(), [str(executable), "sdk-fixture-12.0"])
            self.assertIn(str(executable.parent), (root / "path").read_text())
            self.assertIn(str(root / "sdk"), (root / "env").read_text())

    def test_exact_xml_and_negative_cases(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder); marker = root / "marker"; marker.touch()
            report = root / "tests.xml"
            valid = '<testsuite tests="1" failures="0" errors="0"><testcase classname="C" name="m"/></testsuite>'
            report.write_text(valid); self.assertEqual(junit([report], {("C", "m")}, marker)["executed"], 1)
            for invalid in (valid.replace('failures="0"', 'failures="1"'), valid.replace('/>', '><skipped/></testcase>'),
                            valid.replace('tests="1"', 'tests="2"'), '<!DOCTYPE x><x/>', valid.replace('name="m"', 'name="other"')):
                report.write_text(invalid)
                with self.assertRaises(Invalid): junit([report], {("C", "m")}, marker)
            report.write_text(valid)
            with self.assertRaises(Invalid): junit([report, report], {("C", "m")}, marker)
            os.utime(report, ns=(1, 1))
            with self.assertRaises(Invalid): junit([report], {("C", "m")}, marker)
            os.utime(report, ns=(time.time_ns() + 10**12,) * 2)
            with self.assertRaises(Invalid): fresh(report, marker)
    def test_atomic_output_and_alias_rejection(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder); target = root / "result.json"
            absent_write(target, {"ok": True})
            with self.assertRaises(FileExistsError): absent_write(target, {})
            alias = root / "alias"; alias.symlink_to(target)
            with self.assertRaises(Invalid): safe_file(alias)
            hard = root / "hard"; os.link(target, hard)
            with self.assertRaises(Invalid): safe_file(hard)
    def test_begin_final_lifecycle_and_drift_fixtures(self):
        """Synthetic XML/APKs in an isolated Git repo, not application check evidence."""
        workflow = (Path(__file__).resolve().parents[1] / ".github/workflows/android.yml").read_text()
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder).resolve()
            env = {"PATH": os.environ["PATH"], "HOME": str(root), "LANG": "C.UTF-8", "GIT_CONFIG_NOSYSTEM": "1", "GIT_CONFIG_GLOBAL": os.devnull}
            with patch.dict(os.environ, env, clear=True):
                subprocess.run(["git", "init", "--template=", "--initial-branch=fixture", str(root)], check=True, capture_output=True)
                def put(name, text):
                    path = root / name; path.parent.mkdir(parents=True, exist_ok=True); path.write_text(text)
                    return path
                put(".gitignore", "app/build/\n")
                put(".gitattributes", "* text=auto eol=lf\n*.bat text eol=crlf\n")
                bat = root / "wrapper.bat"
                bat.write_bytes(b"echo fixture\r\n")
                for source_set, count in (("test", 50), ("androidTest", 53)):
                    put(f"app/src/{source_set}/Base{source_set}.kt", f"package p\nclass Base{source_set} {{\n" +
                        "\n".join(f"@Test fun original{i}() {{}}" for i in range(count)) + "\n}")
                def commit():
                    git(root, "add", ".")
                    subprocess.run(["git", "-C", str(root), "-c", "user.name=Fixture", "-c", "user.email=fixture@example.invalid",
                        "-c", "commit.gpgSign=false", "commit", "-m", "synthetic accounting fixture"], check=True, capture_output=True)
                    return git(root, "rev-parse", "HEAD").decode().strip()
                baseline = commit()
                for source_set, classes in REQUIRED.items():
                    for cls in classes:
                        put(f"app/src/{source_set}/{cls}.kt", f"package p\nclass {cls} {{ @Test fun added() {{}} }}\n")
                put(".github/workflows/android.yml", workflow)
                feature = commit()
                os.environ.update(self.env(), PA_FEATURE_SHA=feature)
                old_cwd = Path.cwd()
                os.chdir(root)
                try:
                    with patch.dict(globals(), BASE=baseline, INTEGRATION_BASE=baseline):
                        review = root / "app/build/ttl011-independent-review"
                        begin(review)
                        with self.assertRaises(Invalid): begin(review)
                        inputs = json.loads((review / "inputs.json").read_text())
                        def report(source_set, path):
                            cases = inputs["methods"][source_set]
                            return put(path, f'<testsuite tests="{len(cases)}" failures="0" errors="0">' +
                                ''.join(f'<testcase classname="{cls}" name="{method}"/>' for cls, method in cases) + '</testsuite>')
                        (review / "host-start").touch()
                        host = report("test", "app/build/test-results/testDebugUnitTest/TEST-fixture.xml")
                        put("app/build/reports/tests/testDebugUnitTest/index.html", "fixture")
                        (review / "apk-start").touch()
                        put("app/build/outputs/apk/debug/app-debug.apk", "NOT AN APK: synthetic fixture")
                        put("app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk", "NOT AN APK: synthetic fixture")
                        (review / "lint-start").touch()
                        put("app/build/reports/lint-results-debug.xml", "<issues/>")
                        put("app/build/reports/lint-results-debug.html", "fixture")
                        (review / "device-start").touch()
                        report("androidTest", "app/build/outputs/androidTest-results/connected/debug/TEST-fixture.xml")
                        put("app/build/reports/androidTests/connected/debug/index.html", "fixture")
                        output = review / "results.json"
                        original_xml = host.read_text()
                        host.write_text(original_xml.replace('failures="0"', 'failures="1"'))
                        with self.assertRaises(Invalid): validate(review, output)
                        self.assertFalse(output.exists())
                        host.write_text(original_xml)
                        manifest = (review / "inputs.json").read_bytes()
                        (review / "inputs.json").write_bytes(manifest + b" ")
                        with self.assertRaises(Invalid): validate(review, output)
                        (review / "inputs.json").write_bytes(manifest)
                        put("untracked-source", "dirty fixture")
                        with self.assertRaises(Invalid): validate(review, output)
                        (root / "untracked-source").unlink() # exclusively allocated temporary fixture
                        bat.write_bytes(b"echo fixture\n")
                        git(root, "add", "--renormalize", "wrapper.bat")
                        self.assertFalse(git(root, "status", "--porcelain=v1", "-z", "--untracked-files=all"))
                        with self.assertRaises(Invalid): validate(review, output) # normalized blob matches, raw input drift does not
                        bat.write_bytes(b"echo fixture\r\n")
                        git(root, "add", "--renormalize", "wrapper.bat")
                        validate(review, output)
                        self.assertTrue(json.loads(output.read_text())["accountingPassed"])
                        with self.assertRaises(Invalid): validate(review, output)
                finally:
                    os.chdir(old_cwd)

    def test_baseline_identity_not_only_floor(self):
        with self.assertRaises(Invalid): baseline_preserved({("C", "wrong")}, {("C", "original")}, 1)
        with self.assertRaises(Invalid): discover('package p\n@Ignore\nclass C { @Test fun x() {} }')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--self-test", action="store_true")
    parser.add_argument("--begin", action="store_true")
    parser.add_argument("--review-root", type=Path)
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    if args.self_test:
        need(not args.begin and args.review_root is None and args.output is None, "Conflicting self-test options")
        result = unittest.TextTestRunner(verbosity=2).run(unittest.defaultTestLoader.loadTestsFromTestCase(SelfTest))
        return 0 if result.wasSuccessful() else 1
    need(args.review_root is not None, "--review-root required")
    if args.begin:
        need(args.output is None, "Begin cannot publish results"); begin(args.review_root)
    else:
        need(args.output is not None, "--output required"); validate(args.review_root, args.output)
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except (Invalid, OSError, ValueError, KeyError, subprocess.CalledProcessError) as error:
        print(f"TTL-011 accounting BLOCKED: {error}", file=sys.stderr)
        sys.exit(1)
