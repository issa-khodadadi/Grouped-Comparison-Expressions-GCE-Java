// SPDX-License-Identifier: Apache-2.0
package dev.gce.compiler;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Independent comparison groups in one condition, including an {@code if} or {@code while}
 * whose opening parenthesis is the first subject's parenthesis.
 */
class MultiGroupConditionTest {
    private final GceTranspiler transpiler = new GceTranspiler();

    @Test void oneVariableOneComparisonCompilesAndRuns() {
        String source = """
                public class OneComparison {
                    public static void main(String[] args) {
                        int a = 4;
                        if (a)(== 4) System.out.println("one");
                        System.out.println((a)(== 4));
                        System.out.println((a)(!= 4));
                    }
                }
                """;
        String translated = transpiler.transpile(source);
        assertTrue(translated.contains("if (switch (0)"));
        assertFalse(translated.contains("if ((switch (0)"));
        assertEquals("one\ntrue\nfalse\n", run(translated, "OneComparison"));
    }

    @Test void oneVariableSeveralComparisonsCompilesAndRuns() {
        String source = """
                public class SeveralComparisons {
                    public static void main(String[] args) {
                        int a = 9;
                        if (a)(>= 1 && <= 10 && != 5) System.out.println("in");
                        a = 5;
                        if (a)(>= 1 && <= 10 && != 5) System.out.println("out");
                        a = 0;
                        if (a)(>= 1 && <= 10 && != 5) System.out.println("out");
                        System.out.println("done");
                    }
                }
                """;
        assertEquals("in\ndone\n", run(transpiler.transpile(source), "SeveralComparisons"));
    }

    @Test void twoVariablesWithDifferentComparisonCounts() {
        String source = """
                public class TwoGroups {
                    public static void main(String[] args) {
                        int a = 9;
                        int b = 3;
                        if (a)(>= 1 && <= 10 && != 5)
                                && (b)(== 3) System.out.println("yes");
                        b = 4;
                        if (a)(>= 1 && <= 10 && != 5)
                                && (b)(== 3) System.out.println("no");
                        System.out.println("done");
                    }
                }
                """;
        String translated = transpiler.transpile(source);
        assertTrue(headerContains(translated, "System.out.println(\"yes\")", "$gce$0"));
        assertTrue(headerContains(translated, "System.out.println(\"yes\")", "$gce$1"));
        assertEquals("yes\ndone\n", run(translated, "TwoGroups"));
    }

    @Test void threeVariablesWithThreeOneAndFourComparisons() {
        String source = """
                public class ThreeGroups {
                    public static void main(String[] args) {
                        int a = 9;
                        int b = 3;
                        int c = 52;

                        if (a)(>= 1 && <= 10 && != 5)
                                && (b)(== 3)
                                && (c)(> 0 && < 100 && != 50 && != 75) {
                            System.out.println("here");
                        }

                        System.out.println("GCE compilation test successful!");
                    }
                }
                """;
        String translated = transpiler.transpile(source);
        String header = translated.substring(0, translated.indexOf("System.out.println(\"here\")"));
        assertEquals(3, count(header, "switch (0)"));
        assertTrue(header.contains("$gce$0"));
        assertTrue(header.contains("$gce$1"));
        assertTrue(header.contains("$gce$2"));
        assertTrue(header.contains("if ((switch (0)"));
        assertEquals("here\nGCE compilation test successful!\n", run(translated, "ThreeGroups"));
    }

    @Test void groupsJoinedByAndShortCircuit() {
        String source = """
                public class AndChain {
                    static int calls;
                    static int tick(int value) { calls++; return value; }
                    public static void main(String[] args) {
                        int a = 9;
                        int b = 4;
                        if (a)(>= 1 && <= 10) && (b)(== 3) System.out.println("no");
                        if (tick(1))(>= 2) && (tick(3))(== 3) System.out.println("no");
                        System.out.println(calls);
                        System.out.println("anded");
                    }
                }
                """;
        assertEquals("1\nanded\n", run(transpiler.transpile(source), "AndChain"));
    }

    @Test void groupsJoinedByOrShortCircuit() {
        String source = """
                public class OrChain {
                    static int calls;
                    static int tick(int value) { calls++; return value; }
                    public static void main(String[] args) {
                        int a = 0;
                        int b = 3;
                        if (a)(>= 1) || (b)(== 3) System.out.println("or");
                        if (a)(>= 1) || (b)(== 4) System.out.println("no");
                        if (tick(9))(>= 1) || (tick(3))(== 3) System.out.println("skip");
                        System.out.println(calls);
                        int n = 0;
                        int left = 2;
                        while (left)(> 0) && (b)(== 3) { n++; left--; }
                        System.out.println(n);
                    }
                }
                """;
        String translated = transpiler.transpile(source);
        assertTrue(translated.contains("|| (switch (0)"));
        assertTrue(translated.contains("while ((switch (0)"));
        assertEquals("or\nskip\n1\n2\n", run(translated, "OrChain"));
    }

    @Test void mixedAndOrKeepJavaPrecedence() {
        String source = """
                public class MixedPrecedence {
                    public static void main(String[] args) {
                        int a = 0;
                        int b = 0;
                        int c = 5;
                        if (a)(>= 1) || (b)(== 0) && (c)(> 0) System.out.println("or-and");
                        b = 1;
                        if (a)(>= 1) || (b)(== 0) && (c)(> 0) System.out.println("no");
                        System.out.println("done");
                    }
                }
                """;
        String translated = transpiler.transpile("if (a)(>= 1) || (b)(== 2) && (c)(> 0) {}");
        assertEquals(
                "if ((switch (0) { default -> { var $gce$0 = ((a)); yield ($gce$0 >= (1)); } }) "
                        + "|| (switch (0) { default -> { var $gce$1 = ((b)); yield ($gce$1 == (2)); } }) "
                        + "&& (switch (0) { default -> { var $gce$2 = ((c)); yield ($gce$2 > (0)); } })) {}",
                translated);
        assertEquals("or-and\ndone\n", run(transpiler.transpile(source), "MixedPrecedence"));
    }

    @Test void parenthesesOverridePrecedence() {
        String translatedShape = transpiler.transpile("if (a)(>= 1) && ((b)(== 2) || (c)(> 0)) {}");
        assertEquals(
                "if ((switch (0) { default -> { var $gce$0 = ((a)); yield ($gce$0 >= (1)); } }) "
                        + "&& ((switch (0) { default -> { var $gce$1 = ((b)); yield ($gce$1 == (2)); } }) "
                        + "|| (switch (0) { default -> { var $gce$2 = ((c)); yield ($gce$2 > (0)); } }))) {}",
                translatedShape);
        String source = """
                public class ParenPrecedence {
                    public static void main(String[] args) {
                        int a = 0;
                        int b = 1;
                        int c = 5;
                        if (a)(>= 1) && (b)(== 0) || (c)(> 0) System.out.println("or-wins");
                        if (a)(>= 1) && ((b)(== 0) || (c)(> 0)) System.out.println("and-wins");
                        if (((a)(>= 1) || (b)(== 0)) && (c)(> 0)) System.out.println("wrapped");
                        a = 9;
                        if ((a)(>= 1) || ((b)(== 0) && (c)(> 0))) System.out.println("inner");
                        System.out.println("done");
                    }
                }
                """;
        assertEquals("or-wins\ninner\ndone\n", run(transpiler.transpile(source), "ParenPrecedence"));
    }

    @Test void sideEffectingSubjectsRunOncePerGroup() {
        String source = """
                public class SideOnce {
                    static int calls;
                    static int tick(int value) { calls++; return value; }
                    public static void main(String[] args) {
                        if (tick(9))(>= 1 && <= 10 && != 5)
                                && (tick(3))(== 3)
                                && (tick(52))(> 0 && < 100 && != 50 && != 75) {
                            System.out.println("here");
                        }
                        System.out.println(calls);
                        calls = 0;
                        if (tick(5))(>= 1 && <= 10 && != 5) && (tick(3))(== 3) {
                            System.out.println("no");
                        }
                        System.out.println(calls);
                        calls = 0;
                        if (tick(9))(>= 1) || (tick(3))(== 3) System.out.println("or");
                        System.out.println(calls);
                    }
                }
                """;
        assertEquals("here\n3\n1\nor\n1\n", run(transpiler.transpile(source), "SideOnce"));
    }

    @Test void existingGroupedFormStaysCompatible() {
        String source = """
                public class ClassicGce {
                    public static void main(String[] args) {
                        int age = 25;
                        int salary = 8000;
                        if (age (>= 18 && <= 65) && (salary > 5000)) {
                            System.out.println("Accepted");
                        }
                        for (int n = 0; (age)(>= 18) && (salary)(> 5000) && n < 1; n++) {
                            System.out.println("loop");
                        }
                    }
                }
                """;
        String translated = transpiler.transpile(source);
        assertTrue(translated.contains("(salary > 5000)"));
        assertTrue(translated.contains("var $gce$0 = (age);"));
        assertFalse(translated.contains("Gce.test"));
        assertEquals("Accepted\nloop\n", run(translated, "ClassicGce"));
    }

    @Test void headerChainKeepsACommentBetweenGroups() {
        String output = transpiler.transpile("if (a)(>= 1) /*keep*/ && (b)(== 2) {}");
        assertTrue(output.contains("/*keep*/"));
        assertTrue(output.contains("if ((switch (0)"));
    }

    private static boolean headerContains(String translated, String bodyMarker, String piece) {
        return translated.substring(0, translated.indexOf(bodyMarker)).contains(piece);
    }

    private static String run(String translated, String className) {
        assertFalse(translated.contains("GceEval"));
        return normalize(JavaRunner.compileAndRun(className, translated));
    }

    private static String normalize(String text) {
        return text.replace("\r\n", "\n");
    }

    private static int count(String text, String piece) {
        int found = 0;
        int from = 0;
        while (true) {
            int at = text.indexOf(piece, from);
            if (at < 0) {
                return found;
            }
            found++;
            from = at + piece.length();
        }
    }
}
