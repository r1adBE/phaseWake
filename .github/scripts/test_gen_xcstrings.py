#!/usr/bin/env python3
"""Selbsttest für die Schlüssel-Erkennung der Widgets in `gen-xcstrings.py`: erkannte Muster und Fälle, die
abbrechen müssen (lieber kein Katalog als ein Widget, das Schlüssel statt Texte zeigt).

Aufruf: `cd android && python3 -B -m unittest discover -s ../.github/scripts -p 'test_*.py'`.
"""
import importlib.util
import sys
import unittest
from pathlib import Path

sys.dont_write_bytecode = True
_SPEC = importlib.util.spec_from_file_location("gen_xcstrings", Path(__file__).resolve().parent / "gen-xcstrings.py")
gen = importlib.util.module_from_spec(_SPEC)
_SPEC.loader.exec_module(gen)

KNOWN = {"home_no_alarm", "home_next_today", "home_next_tomorrow", "night_woke", "widget_name",
         "hint_title|one", "hint_title|other", "unused_key"}
HELPER = "ios/Shared/Theme.swift"
HELPER_SRC = 'func L(_ key: String) -> String { Bundle.main.localizedString(forKey: key, value: nil, table: nil) }\n'


def scan(src, packages=None, known=KNOWN):
    return gen.scan({"ios/W/View.swift": src, HELPER: HELPER_SRC}, known, packages)


class LexTest(unittest.TestCase):
    def test_comments_and_strings(self):
        code, lits = gen.lex('a // L("x")\n/* L("y") /* tief */ */ b = "s\\"t"\n')
        self.assertNotIn("L(", code)
        self.assertEqual(lits, [('s"t', False)])
        self.assertEqual(code.count("\n"), 2)

    def test_interpolation_keeps_inner_code(self):
        code, lits = gen.lex('Text("☾ \\(L("widget_name"))")')
        self.assertEqual(code, 'Text(§1§(L(§0§)))')
        self.assertEqual(lits, [("widget_name", False), ("☾ ", True)])

    def test_raw_and_multiline(self):
        code, lits = gen.lex('#"a"b"# + """\nzeile "x"\n"""')
        self.assertEqual([t for t, _ in lits], ['a"b', '\nzeile "x"\n'])
        self.assertEqual(code.count("\n"), 2)

    def test_unterminated_fails(self):
        with self.assertRaises(gen.ScanError):
            gen.lex('let a = "offen\n')


class ScanTest(unittest.TestCase):
    def test_literal_calls_and_plurals(self):
        keys = scan('Text(L("home_no_alarm")); x = LP("hint_title", n, n); Text("Phase Wake")')
        self.assertEqual(keys, {"home_no_alarm", "hint_title"})

    def test_literal_without_call_counts(self):
        self.assertEqual(scan('Text("widget_name")'), {"widget_name"})

    def test_pass_through_parameter(self):
        src = ('func day(todayKey: String, tomorrowKey: String) -> String { L(todayKey) + L(tomorrowKey) }\n'
               'let d = day(todayKey: "home_next_today", tomorrowKey: "home_next_tomorrow")\n')
        self.assertEqual(scan(src), {"home_next_today", "home_next_tomorrow"})

    def test_catalog_keeps_only_listed(self):
        self.assertIn("unused_key", KNOWN)
        self.assertNotIn("unused_key", scan('L("night_woke", t)'))

    def assertFails(self, src, text, packages=None):
        with self.assertRaises(gen.ScanError) as ctx:
            scan(src, packages)
        self.assertTrue(any(text in e for e in ctx.exception.args[0]), ctx.exception.args[0])

    def test_dynamic_keys_fail(self):
        self.assertFails('L("sound_\\(name)")', "nicht als Literal")
        self.assertFails('L(reason.key)', "nicht als Literal")
        self.assertFails('L(prefix + "x")', "nicht als Literal")
        self.assertFails('names.map(L)', "ohne direktes Schlüssel-Argument")

    def test_missing_key_fails(self):
        self.assertFails('L("gibt_es_nicht")', "fehlt im Katalog")
        self.assertFails('LP("home_no_alarm", 2)', "fehlt im Katalog")  # kein Plural
        self.assertFails('L("hint_title")', "fehlt im Katalog")  # Plural über L

    def test_pass_through_with_variable_fails(self):
        self.assertFails('let d = day(todayKey: someKey, tomorrowKey: "home_next_tomorrow")', "Literal mit Schlüssel")

    def test_other_apis_fail(self):
        self.assertFails('String(localized: "widget_name")', "wird nicht erkannt")
        self.assertFails('NSLocalizedString("widget_name", comment: "")', "wird nicht erkannt")
        self.assertFails('Text(LocalizedStringKey(k))', "wird nicht erkannt")
        self.assertFails('Bundle.main.localizedString(forKey: k, value: nil, table: nil)', "direkte localizedString")

    def test_ternary_with_two_literals(self):
        self.assertEqual(scan('Text(L(model.since != nil ? "night_woke" : "widget_name", x))'),
                         {"night_woke", "widget_name"})
        self.assertEqual(scan('Label(L(r == .running ? "night_woke" : "widget_name"), systemImage: "x")'),
                         {"night_woke", "widget_name"})
        self.assertEqual(scan('LP(n > 1 ? "hint_title" : "hint_title", n)'), {"hint_title"})

    def test_ternary_unsafe_fails(self):
        self.assertFails('L(a ? "night_woke" : key)', "nicht als Literal")
        self.assertFails('L(a ? "night_woke" : b ? "widget_name" : "home_no_alarm")', "nicht als Literal")
        self.assertFails('L(a?.b == c ? "night_woke" : "widget_name")', "nicht als Literal")  # «?» in Bedingung
        self.assertFails('L(a ? "night_\\(n)" : "widget_name")', "nicht als Literal")
        self.assertFails('L(a ? "night_woke" : "gibt_es_nicht")', "fehlt im Katalog")
        self.assertFails('L(a ? "night_woke" : "hint_title")', "fehlt im Katalog")  # Plural über L
        self.assertFails('L((a ? "night_woke" : "widget_name") + x)', "nicht als Literal")

    KEYFUNC = ('func reasonKey(_ r: Reason) -> String {\n    switch r {\n    case .a: "night_woke"\n'
               '    case .b, .c: return "widget_name"\n    }\n}\n')

    def test_key_function_with_switch(self):
        self.assertEqual(scan(self.KEYFUNC + 'Text(L(reasonKey(night.reason)))'), {"night_woke", "widget_name"})

    def test_key_function_unsafe_fails(self):
        call = 'Text(L(reasonKey(r)))'
        for body in (self.KEYFUNC.replace('"widget_name"', 'other(r)'),          # Zweig ohne Literal
                     self.KEYFUNC.replace('"widget_name"', '"w_\\(r)"'),          # Interpolation
                     self.KEYFUNC.replace('switch r {', 'let x = 1\n    switch r {'),  # weiterer Code
                     self.KEYFUNC.replace('switch r {', 'switch f(r) {'),        # Ausdruck statt Wert
                     self.KEYFUNC + 'func reasonKey(_ s: String) -> String { s }\n',  # Überladung
                     self.KEYFUNC + 'let reasonKey = { (r: Reason) in key }\n',  # verdeckt durch Eigenschaft
                     self.KEYFUNC.replace('-> String', '-> String?')):
            self.assertFails(body + call, "nicht als Literal")
        self.assertFails(self.KEYFUNC.replace('"widget_name"', '"gibt_es_nicht"') + call, "fehlt im Katalog")
        self.assertFails(self.KEYFUNC + 'Text(L(reasonKey(r) + x))', "nicht als Literal")
        self.assertFails(self.KEYFUNC + 'Text(L(reasonKey(a)(b)))', "nicht als Literal")
        self.assertFails(self.KEYFUNC + call, "nicht als Literal", {"Core/A.swift": 'func reasonKey() -> String {""}'})

    def test_package_must_not_load_texts(self):
        self.assertEqual(scan('L("widget_name")', {"Core/A.swift": 'let k = "widget_name"'}), {"widget_name"})
        self.assertFails('L("widget_name")', "Paket lädt Texte", {"Core/A.swift": 'import SwiftUI'})
        self.assertFails('L("widget_name")', "Paket lädt Texte",
                         {"Core/A.swift": 'Bundle.main.localizedString(forKey: k, value: nil, table: nil)'})


PBX = """\
\t\tAAA /* Exceptions for "W" folder in "W" target */ = {
\t\t\tisa = PBXFileSystemSynchronizedBuildFileExceptionSet;
\t\t\tmembershipExceptions = (
\t\t\t\tInfo.plist,
\t\t\t);
\t\t\ttarget = TTT /* W */;
\t\t};
\t\tGGG /* W */ = {isa = PBXFileSystemSynchronizedRootGroup; exceptions = (AAA /* Exceptions */, ); path = W; };
\t\tSSS /* Shared */ = {isa = PBXFileSystemSynchronizedRootGroup; path = Shared; sourceTree = "<group>"; };
\t\tTTT /* W */ = {
\t\t\tisa = PBXNativeTarget;
\t\t\tbuildPhases = (
\t\t\t\tPPP /* Sources */,
\t\t\t);
\t\t\tfileSystemSynchronizedGroups = (
\t\t\t\tGGG /* W */,
\t\t\t\tSSS /* Shared */,
\t\t\t);
\t\t\tpackageProductDependencies = (
\t\t\t\tKKK /* PhaseWakeCore */,
\t\t\t);
\t\t};
\t\tPPP /* Sources */ = {
\t\t\tisa = PBXSourcesBuildPhase;
\t\t\tfiles = (
\t\t\t);
\t\t};
\t\tKKK /* PhaseWakeCore */ = {
\t\t\tisa = XCSwiftPackageProductDependency;
\t\t};
"""


class TargetFilesTest(unittest.TestCase):
    def test_folders_and_packages(self):
        self.assertEqual(gen.target_files("W", PBX), (["W", "Shared"], ["PhaseWakeCore"]))

    def test_single_source_files_fail(self):
        pbx = PBX.replace("\t\t\tfiles = (\n\t\t\t);",
                          "\t\t\tfiles = (\n\t\t\t\tFFF /* X.swift in Sources */,\n\t\t\t);")
        with self.assertRaises(gen.ScanError):
            gen.target_files("W", pbx)

    def test_unknown_package_fails(self):
        with self.assertRaises(gen.ScanError):
            gen.target_files("W", PBX.replace("PhaseWakeCore", "Fremd"))

    def test_real_project_widgets(self):
        """Echte Widgets: Erkennung läuft durch und findet die Texte, die sie sicher zeigen."""
        pbx = gen.PBXPROJ.read_text()
        for target, key in (("PhaseWakeWidget", "home_no_alarm"), ("PhaseWakePhoneWidget", "widget_name")):
            folders, _ = gen.target_files(target, pbx)
            self.assertIn("Shared", folders)
            known = {k for m in gen.TARGETS[target] for k in gen.load(m)["en"]}
            self.assertIn(key, gen.target_keys(target, known))

    def test_real_project_watch(self):
        """Echte Watch-App: Erkennung läuft durch, inklusive der Tests, die im Watch-Bundle laufen."""
        pbx = gen.PBXPROJ.read_text()
        self.assertIn("PhaseWakeWatch", gen.KUERZEN)
        self.assertEqual(gen.hosted_tests("PhaseWakeWatch", pbx), ["PhaseWakeWatchTests"])
        self.assertEqual(gen.hosted_tests("PhaseWake", pbx), ["PhaseWakeTests"])
        known = {k for m in gen.TARGETS["PhaseWakeWatch"] for k in gen.load(m)["en"]}
        keys = gen.target_keys("PhaseWakeWatch", known)
        # Ternär (WatchEditorView), Schlüssel-Funktion ringReasonKey (RingView), Literal im Test.
        self.assertTrue({"editor_new", "editor_edit", "ring_reason_light", "ring_reason_short_night"} <= keys, keys)

    def test_real_project_phone_stays_full(self):
        """Die iPhone-App wählt Schlüssel zur Laufzeit; wird sie je gekürzt, muss die Erkennung das abfangen."""
        self.assertNotIn("PhaseWake", gen.KUERZEN)
        known = {k for m in gen.TARGETS["PhaseWake"] for k in gen.load(m)["en"]}
        with self.assertRaises(gen.ScanError):
            gen.target_keys("PhaseWake", known)


if __name__ == "__main__":
    unittest.main()
