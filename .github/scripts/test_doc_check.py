#!/usr/bin/env python3
"""Selbsttest für `doc_check.py`: Musterfälle, die gemeldet bzw. nicht gemeldet werden müssen.

Aufruf: `python3 .github/scripts/test_doc_check.py` (läuft in der CI im Schritt «Regeln»).
"""
import sys
import tempfile
import unittest
from pathlib import Path

sys.dont_write_bytecode = True
sys.path.insert(0, str(Path(__file__).resolve().parent))
import doc_check  # noqa: E402


class DocCheckTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = doc_check.ROOT
        doc_check.ROOT = Path(self.tmp.name)

    def tearDown(self):
        doc_check.ROOT = self.root
        self.tmp.cleanup()

    def findings(self, source, swift=False):
        path = doc_check.ROOT / ("t.swift" if swift else "t.kt")
        path.write_text(source, encoding="utf-8")
        out = []
        (doc_check.check_swift if swift else doc_check.check_kotlin)(path, out)
        return [o.split(":")[1] for o in out]

    # Kotlin

    def test_kotlin_plain(self):
        self.assertEqual(self.findings("fun f() = 1\n"), ["1"])
        self.assertEqual(self.findings("/** F. */\nfun f() = 1\n"), [])

    def test_kotlin_block_comment_is_no_doc(self):
        self.assertEqual(self.findings("/* kein KDoc */\nfun f() = 1\n"), ["2"])
        self.assertEqual(self.findings("/**\n * Mehrzeilig.\n */\nfun f() = 1\n"), [])
        self.assertEqual(self.findings("/*\n * Mehrzeilig, kein KDoc.\n */\nfun f() = 1\n"), ["4"])

    def test_kotlin_annotations(self):
        self.assertEqual(self.findings("/** A. */\nobject A {\n    @JvmStatic fun f() = 1\n}\n"), ["3"])
        self.assertEqual(self.findings("/** A. */\nobject A {\n    /** F. */\n    @JvmStatic fun f() = 1\n}\n"), [])
        self.assertEqual(self.findings("/** F. */\n@Suppress(\"X\")\nfun f() = 1\n"), [])

    def test_kotlin_tailrec(self):
        self.assertEqual(self.findings("tailrec fun f(n: Int): Int = if (n == 0) 0 else f(n - 1)\n"), ["1"])

    def test_kotlin_companion_members(self):
        src = "/** A. */\nclass A {\n    companion object {\n        const val X = 1\n        fun g() = 2\n" \
              "        private fun h() = 3\n    }\n}\n"
        self.assertEqual(self.findings(src), ["4", "5"])

    def test_kotlin_nested_public_types(self):
        src = "/** A. */\nclass A {\n    /** B. */\n    enum class B {\n        X, Y;\n\n        fun f() = 1\n" \
              "    }\n}\n"
        self.assertEqual(self.findings(src), ["7"])

    def test_kotlin_private_scopes_are_skipped(self):
        src = "private class A {\n    fun f() = 1\n}\n/** B. */\ninternal object B {\n    val x = 1\n}\n"
        self.assertEqual(self.findings(src), [])

    def test_kotlin_locals_are_skipped(self):
        src = "/** F. */\nfun f(): Int {\n    val x = 1\n    fun g() = x\n    return g()\n}\n" \
              "/** A. */\nobject A {\n    init {\n        val y = 2\n    }\n    private val z = object : Any() {\n" \
              "        val w = 3\n    }\n}\n"
        self.assertEqual(self.findings(src), [])

    def test_kotlin_class_with_multiline_header(self):
        src = "/** A. */\ndata class A(\n    val x: Int,\n    val y: Int = 1,\n) : Base() {\n    fun f() = x\n}\n"
        self.assertEqual(self.findings(src), ["6"])

    def test_kotlin_exceptions(self):
        src = "/** A. */\nclass A : B {\n    override fun f() = 1\n    protected fun g() = 2\n" \
              "    val x: Int\n        get() = 1\n}\nfun interface I {\n    /** C. */\n    fun call()\n}\n"
        self.assertEqual(self.findings(src), ["5"])

    def test_kotlin_braces_in_strings(self):
        src = '/** A. */\nobject A {\n    /** S. */\n    const val S = "{"\n    fun f() = 1\n}\n'
        self.assertEqual(self.findings(src), ["5"])

    # Swift

    def test_swift_plain(self):
        self.assertEqual(self.findings("public func f() {}\n", swift=True), ["1"])
        self.assertEqual(self.findings("/// F.\npublic func f() {}\n", swift=True), [])

    def test_swift_private_set(self):
        src = "/// S.\npublic struct S {\n    public private(set) var x = 1\n}\n"
        self.assertEqual(self.findings(src, swift=True), ["3"])

    def test_swift_open(self):
        self.assertEqual(self.findings("open class C {}\n", swift=True), ["1"])
        self.assertEqual(self.findings("/// C.\nopen class C {\n    open func f() {}\n}\n", swift=True), ["3"])

    def test_swift_public_extension_members(self):
        src = "public extension Int {\n    var doubled: Int { self * 2 }\n    private func p() {}\n" \
              "    /// T.\n    static func t() -> Int {\n        let local = 1\n        return local\n    }\n}\n" \
              "extension Int {\n    func internalOnly() {}\n}\n"
        self.assertEqual(self.findings(src, swift=True), ["2"])

    def test_swift_internal_and_override_are_skipped(self):
        src = "/// C.\npublic class C: B {\n    func f() {}\n    public override func g() {}\n}\n"
        self.assertEqual(self.findings(src, swift=True), [])


if __name__ == "__main__":
    unittest.main()
