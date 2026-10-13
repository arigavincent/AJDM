"""Keep the evidence collector from reporting incomplete or contradictory checks as passing."""
import importlib.util
import tempfile
import unittest
from pathlib import Path

spec = importlib.util.spec_from_file_location("backend_summary", Path(__file__).parents[1] / "summarize_backend_checks.py")
summary = importlib.util.module_from_spec(spec)
spec.loader.exec_module(summary)


class BackendSummaryTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.project = Path(self.temp.name)
        for module in summary.MODULES:
            directory = self.project / f"ajdm-{module}/build/test-results/test"
            directory.mkdir(parents=True)
            (directory / "TEST-fixture.xml").write_text(
                '<testsuite name="fixture" tests="1" failures="0" errors="0" skipped="0" time="1">'
                '<testcase name="passes"/></testsuite>')
        self.coverage = self.project / "build/reports/jacoco/backendCoverage/backendCoverage.xml"
        self.coverage.parent.mkdir(parents=True)
        self.coverage.write_text('<report>' + ''.join(
            '<package name="io/ajdm/' + module + '"><counter type="LINE" covered="80" missed="20"/></package>'
            for module in ("engine", "store")) + '</report>')

    def first_suite(self):
        return self.project / "ajdm-model/build/test-results/test/TEST-fixture.xml"

    def test_passing_evidence_has_exact_counts(self):
        result, _ = summary.collect(self.project)
        self.assertEqual({"tests": 11, "failures": 0, "errors": 0, "skipped": 0}, result["totals"])

    def test_missing_module_fails(self):
        self.first_suite().unlink()
        with self.assertRaisesRegex(ValueError, "Missing test results"):
            summary.collect(self.project)

    def test_no_executed_cases_is_not_a_passing_module(self):
        self.first_suite().write_text('<testsuite name="empty" tests="0" failures="0" errors="0" skipped="0" time="0"/>')
        with self.assertRaisesRegex(ValueError, "No executed test cases"):
            summary.collect(self.project)

    def test_failure_element_cannot_be_hidden_by_zero_attribute(self):
        self.first_suite().write_text(
            '<testsuite name="fixture" tests="1" failures="0" errors="0" skipped="0" time="1">'
            '<testcase name="broken"><failure message="failed"/></testcase></testsuite>')
        with self.assertRaisesRegex(ValueError, "Inconsistent failure counts"):
            summary.collect(self.project)

    def test_a_failing_suite_fails_the_pack(self):
        self.first_suite().write_text(
            '<testsuite name="fixture" tests="1" failures="1" errors="0" skipped="0" time="1">'
            '<testcase name="broken"><failure message="failed"/></testcase></testsuite>')
        with self.assertRaisesRegex(ValueError, "not fully passing"):
            summary.collect(self.project)

    def test_a_skipped_case_is_not_qualification(self):
        self.first_suite().write_text(
            '<testsuite name="fixture" tests="1" failures="0" errors="0" skipped="1" time="1">'
            '<testcase name="pending"><skipped/></testcase></testsuite>')
        with self.assertRaisesRegex(ValueError, "not fully passing"):
            summary.collect(self.project)

    def test_below_prd_coverage_fails(self):
        self.coverage.write_text(self.coverage.read_text().replace('covered="80" missed="20"', 'covered="79" missed="21"', 1))
        with self.assertRaisesRegex(ValueError, "80% line coverage gate failed"):
            summary.collect(self.project)


if __name__ == '__main__':
    unittest.main()
