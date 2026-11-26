// SPDX-License-Identifier: Apache-2.0
package dev.gce.compiler;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GceTranspilerTest {
    private final GceTranspiler transpiler = new GceTranspiler();

    @Test void rewritesGceAndKeepsLegacyJava() {
        String output = transpiler.transpile("if (age (>= 18 && <= 65) && (salary > 5000)) {}");
        assertFalse(output.contains("Gce.test"));
        assertFalse(output.contains("Supplier"));
        assertFalse(output.contains("__gce_value"));
        assertTrue(output.contains("switch (0) { default -> { var $gce$0 = (age);"));
        assertFalse(output.contains("GceEval"));
        assertTrue(output.contains("($gce$0 >= (18))"));
        assertTrue(output.contains("($gce$0 <= (65))"));
        assertTrue(output.contains("(salary > 5000)"));
        assertTrue(output.indexOf("($gce$0 >= (18))") < output.indexOf("&&"));
    }

    @Test void supportsNestedAndOrWithJavaPrecedence() {
        String output = transpiler.transpile("if (score (>= 90 || (>= 70 && < 80))) {}");
        assertTrue(output.contains("($gce$0 >= (90)) ||"));
        assertTrue(output.contains("($gce$0 >= (70)) && ($gce$0 < (80))"));
    }

    @Test void andBindsTighterThanOr() {
        String output = transpiler.transpile("a (>= 1 || >= 2 && >= 3)");
        assertEquals(
                "(switch (0) { default -> { var $gce$0 = (a); yield "
                        + "(($gce$0 >= (1)) || (($gce$0 >= (2)) && ($gce$0 >= (3)))); } })",
                output);
    }

    @Test void supportsMethodChainFieldArrayAndCalls() {
        String output = transpiler.transpile(
                "if (person.getAddress().getHouseNumber() (!= null && >= 0)) {}");
        assertTrue(output.contains("switch (0) { default -> { var $gce$0 = (person.getAddress().getHouseNumber());"));
        assertTrue(output.contains("($gce$0 != (null))"));
        String fields = transpiler.transpile("person.address.house (>= 0)");
        assertTrue(fields.contains("switch (0) { default -> { var $gce$0 = (person.address.house);"));
        String call = transpiler.transpile("map.get(key) (>= 1)");
        assertTrue(call.contains("switch (0) { default -> { var $gce$0 = (map.get(key));"));
        String array = transpiler.transpile("arr[i + 1] (>= 0 && < arr.length)");
        assertTrue(array.contains("switch (0) { default -> { var $gce$0 = (arr[i + 1]);"));
        assertTrue(array.contains("($gce$0 < (arr.length))"));
    }

    @Test void parenthesesMakeABinaryExpressionTheSubject() {
        String output = transpiler.transpile("(low + high) (>= 0 && <= 10)");
        assertTrue(output.contains("switch (0) { default -> { var $gce$0 = ((low + high));"));
    }

    @Test void doesNotRewriteStringsCommentsOrOrdinaryComparisons() {
        String source = """
                // age (>= 18)
                String s = "age (>= 18 && <= 65)";
                char c = '(';
                /* person.getAge() (>= 1) */
                if (salary > 5000 && age >= 18) {
                    System.out.println(s);
                }
                """;
        assertSame(source, transpiler.transpile(source));
    }

    @Test void preservesUnrelatedSourceByteForByte() {
        String source = "int a = 1;\r\nString s = \"x\";\r\n";
        assertSame(source, transpiler.transpile(source));
    }

    @Test void avoidsGeneratedNameCollisions() {
        String output = transpiler.transpile("int $gce$0 = 100; boolean b = age (>= 18 && <= $gce$0);");
        assertTrue(output.contains("var $gce$1 = (age);"));
        assertTrue(output.contains("($gce$1 <= ($gce$0))"));
        assertFalse(output.contains("var $gce$0 = (age);"));
    }

    @Test void reportsMalformedGroupsWithLocation() {
        GceSyntaxException ex = assertThrows(GceSyntaxException.class,
                () -> transpiler.transpile("\n\nage (>= );"));
        assertEquals(3, ex.line());
        assertTrue(ex.detail().contains("operand"));
        assertThrows(GceSyntaxException.class, () -> transpiler.transpile("age (>= 18 &&)"));
        assertThrows(GceSyntaxException.class, () -> transpiler.transpile("age (>= 18 19)"));
        String instanceofGroup = transpiler.transpile("value (instanceof String)");
        assertTrue(instanceofGroup.contains("instanceof String"));
        assertTrue(assertThrows(GceSyntaxException.class, () -> transpiler.transpile("age (>= a = 1)"))
                .detail().contains("assignment"));
        String lambdaOperand = transpiler.transpile("age (>= (x -> x))");
        assertTrue(lambdaOperand.contains("x -> x"));
    }

    /** Direct calls to {@code Gce.test}. This does not exercise generated {@code GceEval} code. */
    @Test void helperEvaluatesSubjectOnce() {
        int[] calls = {0};
        boolean result = dev.gce.runtime.Gce.test(() -> { calls[0]++; return 12; },
                value -> value >= 10 && value <= 20);
        assertTrue(result);
        assertEquals(1, calls[0]);
    }

    /** Direct calls to {@code Gce.test}. Short-circuit of generated code is covered by end-to-end tests. */
    @Test void helperPreservesShortCircuit() {
        int[] calls = {0};
        boolean result = dev.gce.runtime.Gce.test(() -> 5,
                value -> value >= 10 && ++calls[0] > 0);
        assertFalse(result);
        assertEquals(0, calls[0]);
    }

    @Test void leavesOrdinaryIncrementsCastsAndQualifiedCreationUntouched() {
        String source = "int i = 0; ++i; i++; int n = (int) raw; holder.new Inner();";
        assertSame(source, transpiler.transpile(source));
    }

    @Test void acceptsPreviouslyRejectedExpressionForms() {
        assertTrue(transpiler.transpile("++f (>= 0)").contains("var $gce$0 = (++f);"));
        assertTrue(transpiler.transpile("f++ (>= 0)").contains("var $gce$0 = (f++);"));
        assertTrue(transpiler.transpile("n (>= ++f)").contains("++f"));
        assertTrue(transpiler.transpile("(int) x (>= 0)").contains("var $gce$0 = ((int) x);"));
        assertTrue(transpiler.transpile("n (>= (String) s)").contains("(String) s"));
        assertTrue(transpiler.transpile("holder.new Inner() (>= 0)").contains("holder.new Inner()"));
        assertTrue(transpiler.transpile("n (>= holder.new Inner())").contains("holder.new Inner()"));
        assertTrue(transpiler.transpile("items.stream().filter(x -> x (>= 0))").contains("x ->"));
        assertTrue(transpiler.transpile("items.stream().filter((Integer x) -> x (>= 0))").contains("(Integer x) ->"));
        assertTrue(transpiler.transpile("target (>= String::valueOf)").contains("String::valueOf"));
    }

    @Test void parenthesizedNullLiteralsUseAnObjectBinding() {
        assertTrue(transpiler.transpile("null (== null)").contains("Object $gce$0 = (null);"));
        assertTrue(transpiler.transpile("(null) (== null)").contains("Object $gce$0 = ((null));"));
        assertTrue(transpiler.transpile("((null)) (== null)").contains("Object $gce$0 = (((null)));"));
        assertTrue(transpiler.transpile("  ( null )  (== null)").contains("Object $gce$0"));
        assertTrue(transpiler.transpile("value (== null)").contains("var $gce$0 = (value);"));
        assertTrue(transpiler.transpile("value (!= null)").contains("var $gce$0 = (value);"));
    }

    @Test void unparenthesizedBinarySubjectsAreRejected() {
        for (String source : new String[] {
                "a + b (>= 0)", "a - b (>= 0)", "a * b (>= 0)", "a / b (>= 0)",
                "a % b (>= 0)", "a << b (>= 0)", "a >> b (>= 0)", "a >>> b (>= 0)",
                "a & b (>= 0)", "a | b (>= 0)", "a ^ b (>= 0)", "flag && a + b (>= 0)"
        }) {
            GceSyntaxException error = assertThrows(GceSyntaxException.class, () -> transpiler.transpile(source));
            assertTrue(error.detail().contains("Parenthesize"), source);
        }
        assertTrue(transpiler.transpile("(a + b) (>= 0)").contains("var $gce$0 = ((a + b));"));
        assertTrue(transpiler.transpile("flag && age (>= 18)").contains("var $gce$0 = (age);"));
        assertTrue(transpiler.transpile("ready ? a (>= 1) : b (>= 2)").contains("$gce$0"));
        assertTrue(transpiler.transpile("-n (>= 0)").contains("var $gce$0 = (-n);"));
        assertTrue(transpiler.transpile("!ready (== false)").contains("var $gce$0 = (!ready);"));
        assertTrue(transpiler.transpile("n (>= flags >> 1)").contains("flags >> 1"));
    }

    @Test void constantContextsAreRejectedBeforeCodeGeneration() {
        GceSyntaxException caseLabel = assertThrows(GceSyntaxException.class,
                () -> transpiler.transpile("switch (value) { case age (>= 18) ? 1 : 0: }"));
        assertTrue(caseLabel.detail().contains("case label"));
        GceSyntaxException annotation = assertThrows(GceSyntaxException.class,
                () -> transpiler.transpile("@Ann(age (>= 18)) class C {}"));
        assertTrue(annotation.detail().contains("annotation"));
        assertTrue(transpiler.transpile("int x = age (>= 18) ? 1 : 0;").contains("switch (0)"));
    }

    @Test void recordAndUnnamedPatternsAreCopiedIntoTheOperand() {
        assertTrue(transpiler.transpile("o (instanceof Point(int x, int y))")
                .contains("instanceof Point(int x, int y)"));
        assertTrue(transpiler.transpile("o (instanceof Box(Point(int x, int y)))")
                .contains("instanceof Box(Point(int x, int y))"));
        assertTrue(transpiler.transpile("o (instanceof String _)").contains("instanceof String _"));
        assertTrue(transpiler.transpile("o (instanceof final String s)").contains("instanceof final String s"));
    }

    @Test void commentsOutsideAGroupStayAndCommentsInsideTheSpanDoNot() {
        String output = transpiler.transpile("/*keep*/ age /*inside*/ (>= 18) // tail\n");
        assertTrue(output.contains("/*keep*/"));
        assertTrue(output.contains("// tail"));
        assertFalse(output.contains("inside"));
    }

    @Test void lambdaShapesAreTranslated() {
        assertTrue(transpiler.transpile("x -> x (>= 0)").contains("x ->"));
        assertTrue(transpiler.transpile("(x, y) -> x (>= y)").contains("(x, y) ->"));
        assertTrue(transpiler.transpile("(String x) -> x.length() (> 0)").contains("x.length()"));
        assertTrue(transpiler.transpile("x -> { return x (>= 0); }").contains("return"));
        assertTrue(transpiler.transpile("a -> b -> a (>= 0)").contains("a -> b ->"));
        assertTrue(transpiler.transpile("map(String::toUpperCase).filter(s -> s.length() (>= 2))")
                .contains("String::toUpperCase"));
    }

    @Test void excessiveNestingIsASyntaxError() {
        String groups = nestGroups(GceLimits.MAX_NESTING + 5);
        GceSyntaxException groupsError = assertThrows(GceSyntaxException.class, () -> transpiler.transpile(groups));
        assertTrue(groupsError.detail().contains("nesting"));
        String parens = "age (>= " + "(".repeat(GceLimits.MAX_NESTING + 5) + "1"
                + ")".repeat(GceLimits.MAX_NESTING + 5) + ")";
        GceSyntaxException parensError = assertThrows(GceSyntaxException.class, () -> transpiler.transpile(parens));
        assertTrue(parensError.detail().contains("nesting"));
    }

    private static String nestGroups(int depth) {
        String source = "1";
        for (int i = 0; i < depth; i++) {
            source = "age (>= (" + source + "))";
        }
        return source;
    }
}
