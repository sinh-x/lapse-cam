#!/usr/bin/env python3
"""TTL-005 report accounting only. Never runs Gradle, ADB, installs, or deletes inputs.

Independent V6 requires the exact candidate and fresh V2/V3/V4/V5 markers. A
successful report is not human UAT, semantic review, or permission to operate a phone.
"""
import argparse
from collections import Counter
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
import xml.etree.ElementTree as ET

BASE = "4b4a6ee17492035c9f41a91b02fd93d5ff3f89f0"
SOURCE = "requirements:agent-teams/requirements/artifacts/2026-10-21-ttl-005-session-rollover.md"
AUTHORITY = "ab541c3e32568640a50c3ce7f33070c4cf6e1639cfe095c36fb7f8ce0a1f15f3"
APPROVAL = "c-20261021103657484"


class Invalid(ValueError):
    pass


def need(condition, message):
    if not condition:
        raise Invalid(message)


def sha(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def kotlin_code(text):
    """Remove comments and quoted strings, preserving backtick function identifiers."""
    out = []
    i = 0
    while i < len(text):
        if text.startswith("//", i):
            end = text.find("\n", i)
            i = len(text) if end < 0 else end
        elif text.startswith("/*", i):
            depth = 1
            i += 2
            while depth and i < len(text):
                if text.startswith("/*", i):
                    depth += 1
                    i += 2
                elif text.startswith("*/", i):
                    depth -= 1
                    i += 2
                else:
                    out.append("\n" if text[i] == "\n" else " ")
                    i += 1
            need(depth == 0, "Unterminated Kotlin comment")
        elif text.startswith('"""', i):
            end = text.find('"""', i + 3)
            need(end >= 0, "Unterminated Kotlin raw string")
            out.append(" " * (end + 3 - i))
            i = end + 3
        elif text[i] in "\"'":
            quote = text[i]
            i += 1
            while i < len(text) and text[i] != quote:
                i += 2 if text[i] == "\\" else 1
            need(i < len(text), "Unterminated Kotlin string")
            i += 1
            out.append(" ")
        elif text[i] == "`":
            end = text.find("`", i + 1)
            need(end >= 0, "Unterminated Kotlin identifier")
            out.append(text[i:end + 1])
            i = end + 1
        else:
            out.append(text[i])
            i += 1
    return "".join(out)


def discover(text):
    code = kotlin_code(text)
    annotations = list(re.finditer(r"@(?:org\.junit\.)?Test\b", code))
    if not annotations:
        return set()
    need(not re.search(r"@(?:org\.junit\.)?Ignore\b", code), "Ignored source test")
    package = re.search(r"(?m)^\s*package\s+([\w.]+)", code)
    classes = re.findall(r"(?m)^class\s+(\w+)\b", code)
    need(package is not None and len(classes) == 1, "Unsupported test source/class structure")
    pattern = r"@(?:org\.junit\.)?Test\b(?:\s*\([^)]*\))?\s*(?:(?:public|internal)\s+)?fun\s+(`[^`]+`|\w+)\s*\("
    methods = re.findall(pattern, code)
    need(len(methods) == len(annotations), "Unaccounted @Test annotation")
    result = {(package[1] + "." + classes[0], method.strip("`")) for method in methods}
    need(len(result) == len(methods), "Duplicate source test")
    return result


def source_methods(root):
    need(root.is_dir(), f"Missing source set: {root}")
    need(not list(root.rglob("*.java")), "Java test source requires explicit discovery support")
    result = set()
    for path in sorted(root.rglob("*.kt")):
        methods = discover(path.read_text(encoding="utf-8"))
        need(not result.intersection(methods), f"Duplicate source methods: {path}")
        result.update(methods)
    need(result, f"Empty source set: {root}")
    return result


def baseline_preserved(current, baseline, floor):
    need(len(baseline) >= floor, "Unexpected/reduced baseline method inventory")
    need(baseline <= current, f"Baseline methods removed: {sorted(baseline - current)}")


def fresh(path, marker):
    need(path.is_file() and not path.is_symlink() and path.resolve() == path.absolute(), f"Missing/aliased artifact: {path}")
    need(path.stat().st_size > 0, f"Empty artifact: {path}")
    need(path.stat().st_mtime_ns >= marker.stat().st_mtime_ns, f"Stale artifact: {path}")
    need(path.stat().st_mtime_ns <= time.time_ns() + 1_000_000_000, f"Future artifact: {path}")
    return {"path": str(path), "sha256": sha(path), "bytes": path.stat().st_size}


def xml_root(path):
    raw = path.read_bytes()
    need(len(raw) <= 16 * 1024 * 1024, "Oversized XML report")
    need(not re.search(br"<!\s*(?:DOCTYPE|ENTITY)\b", raw, re.I), "DTD/entity XML rejected")
    try:
        return ET.fromstring(raw)
    except ET.ParseError as error:
        raise Invalid(f"Malformed XML: {path}") from error


def integer(value):
    need(value is not None and re.fullmatch(r"[0-9]+", value) is not None, "Malformed report counter")
    return int(value)


def junit(paths, expected, marker):
    need(paths, "Missing JUnit XML")
    seen = Counter()
    inputs = []
    for path in sorted(paths):
        inputs.append(fresh(path, marker))
        root = xml_root(path)
        need(root.tag in ("testsuite", "testsuites"), "Unexpected JUnit root")
        suites = list(root.iter("testsuite"))
        need(suites, "Missing testsuite")
        for suite in suites:
            cases = list(suite.iter("testcase"))
            need(integer(suite.get("tests")) == len(cases), "Contradictory test count")
            for counter in ("failures", "errors"):
                need(integer(suite.get(counter)) == 0, f"Nonzero {counter}")
            for counter in ("skipped", "ignored", "disabled"):
                need(integer(suite.get(counter, "0")) == 0, f"Nonzero {counter}")
        if root.tag == "testsuites":
            if root.get("tests") is not None:
                need(integer(root.get("tests")) == len(list(root.iter("testcase"))), "Contradictory aggregate count")
            for counter in ("failures", "errors", "skipped", "ignored", "disabled"):
                need(integer(root.get(counter, "0")) == 0, f"Nonzero aggregate {counter}")
        for case in root.iter("testcase"):
            need(not any(case.find(tag) is not None for tag in ("failure", "error", "skipped", "ignored")), "Failed/skipped case")
            need(case.get("status", "run") in ("run", "passed"), "Unexecuted/failed case")
            for flag in ("ignored", "disabled", "skipped"):
                need(case.get(flag, "false") in ("false", "0"), f"Unexecuted case flag: {flag}")
            need(case.get("classname") and case.get("name"), "Missing test identity")
            seen[(case.get("classname"), case.get("name"))] += 1
    need(all(count == 1 for count in seen.values()), "Duplicate test execution")
    need(set(seen) == expected, f"Method-set mismatch; missing={sorted(expected - set(seen))}, extra={sorted(set(seen) - expected)}")
    return {"executed": len(seen), "failures": 0, "errors": 0, "skipped": 0, "methods": sorted(seen), "inputs": inputs}


def lint(path, marker):
    artifact = fresh(path, marker)
    root = xml_root(path)
    need(root.tag == "issues", "Unexpected lint report")
    warnings = []
    for issue in root.findall("issue"):
        severity = issue.get("severity")
        need(severity in ("Warning", "Information", "Ignore"), f"Lint error/fatal: {issue.get('id')}")
        warnings.append({"id": issue.get("id"), "severity": severity, "message": issue.get("message"),
                         "locations": [dict(location.attrib) for location in issue.findall("location")]})
    return {"input": artifact, "errors": 0, "warnings": warnings, "humanDispositionRequired": True}


def absent_write(path, report):
    need(path.parent.resolve() == path.parent.absolute(), "Aliased output parent")
    data = (json.dumps(report, indent=2, sort_keys=True) + "\n").encode()
    # Exclusive creation, never truncate an existing result or follow an output symlink.
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600)
    with os.fdopen(fd, "wb") as stream:
        stream.write(data)
        stream.flush()
        os.fsync(stream.fileno())


def git(root, *args):
    return subprocess.check_output(["git", "-C", str(root), *args])


def validate(review_root, output):
    root = Path(git(Path.cwd(), "rev-parse", "--show-toplevel").decode().strip()).resolve()
    review_root = review_root.absolute()
    output = output.absolute()
    need(review_root == root / "app/build/ttl005-independent-review" and review_root.resolve() == review_root, "Unexpected/aliased review root")
    need(output.parent == review_root and output.name == "results.json" and not output.exists() and not output.is_symlink(), "Output must be absent results.json in review root")
    feature = os.environ.get("PA_FEATURE_SHA", "")
    need(re.fullmatch(r"[0-9a-f]{40}", feature) is not None, "Missing exact Feature SHA")
    need(os.environ.get("PA_TICKET_ID") == "TTL-005", "Wrong ticket binding")
    need(os.environ.get("PA_MATRIX_SOURCE") == SOURCE and os.environ.get("PA_MATRIX_AUTHORITY_SHA256") == AUTHORITY and
         os.environ.get("PA_MATRIX_APPROVAL_EVIDENCE") == APPROVAL, "Matrix bindings differ from approved requirements")
    def unchanged():
        need(git(root, "rev-parse", "HEAD").decode().strip() == feature, "Candidate changed")
        need(not git(root, "status", "--porcelain=v1", "-z", "--untracked-files=all"), "Candidate is dirty")
    unchanged()
    subprocess.run(["git", "-C", str(root), "merge-base", "--is-ancestor", BASE, feature], check=True)
    markers = {}
    for stage in ("host", "apk", "lint", "device"):
        marker = review_root / f"{stage}-start"
        need(marker.is_file() and not marker.is_symlink(), f"Missing/aliased {stage} marker")
        need(marker.stat().st_mtime_ns <= time.time_ns(), "Future marker")
        markers[stage] = marker
    inventory = {}
    for name in git(root, "ls-files", "-z").decode().split("\0"):
        if name:
            path = root / name
            need(path.is_file() and not path.is_symlink(), f"Missing/aliased tracked input: {name}")
            blob = git(root, "show", f"{feature}:{name}")
            need(path.read_bytes() == blob, f"Tracked bytes differ from candidate: {name}")
            inventory[name] = sha(path)
    results = {}
    for source_set, floor, reports, marker in (
        ("test", 42, root / "app/build/test-results/testDebugUnitTest", markers["host"]),
        ("androidTest", 32, root / "app/build/outputs/androidTest-results/connected/debug", markers["device"]),
    ):
        source_path = f"app/src/{source_set}"
        current = source_methods(root / source_path)
        baseline = set()
        for name in git(root, "ls-tree", "-r", "--name-only", BASE, source_path).decode().splitlines():
            if name.endswith(".kt"):
                baseline.update(discover(git(root, "show", f"{BASE}:{name}").decode()))
        baseline_preserved(current, baseline, floor)
        results[source_set] = junit(list(reports.rglob("*.xml")), current, marker)
    artifacts = []
    for path, marker in (
        ("app/build/outputs/apk/debug/app-debug.apk", markers["apk"]),
        ("app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk", markers["apk"]),
        ("app/build/reports/tests/testDebugUnitTest/index.html", markers["host"]),
        ("app/build/reports/androidTests/connected/debug/index.html", markers["device"]),
        ("app/build/reports/lint-results-debug.html", markers["lint"]),
    ):
        artifacts.append(fresh(root / path, marker))
    lint_result = lint(root / "app/build/reports/lint-results-debug.xml", markers["lint"])
    unchanged()
    report = {"schemaVersion": 1, "scope": "software-report-accounting-only; not human UAT or review acceptance",
              "featureSha": feature, "matrixSource": SOURCE, "matrixAuthoritySha256": AUTHORITY,
              "matrixApprovalEvidence": APPROVAL, "sourceSha256": inventory,
              "markers": {stage: {"path": str(path), "mtimeNs": path.stat().st_mtime_ns} for stage, path in markers.items()},
              "tests": results, "artifacts": artifacts, "lint": lint_result,
              "selfTests": self_test(quiet=True)}
    unchanged()
    absent_write(output, report)
    return report


class AccountingTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="ttl005-validator-")
        self.root = Path(self.temp.name).resolve()
        self.marker = self.root / "start"
        self.marker.touch()
        self.expected = {("p.T", "one")}
    def tearDown(self):
        self.temp.cleanup()  # Only this self-test's allocated temporary directory.
    def xml(self, text=None, name="result.xml"):
        path = self.root / name
        path.write_text(text or '<testsuite tests="1" failures="0" errors="0" skipped="0"><testcase classname="p.T" name="one"/></testsuite>')
        return path
    def test_complete(self):
        self.assertEqual(1, junit([self.xml()], self.expected, self.marker)["executed"])
    def test_missing(self):
        with self.assertRaises(Invalid): junit([], self.expected, self.marker)
    def test_stale(self):
        path = self.xml()
        os.utime(path, ns=(1, 1))
        with self.assertRaises(Invalid): junit([path], self.expected, self.marker)
    def test_duplicate(self):
        with self.assertRaises(Invalid): junit([self.xml(), self.xml(name="copy.xml")], self.expected, self.marker)
    def test_failed_skipped_and_errors(self):
        for tag in ("failure", "error", "skipped", "ignored"):
            with self.subTest(tag=tag), self.assertRaises(Invalid):
                junit([self.xml(f'<testsuite tests="1" failures="0" errors="0"><testcase classname="p.T" name="one"><{tag}/></testcase></testsuite>')], self.expected, self.marker)
    def test_counters(self):
        for value in ("-1", "1.0", "NaN", "", "2"):
            with self.subTest(value=value), self.assertRaises(Invalid):
                junit([self.xml(f'<testsuite tests="{value}" failures="0" errors="0"><testcase classname="p.T" name="one"/></testsuite>')], self.expected, self.marker)
    def test_nonzero_aggregate(self):
        with self.assertRaises(Invalid): junit([self.xml('<testsuites failures="1"><testsuite tests="1" failures="0" errors="0"><testcase classname="p.T" name="one"/></testsuite></testsuites>')], self.expected, self.marker)
    def test_nested_android_reports(self):
        path = self.root / "device" / "nested" / "TEST.xml"
        path.parent.mkdir(parents=True)
        self.xml().rename(path)
        self.assertEqual(1, junit(list(self.root.rglob("*.xml")), self.expected, self.marker)["executed"])
    def test_nested_suites(self):
        self.assertEqual(1, junit([self.xml('<testsuites tests="1"><testsuite tests="1" failures="0" errors="0"><testcase classname="p.T" name="one"/></testsuite></testsuites>')], self.expected, self.marker)["executed"])
    def test_dtd_entity_malformed(self):
        for raw in ('<!DOCTYPE testsuite [<!ENTITY x "x">]><testsuite/>', '<!ENTITY x "x">', '<broken'):
            with self.subTest(raw=raw), self.assertRaises(Invalid): xml_root(self.xml(raw))
    def test_kotlin_backticks_comments_and_strings(self):
        text = 'package p\nclass T { /* @Test fun fake() {} */ val s = "@Test fun ignored() {}"\n@Test fun `has spaces`() {}\n@Test(expected = Exception::class) fun two() {} }'
        self.assertEqual({("p.T", "has spaces"), ("p.T", "two")}, discover(text))
    def test_ignored_source(self):
        with self.assertRaises(Invalid): discover('package p\nclass T { @Ignore @Test fun one() {} }')
    def test_unaccounted_annotation(self):
        with self.assertRaises(Invalid): discover('package p\nclass T { @Test strange() }')
    def test_source_set_drift(self):
        with self.assertRaises(Invalid): baseline_preserved(set(), self.expected, 1)
        with self.assertRaises(Invalid): baseline_preserved(self.expected, set(), 1)
    def test_exact_names_and_no_extra_cases(self):
        with self.assertRaises(Invalid): junit([self.xml()], {("p.T", "renamed")}, self.marker)
    def test_apk_freshness(self):
        path = self.root / "fixture.apk"
        with self.assertRaises(Invalid): fresh(path, self.marker)
        path.write_bytes(b"fixture")
        self.assertEqual(7, fresh(path, self.marker)["bytes"])
        os.utime(path, ns=(1, 1))
        with self.assertRaises(Invalid): fresh(path, self.marker)
    def test_lint_fresh_and_zero_errors(self):
        self.assertEqual(0, lint(self.xml('<issues><issue id="style" severity="Warning"/></issues>'), self.marker)["errors"])
        with self.assertRaises(Invalid): lint(self.xml('<issues><issue id="bad" severity="Error"/></issues>'), self.marker)
    def test_absent_only_output(self):
        path = self.root / "results.json"
        absent_write(path, {"fixture": True})
        with self.assertRaises(FileExistsError): absent_write(path, {})
        self.assertEqual({"fixture": True}, json.loads(path.read_text()))
    def test_symlink_inputs_and_output(self):
        target = self.xml()
        link = self.root / "alias.xml"
        link.symlink_to(target)
        with self.assertRaises(Invalid): fresh(link, self.marker)
        with self.assertRaises(FileExistsError): absent_write(link, {})


def self_test(quiet=False):
    suite = unittest.defaultTestLoader.loadTestsFromTestCase(AccountingTests)
    if quiet:
        import io
        stream = io.StringIO()
    else:
        stream = sys.stderr
    result = unittest.TextTestRunner(stream=stream, verbosity=1).run(suite)
    need(result.wasSuccessful() and result.testsRun >= 19 and not result.skipped, "Validator self-tests failed")
    return {"executed": result.testsRun, "failures": 0, "errors": 0, "skipped": 0}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--self-test", action="store_true")
    parser.add_argument("--review-root", type=Path)
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    try:
        if args.self_test:
            need(args.review_root is None and args.output is None, "Self-test takes no report paths")
            print(json.dumps(self_test(), sort_keys=True))
        else:
            need(args.review_root is not None and args.output is not None, "Both --review-root and --output are required")
            report = validate(args.review_root, args.output)
            print(json.dumps({"report": str(args.output), "featureSha": report["featureSha"], "scope": report["scope"]}))
    except (Invalid, OSError, subprocess.SubprocessError) as error:
        print(f"NOT VERIFIED: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
