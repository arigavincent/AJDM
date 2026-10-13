"""Snapshot JUnit/JaCoCo evidence; fail on missing suites, failing tests or PRD coverage gaps."""
import argparse
import hashlib
import json
import shutil
import xml.etree.ElementTree as ET
from pathlib import Path

MODULES = ("model", "net", "store", "engine", "bench", "media", "core", "ipc", "cli", "ui", "verify")


def collect(project):
    suites = []
    for module in MODULES:
        module_tests = 0
        files = sorted((project / f"ajdm-{module}/build/test-results/test").glob("TEST-*.xml"))
        if not files:
            raise ValueError(f"Missing test results for ajdm-{module}")
        for file in files:
            root = ET.parse(file).getroot()
            cases = root.findall("testcase")
            count = len(cases)
            if count != int(root.attrib["tests"]):
                raise ValueError(f"Inconsistent test counts: {file}")
            for attribute, element in (("failures", "failure"), ("errors", "error"), ("skipped", "skipped")):
                if sum(case.find(element) is not None for case in cases) != int(root.attrib[attribute]):
                    raise ValueError(f"Inconsistent {element} counts: {file}")
            module_tests += count
            suites.append({"module": module, "suite": root.attrib["name"], "tests": count,
                           "failures": int(root.attrib["failures"]), "errors": int(root.attrib["errors"]),
                           "skipped": int(root.attrib["skipped"]), "seconds": float(root.attrib["time"]),
                           "xml": str(file.relative_to(project))})
        if module_tests == 0:
            raise ValueError(f"No executed test cases for ajdm-{module}")
    coverage_file = project / "build/reports/jacoco/backendCoverage/backendCoverage.xml"
    coverage = ET.parse(coverage_file).getroot()
    packages = {}
    for package in coverage.findall("package"):
        counters = {}
        for counter in package.findall("counter"):
            covered, missed = int(counter.attrib["covered"]), int(counter.attrib["missed"])
            counters[counter.attrib["type"]] = {"covered": covered, "missed": missed,
                                               "percent": 100 * covered / (covered + missed) if covered + missed else None}
        packages[package.attrib["name"].replace("/", ".")] = counters
    for package in ("io.ajdm.engine", "io.ajdm.store"):
        line = packages.get(package, {}).get("LINE")
        if not line or line["percent"] is None or line["percent"] < 80:
            raise ValueError(f"PRD 80% line coverage gate failed for {package}")
    totals = {field: sum(suite[field] for suite in suites) for field in ("tests", "failures", "errors", "skipped")}
    if totals["failures"] or totals["errors"] or totals["skipped"]:
        raise ValueError(f"Suite is not fully passing: {totals}")
    return {"totals": totals, "suites": suites, "coverage_packages": packages}, coverage_file


def snapshot(project, output):
    result, coverage_file = collect(project)
    output.mkdir(parents=True, exist_ok=True)
    for suite in result["suites"]:
        source = project / suite["xml"]
        target = output / "junit" / suite["module"] / source.name
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(source, target)
    shutil.copyfile(coverage_file, output / "backend-coverage.xml")
    (output / "test-summary.json").write_text(json.dumps(result, indent=2) + "\n")
    files = set()
    for module in MODULES:
        directory = project / f"ajdm-{module}"
        files.update(directory.glob("src/**/*.java"))
        files.update(directory.glob("src/main/resources/**/*"))
        files.update(directory.glob("*.gradle.kts"))
        files.update(directory.glob("gradle.lockfile"))
    for pattern in ("*.gradle.kts", "gradle.properties", "gradle.lockfile", "gradlew*", "gradle/**/*",
                    ".github/workflows/*", "tools/*.py", "tools/tests/*.py", "docs/*.md", "README.md", "ENGINE_PLAN.md",
                    "architecture.md", "AJDM_PRD.pdf"):
        files.update(project.glob(pattern))
    manifest = [f"{hashlib.sha256(file.read_bytes()).hexdigest()}  {file.relative_to(project)}"
                for file in sorted(files) if file.is_file()]
    (output / "source-files.sha256").write_text("\n".join(manifest) + "\n")
    print(f"Verified {result['totals']['tests']} Java tests; engine/store line coverage >=80%; {len(manifest)} source/config hashes")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--project", type=Path, default=Path(__file__).resolve().parents[1])
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    project = args.project.resolve()
    try:
        snapshot(project, args.output or project / "artifacts/backend-verification")
    except (OSError, ValueError, KeyError, ET.ParseError) as error:
        parser.error(str(error))


if __name__ == "__main__":
    main()
