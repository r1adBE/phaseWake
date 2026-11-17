#!/usr/bin/env python3
"""Selbsttest für `code_limits.py`: Musterfälle, die gemeldet bzw. nicht gemeldet werden müssen.

Aufruf: `python3 .github/scripts/test_code_limits.py` (läuft in der CI im Schritt «Regeln»).
"""
import sys
import tempfile
import unittest
from pathlib import Path

sys.dont_write_bytecode = True
sys.path.insert(0, str(Path(__file__).resolve().parent))
import code_limits  # noqa: E402

LONG = 80  # Körperzeilen: über 60, unter 120
VERY_LONG = 150  # Körperzeilen: über 120


def lines(text, count):
    return (text + "\n") * count


class CodeLimitsTest(unittest.TestCase):
    def run_check(self, source, swift=False):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / ("t.swift" if swift else "t.kt")
            path.write_text(source, encoding="utf-8")
            return code_limits.check(path, "t")

    def assertReported(self, source, swift=False, text=None):
        errors = self.run_check(source, swift)
        self.assertTrue(errors, "nicht gemeldet")
        if text:
            self.assertTrue(any(text in e for e in errors), errors)

    def assertClean(self, source, swift=False):
        self.assertEqual(self.run_check(source, swift), [])

    # Länge Kotlin

    def test_kotlin_block_body(self):
        self.assertReported("fun f() {\n" + lines("  x()", LONG) + "}\n", text="f hat 82 Zeilen")

    def test_kotlin_short_function(self):
        self.assertClean("fun f() {\n" + lines("  x()", 50) + "}\n")

    def test_kotlin_lambda_default_in_params(self):
        self.assertReported("fun f(a: Int, onClick: () -> Unit = {}) {\n" + lines("  x()", LONG) + "}\n",
                            text="f hat 82 Zeilen")

    def test_kotlin_expression_body_run(self):
        self.assertReported("fun f(a: Int) = run {\n" + lines("  x()", LONG) + "}\n", text="f hat 82 Zeilen")

    def test_kotlin_expression_body_call(self):
        src = "fun f(): Foo = Foo(\n" + lines("  a = 1,", LONG) + ")\n"
        self.assertReported(src, text="f hat 82 Zeilen")

    def test_kotlin_expression_body_chain(self):
        src = "fun f(): List<Int> =\n    listOf(1)\n" + lines("        .map { it }", LONG) + "\nfun g() = 1\n"
        self.assertReported(src, text="f hat 82 Zeilen")

    def test_kotlin_expression_ends_at_next_declaration(self):
        src = "fun f(a: Int): Int = a + 1\n" + "fun g() {\n" + lines("  x()", 50) + "}\n"
        self.assertClean(src)

    def test_kotlin_generic_return_with_expression(self):
        src = "fun f(): Map<String, Int> = mapOf(\n" + lines('  "a" to 1,', LONG) + ")\n"
        self.assertReported(src, text="f hat 82 Zeilen")

    def test_kotlin_abstract_function_has_no_body(self):
        src = "interface A {\n    fun f(): Int\n    fun g() {\n" + lines("  x()", 50) + "    }\n}\n"
        self.assertClean(src)

    def test_kotlin_init(self):
        self.assertReported("class A {\n init {\n" + lines("  x()", LONG) + " }\n}\n", text="init hat 82 Zeilen")

    def test_kotlin_getter(self):
        self.assertReported("val x: Int\n  get() {\n" + lines("  x()", LONG) + "  }\n", text="get hat 82 Zeilen")

    def test_kotlin_setter_call_is_no_accessor(self):
        self.assertClean("fun f() {\n    set(key, 1)\n}\n")

    def test_kotlin_composable_limit(self):
        self.assertClean("@Composable\nfun Screen() {\n" + lines("  Text()", LONG) + "}\n")
        self.assertClean("@Preview @Composable fun Screen() {\n" + lines("  Text()", LONG) + "}\n")
        self.assertReported("@Composable\nfun Screen() {\n" + lines("  Text()", VERY_LONG) + "}\n",
                            text="Screen hat 152 Zeilen (höchstens 120)")

    def test_kotlin_composable_parameter_type_is_no_ui(self):
        src = "fun f(content: @Composable () -> Unit) {\n" + lines("  x()", LONG) + "}\n"
        self.assertReported(src, text="(höchstens 60)")

    def test_kotlin_braces_in_strings_and_templates(self):
        src = 'fun f() {\n  val s = "{ ${if (a) "}" else "{"} {"\n  val c = \'{\'\n}\n' + lines("x()", LONG)
        self.assertClean(src)

    # Parameter Kotlin

    def test_kotlin_function_type_params(self):
        src = "fun f(a: (Int) -> Unit, b: Int, c: Int, d: Int, e: Int, f: Int, g: Int, h: Int) {\n}\n"
        self.assertReported(src, text="f hat 8 Parameter")

    def test_kotlin_seven_params_with_generics(self):
        src = "fun f(a: Map<String, Int>, b: () -> Unit, c: Int, d: Int, e: Int, f: Int = g(1, 2), h: Int) {\n}\n"
        self.assertClean(src)

    def test_kotlin_multiline_params_trailing_comma(self):
        params = "".join(f"    p{i}: () -> Unit = {{}},\n" for i in range(9))
        self.assertReported("@Composable\nfun Screen(\n" + params + ") {\n}\n", text="Screen hat 9 Parameter")

    def test_kotlin_comparison_in_default_is_no_generic(self):
        self.assertClean("fun f(a: Boolean = x < 3, b: Int, c: Int, d: Int, e: Int, f: Int, g: Int) {\n}\n")

    def test_kotlin_constructor_params_do_not_count(self):
        self.assertClean("class A(a: Int, b: Int, c: Int, d: Int, e: Int, f: Int, g: Int, h: Int) {\n"
                         "    constructor(a: Int, b: Int, c: Int, d: Int, e: Int, f: Int, g: Int, h: Int, i: Int)"
                         " : this(a, b, c, d, e, f, g, h)\n}\n")

    # Swift

    def test_swift_closure_params(self):
        src = "func f(a: @escaping (Int) -> Void, b: Int, c: Int, d: Int, e: Int, f: Int, g: Int, h: Int) {\n}\n"
        self.assertReported(src, swift=True, text="f hat 8 Parameter")

    def test_swift_init_params_do_not_count(self):
        self.assertClean("init(a: Int, b: Int, c: Int, d: Int, e: Int, f: Int, g: Int, h: Int) {\n}\n", swift=True)

    def test_swift_default_closure(self):
        src = "func f(a: Int, done: @escaping () -> Void = {}) {\n" + lines("  x()", LONG) + "}\n"
        self.assertReported(src, swift=True, text="f hat 82 Zeilen")

    def test_swift_where_clause(self):
        src = "func f<T>(a: T) -> Bool where T == Int {\n" + lines("  x()", LONG) + "}\n"
        self.assertReported(src, swift=True, text="f hat 82 Zeilen")

    def test_swift_body(self):
        src = "var body: some View {\n" + lines('  Text("")', VERY_LONG) + "}\n"
        self.assertReported(src, swift=True, text="body hat 152 Zeilen (höchstens 120)")
        self.assertClean("var body: some View {\n" + lines('  Text("")', LONG) + "}\n", swift=True)

    def test_swift_computed_view(self):
        src = "private var header: some View {\n" + lines('  Text("")', VERY_LONG) + "}\n"
        self.assertReported(src, swift=True, text="header hat 152 Zeilen")

    def test_swift_computed_value(self):
        src = "static var table: [String: Int] {\n" + lines("  x()", LONG) + "}\n"
        self.assertReported(src, swift=True, text="table hat 82 Zeilen (höchstens 60)")

    def test_swift_stored_closure_is_no_function(self):
        self.assertClean("let f: () -> Void = {\n" + lines("  x()", LONG) + "}\n", swift=True)

    def test_swift_view_builder_function(self):
        src = "@ViewBuilder\nfunc row() -> some View {\n" + lines('  Text("")', LONG) + "}\n"
        self.assertClean(src, swift=True)

    def test_swift_some_view_parameter_is_no_ui(self):
        src = "func f(content: some View) -> Int {\n" + lines("  x()", LONG) + "}\n"
        self.assertReported(src, swift=True, text="(höchstens 60)")

    def test_swift_raw_string(self):
        src = 'func f() {\n let s = #"a"{"#\n' + lines("  x()", LONG) + "}\n"
        self.assertReported(src, swift=True, text="f hat 83 Zeilen")

    def test_swift_interpolation_with_quotes(self):
        src = 'func f() {\n let s = "a \\(g("}")) b"\n}\nfunc g() {\n' + lines("  x()", 50) + "}\n"
        self.assertClean(src, swift=True)

    def test_swift_protocol_requirement_has_no_body(self):
        src = "protocol P {\n    func f() -> Int\n    func g()\n}\nfunc h() {\n" + lines("  x()", 50) + "}\n"
        self.assertClean(src, swift=True)

    # Zeilen

    def test_line_width(self):
        self.assertReported("val x = 1" + " " * 120 + "\n", text="Zeile 129 Zeichen")
        self.assertClean("val x = 1" + " " * 111 + "\n")


if __name__ == "__main__":
    unittest.main()
