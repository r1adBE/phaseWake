#!/usr/bin/env python3
"""Selbsttest für die Prüfung des Startprofils in `release_check.py`: Regeln, die greifen bzw. nicht greifen.

Aufruf: `python3 .github/scripts/test_release_check.py` (läuft in der CI im Schritt «Regeln»).
"""
import sys
import tempfile
import unittest
from pathlib import Path

sys.dont_write_bytecode = True
sys.path.insert(0, str(Path(__file__).resolve().parent))
import release_check  # noqa: E402

# Ausschnitt einer aufgelösten Fassung, wie AGP sie schreibt.
EXPANDED = ["Lcom/phasewake/app/ui/MainActivity;"] + [
    f"HSPLcom/phasewake/app/ui/MainActivity;->m{i}()V" for i in range(12)
] + [f"HSPLcom/phasewake/app/ui/home/Home{i};->draw()V" for i in range(12)]


class ProfileCheckTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.dir = Path(self.tmp.name)
        self.expanded = self.dir / "expanded.txt"
        self.expanded.write_text("\n".join(EXPANDED) + "\n", encoding="utf-8")

    def tearDown(self):
        self.tmp.cleanup()

    def errors(self, *rules):
        path = self.dir / "baseline-prof.txt"
        path.write_text("\n".join(rules) + "\n", encoding="utf-8")
        return release_check.check_profile("app", str(path), str(self.expanded))

    def test_single_star_stops_before_the_semicolon(self):
        # Die frühere Regel: «*» endet vor «;», «->» folgt nie direkt – greift nicht.
        self.assertEqual(len(self.errors("HSPLcom/phasewake/app/ui/*->**(**)**")), 1)
        self.assertEqual(self.errors("HSPLcom/phasewake/app/ui/*;->**(**)**"), [])

    def test_single_star_stays_in_its_package(self):
        pattern = release_check.profile_pattern("HSPLcom/phasewake/app/ui/*;->**(**)**")
        self.assertIsNone(pattern.fullmatch("HSPLcom/phasewake/app/ui/home/Home1;->draw()V"))
        self.assertIsNotNone(pattern.fullmatch("HSPLcom/phasewake/app/ui/MainActivity;->m1()V"))

    def test_double_star_covers_sub_packages(self):
        self.assertEqual(self.errors("HSPLcom/phasewake/app/ui/**->**(**)**"), [])

    def test_exact_class_line_and_missing_class(self):
        self.assertEqual(self.errors("Lcom/phasewake/app/ui/MainActivity;"), [])
        self.assertEqual(len(self.errors("Lcom/phasewake/app/ring/PhoneRingActivity;")), 1)

    def test_too_few_lines_for_a_wildcard(self):
        self.expanded.write_text("HSPLcom/phasewake/app/ui/MainActivity;->m()V\n", encoding="utf-8")
        self.assertEqual(len(self.errors("HSPLcom/phasewake/app/ui/*;->**(**)**")), 1)

    def test_missing_expanded_profile(self):
        self.expanded.unlink()
        self.assertIn("aufgelöstes Startprofil fehlt", self.errors("Lcom/x/A;")[0])


if __name__ == "__main__":
    unittest.main()
