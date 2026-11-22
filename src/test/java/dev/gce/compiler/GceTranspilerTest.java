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
        assertTrue(output.contains("GceEval.of((age)).is($gce$0 ->"));
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
                "(dev.gce.runtime.GceEval.of((a)).is($gce$0 -> "
                        + "(($gce$0 >= (1)) || (($gce$0 >= (2)) && ($gce$0 >= (3))))))",
                output);
    }

    @Test void supportsMethodChainFieldArrayAndCalls() {
        String output = transpiler.transpile(
                "if (person.getAddress().getHouseNumber() (!= null && >= 0)) {}");
        assertTrue(output.contains("GceEval.of((person.getAddress().getHouseNumber())).is($gce$0 ->"));
        assertTrue(output.contains("($gce$0 != (null))"));
        String fields = transpiler.transpile("person.address.house (>= 0)");
        assertTrue(fields.contains("GceEval.of((person.address.house)).is($gce$0 ->"));
        String call = transpiler.transpile("map.get(key) (>= 1)");
        assertTrue(call.contains("GceEval.of((map.get(key))).is($gce$0 ->"));
        String array = transpiler.transpile("arr[i + 1] (>= 0 && < arr.length)");
        assertTrue(array.contains("GceEval.of((arr[i + 1])).is($gce$0 ->"));
        assertTrue(array.contains("($gce$0 < (arr.length))"));
    }

    @Test void parenthesesMakeABinaryExpressionTheSubject() {
        String output = transpiler.transpile("(low + high) (>= 0 && <= 10)");
        assertTrue(output.contains("GceEval.of(((low + high))).is($gce$0 ->"));
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
        assertTrue(output.contains("GceEval.of((age)).is($gce$1 ->"));
        assertTrue(output.contains("($gce$1 <= ($gce$0))"));
        assertFalse(output.contains("is($gce$0 ->"));
    }

    @Test void reportsMalformedGroupsWithLocation() {
        GceSyntaxException ex = assertThrows(GceSyntaxException.class,
                () -> transpiler.transpile("\n\nage (>= );"));
        assertEquals(3, ex.line());
        assertTrue(ex.detail().contains("operand"));
        assertThrows(GceSyntaxException.class, () -> transpiler.transpile("age (>= 18 &&)"));
        assertThrows(GceSyntaxException.class, () -> transpiler.transpile("age (>= 18 19)"));
        GceSyntaxException instanceofGroup = assertThrows(GceSyntaxException.class,
                () -> transpiler.transpile("value (instanceof String)"));
        assertTrue(instanceofGroup.detail().contains("0.2"));
        assertTrue(assertThrows(GceSyntaxException.class, () -> transpiler.transpile("age (>= a = 1)"))
                .detail().contains("assignment"));
        assertTrue(assertThrows(GceSyntaxException.class, () -> transpiler.transpile("age (>= (x -> x))"))
                .detail().contains("lambda"));
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

    @Test void rejectsUnsupportedSubjectsAndOperands() {
        assertTrue(assertThrows(GceSyntaxException.class, () -> transpiler.transpile("++f (>= 0)"))
                .detail().contains("prefix increment"));
        assertTrue(assertThrows(GceSyntaxException.class, () -> transpiler.transpile("--f (>= 0)"))
                .detail().contains("prefix decrement"));
        assertTrue(assertThrows(GceSyntaxException.class, () -> transpiler.transpile("f++ (>= 0)"))
                .detail().contains("postfix increment"));
        assertTrue(assertThrows(GceSyntaxException.class, () -> transpiler.transpile("f-- (>= 0)"))
                .detail().contains("postfix decrement"));
        assertTrue(assertThrows(GceSyntaxException.class, () -> transpiler.transpile("n (>= ++f)"))
                .detail().contains("prefix increment"));
        assertTrue(assertThrows(GceSyntaxException.class, () -> transpiler.transpile("n (>= f++)"))
                .detail().contains("postfix increment"));
        assertTrue(assertThrows(GceSyntaxException.class, () -> transpiler.transpile("(int) x (>= 0)"))
                .detail().contains("casts"));
        assertTrue(assertThrows(GceSyntaxException.class, () -> transpiler.transpile("n (>= (String) s)"))
                .detail().contains("casts"));
        assertTrue(assertThrows(GceSyntaxException.class, () -> transpiler.transpile("holder.new Inner() (>= 0)"))
                .detail().contains("qualified instance creation"));
        assertTrue(assertThrows(GceSyntaxException.class, () -> transpiler.transpile("n (>= holder.new Inner())"))
                .detail().contains("qualified instance creation"));
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
