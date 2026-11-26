// SPDX-License-Identifier: Apache-2.0
package dev.gce.compiler;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GceEndToEndTest {
    private final GceTranspiler transpiler = new GceTranspiler();

    @Test void translatedProgramMatchesSingleEvaluationJava() {
        String translated = transpiler.transpile(gceProgram());
        assertFalse(translated.contains("Gce.test"));
        assertFalse(translated.contains("Supplier"));
        assertFalse(translated.contains("__gce_value"));
        assertTrue(translated.contains("switch (0)"));
        assertFalse(translated.contains("GceEval"));
        assertTrue(translated.contains("\"age (>= 18)\""));
        assertTrue(translated.contains("// age (>= 99)"));
        assertTrue(translated.contains("/* salary (>= 1) */"));

        String expected = """
                accepted
                true
                1
                false
                1
                true
                true
                true
                true
                false
                true
                true
                true
                true
                npe
                true
                true
                true
                false
                true
                false
                true
                false
                true
                true
                1
                true
                true
                true
                false
                ABCtrue
                false
                Dtrue
                Ffalse
                true
                true
                true
                true
                true
                false
                true
                age (>= 18)
                done
                """;
        assertEquals(normalize(expected), normalize(JavaRunner.compileAndRun("GceBehavior", translated)));
        assertEquals(normalize(expected), normalize(JavaRunner.compileAndRun("JavaBehavior", javaProgram())));
    }

    @Test void textBlockSurvivesAndRealGroupStillRuns() {
        String source = """
                public class TextBlockGce {
                    public static void main(String[] args) {
                        String block = \"\"\"
                                age (>= 18)
                                \"\"\";
                        int age = 20;
                        System.out.println(block.contains("age (>= 18)") && age (>= 18));
                    }
                }
                """;
        String translated = transpiler.transpile(source);
        assertTrue(translated.contains("age (>= 18)"));
        assertEquals("true\n", normalize(JavaRunner.compileAndRun("TextBlockGce", translated)));
    }

    private static String normalize(String text) {
        return text.replace("\r\n", "\n");
    }

    @Test void primitiveComparedWithNullStaysIllegal() {
        String translated = transpiler.transpile("class BadCompare { boolean m(int x) { return x (== null); } }");
        JavaRunner.assertDoesNotCompile("BadCompare", translated);
    }

    @Test void nestedGroupInsideAnOperandKeepsBothSubjectsSingle() {
        String source = """
                public class NestedGce {
                    static int calls;
                    static int next() { return ++calls; }
                    public static void main(String[] args) {
                        boolean flag = true;
                        System.out.println(flag (== (next() (>= 1 && <= 1))));
                        System.out.println(calls);
                    }
                }
                """;
        assertEquals("true\n1\n", normalize(JavaRunner.compileAndRun("NestedGce", transpiler.transpile(source))));
    }

    @Test void reassignedLocalMatchesJava() {
        String gce = """
                public class C {
                    public static void main(String[] args) {
                        int age = 25;
                        boolean first = age (>= 18 && <= 65);
                        age = 10;
                        boolean second = age (>= 18 && <= 65);
                        System.out.print(first);
                        System.out.print(second);
                    }
                }
                """;
        String java = """
                public class J {
                    public static void main(String[] args) {
                        int age = 25;
                        int firstValue = age;
                        boolean first = firstValue >= 18 && firstValue <= 65;
                        age = 10;
                        int secondValue = age;
                        boolean second = secondValue >= 18 && secondValue <= 65;
                        System.out.print(first);
                        System.out.print(second);
                    }
                }
                """;
        assertSameOutput(gce, java);
    }

    @Test void forLoopIndexMatchesJava() {
        String gce = """
                public class C {
                    public static void main(String[] args) {
                        int sum = 0;
                        for (int i = 0; i < 10; i++) if (i (>= 3 && <= 5)) sum++;
                        System.out.print(sum);
                    }
                }
                """;
        String java = """
                public class J {
                    public static void main(String[] args) {
                        int sum = 0;
                        for (int i = 0; i < 10; i++) {
                            int value = i;
                            if (value >= 3 && value <= 5) sum++;
                        }
                        System.out.print(sum);
                    }
                }
                """;
        assertSameOutput(gce, java);
    }

    @Test void arrayIndexInLoopMatchesJava() {
        String gce = """
                public class C {
                    public static void main(String[] args) {
                        int[] values = {1, 2, 3, 2};
                        StringBuilder found = new StringBuilder();
                        for (int i = 0; i < values.length; i++) if (values[i] (>= 2 && <= 2)) found.append(i);
                        System.out.print(found);
                    }
                }
                """;
        String java = """
                public class J {
                    public static void main(String[] args) {
                        int[] values = {1, 2, 3, 2};
                        StringBuilder found = new StringBuilder();
                        for (int i = 0; i < values.length; i++) {
                            int value = values[i];
                            if (value >= 2 && value <= 2) found.append(i);
                        }
                        System.out.print(found);
                    }
                }
                """;
        assertSameOutput(gce, java);
    }

    @Test void checkedExceptionFromSubjectPropagates() {
        String gce = """
                public class C {
                    static int read() throws Exception { return 4; }
                    public static void main(String[] args) throws Exception {
                        System.out.print(read() (>= 0 && <= 10));
                    }
                }
                """;
        String java = """
                public class J {
                    static int read() throws Exception { return 4; }
                    public static void main(String[] args) throws Exception {
                        int value = read();
                        System.out.print(value >= 0 && value <= 10);
                    }
                }
                """;
        assertSameOutput(gce, java);
    }

    @Test void checkedExceptionInOperandPropagates() {
        String gce = """
                public class C {
                    static int read() throws Exception { return 1; }
                    public static void main(String[] args) throws Exception {
                        int n = 4;
                        System.out.print(n (>= 0 && <= read()));
                    }
                }
                """;
        String java = """
                public class J {
                    static int read() throws Exception { return 1; }
                    public static void main(String[] args) throws Exception {
                        int n = 4;
                        int value = n;
                        System.out.print(value >= 0 && value <= read());
                    }
                }
                """;
        assertSameOutput(gce, java);
    }

    @Test void operandThatUsesAReassignedLocalMatchesJava() {
        String gce = """
                public class C {
                    public static void main(String[] args) {
                        int i = 1;
                        int j = 0;
                        boolean result = i (>= 0 && <= j);
                        j = 2;
                        System.out.print(result);
                        System.out.print(j);
                    }
                }
                """;
        String java = """
                public class J {
                    public static void main(String[] args) {
                        int i = 1;
                        int j = 0;
                        int value = i;
                        boolean result = value >= 0 && value <= j;
                        j = 2;
                        System.out.print(result);
                        System.out.print(j);
                    }
                }
                """;
        assertSameOutput(gce, java);
    }

    @Test void castIncrementAndQualifiedCreationMatchJava() {
        String gce = """
                public class C {
                    static class Box {
                        class Inner {}
                    }
                    public static void main(String[] args) {
                        Object raw = Integer.valueOf(4);
                        System.out.print(((Integer) raw) (>= 4));
                        int i = 1;
                        System.out.print(++i (>= 2));
                        System.out.print(i);
                        int j = 1;
                        System.out.print(j++ (>= 1));
                        System.out.print(j);
                        Box box = new Box();
                        System.out.print(box.new Inner() (!= null));
                    }
                }
                """;
        String java = """
                public class J {
                    static class Box {
                        class Inner {}
                    }
                    public static void main(String[] args) {
                        Object raw = Integer.valueOf(4);
                        Integer cast = (Integer) raw;
                        System.out.print(cast >= 4);
                        int i = 1;
                        int incremented = ++i;
                        System.out.print(incremented >= 2);
                        System.out.print(i);
                        int j = 1;
                        int posted = j++;
                        System.out.print(posted >= 1);
                        System.out.print(j);
                        Box box = new Box();
                        Box.Inner created = box.new Inner();
                        System.out.print(created != null);
                    }
                }
                """;
        assertSameOutput(gce, java);
    }

    @Test void instanceofGroupMatchesJava() {
        String gce = """
                public class C {
                    public static void main(String[] args) {
                        Object value = "hi";
                        System.out.print(value (instanceof String));
                        System.out.print(value (instanceof String s && != null));
                        System.out.print(value (instanceof Integer));
                    }
                }
                """;
        String java = """
                public class J {
                    public static void main(String[] args) {
                        Object value = "hi";
                        Object first = value;
                        System.out.print(first instanceof String);
                        Object second = value;
                        System.out.print(second instanceof String s && second != null);
                        Object third = value;
                        System.out.print(third instanceof Integer);
                    }
                }
                """;
        assertSameOutput(gce, java);
    }

    @Test void lambdaBodiesCanContainGroups() {
        String gce = """
                public class C {
                    public static void main(String[] args) {
                        int[] values = {-1, 2, 3};
                        long count = java.util.Arrays.stream(values).filter(x -> x (>= 0)).count();
                        long blocks = java.util.Arrays.stream(values).filter(x -> { return x (>= 2); }).count();
                        long typed = java.util.Arrays.stream(values).boxed()
                                .filter((Integer x) -> x (>= 2)).count();
                        System.out.print(count);
                        System.out.print(blocks);
                        System.out.print(typed);
                    }
                }
                """;
        String java = """
                public class J {
                    public static void main(String[] args) {
                        int[] values = {-1, 2, 3};
                        long count = java.util.Arrays.stream(values).filter(x -> x >= 0).count();
                        long blocks = java.util.Arrays.stream(values).filter(x -> { return x >= 2; }).count();
                        long typed = java.util.Arrays.stream(values).boxed()
                                .filter((Integer x) -> x >= 2).count();
                        System.out.print(count);
                        System.out.print(blocks);
                        System.out.print(typed);
                    }
                }
                """;
        assertSameOutput(gce, java);
    }

    @Test void parenthesizedNullSubjectsMatchJava() {
        String gce = """
                public class C {
                    public static void main(String[] args) {
                        System.out.print(null (== null));
                        System.out.print((null) (== null));
                        System.out.print(((null)) (== null));
                        String value = null;
                        System.out.print(value (== null));
                        System.out.print(value (!= null));
                        String other = "x";
                        System.out.print(other (!= null));
                    }
                }
                """;
        String java = """
                public class J {
                    public static void main(String[] args) {
                        Object bare = null;
                        System.out.print(bare == null);
                        Object one = null;
                        System.out.print(one == null);
                        Object two = null;
                        System.out.print(two == null);
                        String value = null;
                        System.out.print(value == null);
                        System.out.print(value != null);
                        String other = "x";
                        System.out.print(other != null);
                    }
                }
                """;
        assertSameOutput(gce, java);
    }

    @Test void parenthesizedArithmeticSubjectIsEvaluatedOnce() {
        String gce = """
                public class C {
                    static int calls;
                    static int tick(int value) { calls++; return value; }
                    public static void main(String[] args) {
                        System.out.print((tick(2) + tick(3)) (>= 5 && <= 5));
                        System.out.print(calls);
                        System.out.print(obj().field (>= 1));
                        System.out.print(calls);
                        int[] items = {4};
                        System.out.print(items[tick(0)] (>= 4));
                        System.out.print(calls);
                    }
                    static Holder obj() { calls++; return new Holder(); }
                    static class Holder { int field = 1; }
                }
                """;
        String java = """
                public class J {
                    static int calls;
                    static int tick(int value) { calls++; return value; }
                    public static void main(String[] args) {
                        int sum = tick(2) + tick(3);
                        System.out.print(sum >= 5 && sum <= 5);
                        System.out.print(calls);
                        Holder holder = obj();
                        System.out.print(holder.field >= 1);
                        System.out.print(calls);
                        int[] items = {4};
                        int item = items[tick(0)];
                        System.out.print(item >= 4);
                        System.out.print(calls);
                    }
                    static Holder obj() { calls++; return new Holder(); }
                    static class Holder { int field = 1; }
                }
                """;
        assertSameOutput(gce, java);
    }

    @Test void lambdasAndMethodReferencesMatchJava() {
        String gce = """
                public class C {
                    public static void main(String[] args) {
                        java.util.function.Predicate<Integer> nonNegative = x -> x (>= 0);
                        java.util.function.BiPredicate<Integer, Integer> atLeast = (x, y) -> x (>= y);
                        java.util.function.Predicate<String> nonempty = (String x) -> x.length() (> 0);
                        java.util.function.Predicate<Integer> block = x -> { return x (>= 0); };
                        java.util.function.Function<Integer, java.util.function.Predicate<Integer>> nested =
                                a -> b -> a (>= 0);
                        System.out.print(nonNegative.test(-1));
                        System.out.print(nonNegative.test(2));
                        System.out.print(atLeast.test(2, 1));
                        System.out.print(atLeast.test(1, 2));
                        System.out.print(nonempty.test("ab"));
                        System.out.print(block.test(0));
                        System.out.print(nested.apply(3).test(1));
                        System.out.print(java.util.List.of("bb", "c").stream()
                                .map(String::toUpperCase)
                                .filter(s -> s.length() (>= 2))
                                .findFirst().orElse(""));
                    }
                }
                """;
        String java = """
                public class J {
                    public static void main(String[] args) {
                        java.util.function.Predicate<Integer> nonNegative = x -> x >= 0;
                        java.util.function.BiPredicate<Integer, Integer> atLeast = (x, y) -> x >= y;
                        java.util.function.Predicate<String> nonempty = (String x) -> x.length() > 0;
                        java.util.function.Predicate<Integer> block = x -> { return x >= 0; };
                        java.util.function.Function<Integer, java.util.function.Predicate<Integer>> nested =
                                a -> b -> a >= 0;
                        System.out.print(nonNegative.test(-1));
                        System.out.print(nonNegative.test(2));
                        System.out.print(atLeast.test(2, 1));
                        System.out.print(atLeast.test(1, 2));
                        System.out.print(nonempty.test("ab"));
                        System.out.print(block.test(0));
                        System.out.print(nested.apply(3).test(1));
                        System.out.print(java.util.List.of("bb", "c").stream()
                                .map(String::toUpperCase)
                                .filter(s -> s.length() >= 2)
                                .findFirst().orElse(""));
                    }
                }
                """;
        assertSameOutput(gce, java);
    }

    @Test void recordPatternsMatchJava() {
        String gce = """
                public class C {
                    record Point(int x, int y) {}
                    record Box(Point p) {}
                    public static void main(String[] args) {
                        Object point = new Point(1, 2);
                        Object text = "hi";
                        Object box = new Box(new Point(3, 4));
                        System.out.print(point (instanceof Point(int x, int y)));
                        System.out.print(point (instanceof String _));
                        System.out.print(text (instanceof String s));
                        System.out.print(box (instanceof Box(Point(int x, int y))));
                        System.out.print(point (instanceof final Point p));
                    }
                }
                """;
        String java = """
                public class J {
                    record Point(int x, int y) {}
                    record Box(Point p) {}
                    public static void main(String[] args) {
                        Object point = new Point(1, 2);
                        Object text = "hi";
                        Object box = new Box(new Point(3, 4));
                        System.out.print(point instanceof Point(int x, int y));
                        System.out.print(point instanceof String _);
                        System.out.print(text instanceof String s);
                        System.out.print(box instanceof Box(Point(int x, int y)));
                        System.out.print(point instanceof final Point p);
                    }
                }
                """;
        assertSameOutput(gce, java);
    }

    @Test void nullLiteralSubjectMatchesJava() {
        String gce = """
                public class C {
                    public static void main(String[] args) {
                        System.out.print(null (== null));
                        String s = null;
                        System.out.print(s (== null));
                    }
                }
                """;
        String java = """
                public class J {
                    public static void main(String[] args) {
                        Object value = null;
                        System.out.print(value == null);
                        String s = null;
                        System.out.print(s == null);
                    }
                }
                """;
        assertSameOutput(gce, java);
    }

    @Test void longNearTheDoubleBoundaryStaysIntegral() {
        String gce = """
                public class C {
                    public static void main(String[] args) {
                        long big = 9007199254740993L;
                        System.out.print(big (== big));
                        System.out.print(big (== big + 1));
                    }
                }
                """;
        String java = """
                public class J {
                    public static void main(String[] args) {
                        long big = 9007199254740993L;
                        long value = big;
                        System.out.print(value == big);
                        System.out.print(value == big + 1);
                    }
                }
                """;
        assertSameOutput(gce, java);
    }

    @Test void groupInsideMethodArgumentOfASubjectRunsOnce() {
        String gce = """
                public class C {
                    static int calls;
                    static int age() { calls++; return 25; }
                    static int id(boolean value) { calls += 10; return value ? 1 : 0; }
                    public static void main(String[] args) {
                        System.out.print(id(age() (>= 18 && <= 65)) (>= 1));
                        System.out.print(calls);
                        calls = 0;
                        System.out.print(false && id(age() (>= 18)) (>= 0));
                        System.out.print(calls);
                    }
                }
                """;
        String java = """
                public class J {
                    static int calls;
                    static int age() { calls++; return 25; }
                    static int id(boolean value) { calls += 10; return value ? 1 : 0; }
                    public static void main(String[] args) {
                        int ageValue = age();
                        boolean inner = ageValue >= 18 && ageValue <= 65;
                        int outer = id(inner);
                        System.out.print(outer >= 1);
                        System.out.print(calls);
                        calls = 0;
                        System.out.print(false && id(age() >= 18) >= 0);
                        System.out.print(calls);
                    }
                }
                """;
        assertSameOutput(gce, java);
    }

    @Test void groupInsideParenthesizedSubjectRunsOnce() {
        String gce = """
                public class C {
                    static int calls;
                    static int age() { calls++; return 25; }
                    public static void main(String[] args) {
                        System.out.print((age() (>= 18 && <= 65) ? 1 : 0) (>= 1));
                        System.out.print(calls);
                    }
                }
                """;
        String java = """
                public class J {
                    static int calls;
                    static int age() { calls++; return 25; }
                    public static void main(String[] args) {
                        int ageValue = age();
                        int selected = (ageValue >= 18 && ageValue <= 65) ? 1 : 0;
                        System.out.print(selected >= 1);
                        System.out.print(calls);
                    }
                }
                """;
        assertSameOutput(gce, java);
    }

    @Test void groupInsideArrayIndexOfASubjectRunsOnce() {
        String gce = """
                public class C {
                    static int calls;
                    static int age() { calls++; return 25; }
                    public static void main(String[] args) {
                        int[] values = {5};
                        System.out.print(values[age() (>= 18) ? 0 : 0] (>= 1));
                        System.out.print(calls);
                    }
                }
                """;
        String java = """
                public class J {
                    static int calls;
                    static int age() { calls++; return 25; }
                    public static void main(String[] args) {
                        int[] values = {5};
                        int ageValue = age();
                        int selected = values[ageValue >= 18 ? 0 : 0];
                        System.out.print(selected >= 1);
                        System.out.print(calls);
                    }
                }
                """;
        assertSameOutput(gce, java);
    }

    @Test void ordinaryJavaInsideASubjectArgumentIsKept() {
        String gce = """
                public class C {
                    static int id(int value) { return value; }
                    static class Box {
                        class Inner { int v() { return 4; } }
                    }
                    public static void main(String[] args) {
                        int i = 1;
                        System.out.print(id(++i) (>= 2));
                        System.out.print(i);
                        Box box = new Box();
                        System.out.print(id(box.new Inner().v()) (>= 4));
                    }
                }
                """;
        String java = """
                public class J {
                    static int id(int value) { return value; }
                    static class Box {
                        class Inner { int v() { return 4; } }
                    }
                    public static void main(String[] args) {
                        int i = 1;
                        int incremented = id(++i);
                        System.out.print(incremented >= 2);
                        System.out.print(i);
                        Box box = new Box();
                        int created = id(box.new Inner().v());
                        System.out.print(created >= 4);
                    }
                }
                """;
        assertSameOutput(gce, java);
    }

    private void assertSameOutput(String gce, String java) {
        assertEquals(
                normalize(JavaRunner.compileAndRun("J", java)),
                normalize(JavaRunner.compileAndRun("C", transpiler.transpile(gce))));
    }

    private static String gceProgram() {
        return """
                public class GceBehavior {
                    static int calls;
                    static int lists;
                    static int next(int value) { calls++; return value; }
                    static int boom() { throw new RuntimeException("rhs"); }
                    static int side(String name, int value) { System.out.print(name); return value; }
                    static java.util.ArrayList<java.util.List<String>> freshList() {
                        lists++;
                        return new java.util.ArrayList<>();
                    }

                    enum Color { RED, BLUE }

                    static class Address {
                        final Integer house;
                        Address(Integer house) { this.house = house; }
                        Integer house() { return house; }
                    }

                    static class Person {
                        final int age;
                        final Address address;
                        Person(int age, Address address) { this.age = age; this.address = address; }
                        int age() { return age; }
                        Address address() { return address; }
                    }

                    public static void main(String[] args) {
                        int age = 25;
                        int salary = 8000;
                        if (age (>= 18 && <= 65) && (salary > 5000)) System.out.println("accepted");

                        calls = 0;
                        System.out.println(next(5) (>= 1 && <= 5));
                        System.out.println(calls);
                        calls = 0;
                        System.out.println(next(0) (>= 1 && <= boom()));
                        System.out.println(calls);

                        String s = "hi";
                        System.out.println(s (== "hi"));
                        System.out.println(s (!= "no"));
                        Color color = Color.RED;
                        System.out.println(color (== Color.RED));
                        System.out.println(color (!= Color.BLUE));

                        Integer boxed = 1000;
                        Integer boxed2 = 1000;
                        System.out.println(boxed (== boxed2));
                        System.out.println(boxed (== 1000));
                        int primitive = 1000;
                        Integer wrapped = 1000;
                        System.out.println(primitive (== wrapped));
                        System.out.println(wrapped (== primitive));
                        Integer nil = null;
                        System.out.println(nil (== null));
                        try {
                            boolean ignored = nil (>= 0);
                            System.out.println("no-npe");
                        } catch (NullPointerException ex) {
                            System.out.println("npe");
                        }

                        double d = -1.5;
                        System.out.println(d (>= -2.0 && < 0.0));
                        int neg = -3;
                        System.out.println(neg (>= -5 && <= -1));
                        int five = 5;
                        System.out.println(five (>= 0 || >= 100 && >= 6));
                        System.out.println(five ((>= 0 || >= 100) && >= 6));
                        int thirtyTwo = 32;
                        System.out.println(thirtyTwo (< 18 || >= 30 && < 40));
                        int ten = 10;
                        System.out.println(ten (>= 18 || >= 30 && < 40));

                        Person person = new Person(25, new Address(4));
                        System.out.println(person.address().house() (!= null && >= 0));
                        Person empty = new Person(25, new Address(null));
                        System.out.println(empty.address().house() (!= null && >= 0));
                        int[] arr = {1, 2, 3};
                        System.out.println(arr[1] (>= 2 && <= 2));
                        System.out.println(freshList().size() (>= 0 && <= 0));
                        System.out.println(lists);

                        char ch = 'b';
                        System.out.println(ch (>= 'a' && <= 'z'));
                        boolean flag = true;
                        System.out.println(flag (== true && != false));
                        int $gce$0 = 100;
                        System.out.println(age (>= 18 && <= $gce$0));
                        System.out.println(age (>= $gce$0));

                        System.out.println(side("A", 5) (>= side("B", 1) && <= side("C", 9)));
                        System.out.println(false && side("Z", 1) (>= 0));
                        System.out.println(side("D", 5) (>= 0 || <= side("E", 0)));
                        System.out.println(side("F", 0) (>= 1) && side("G", 1) (>= 0));
                        System.out.println(age (>= 18) && (salary > 5000) && person.age() (<= 65));

                        long big = 20L;
                        System.out.println(big (>= 10L && <= 20L));
                        float f = 1.5f;
                        System.out.println(f (>= 1.0f && < 2.0f));
                        int hex = 255;
                        System.out.println(hex (== 0xFF && >= 0b1111_1111 && <= 255));
                        int low = 2;
                        int high = 3;
                        System.out.println((low + high) (>= 5 && <= 5));
                        double nan = Double.NaN;
                        System.out.println(nan (>= 0));
                        System.out.println(nan (!= nan));
                        String literal = "age (>= 18)";
                        // age (>= 99)
                        /* salary (>= 1) */
                        System.out.println(literal);
                        System.out.println("done");
                    }
                }
                """;
    }

    private static String javaProgram() {
        return """
                public class JavaBehavior {
                    static int calls;
                    static int lists;
                    static int next(int value) { calls++; return value; }
                    static int boom() { throw new RuntimeException("rhs"); }
                    static int side(String name, int value) { System.out.print(name); return value; }
                    static java.util.ArrayList<java.util.List<String>> freshList() {
                        lists++;
                        return new java.util.ArrayList<>();
                    }

                    enum Color { RED, BLUE }

                    static class Address {
                        final Integer house;
                        Address(Integer house) { this.house = house; }
                        Integer house() { return house; }
                    }

                    static class Person {
                        final int age;
                        final Address address;
                        Person(int age, Address address) { this.age = age; this.address = address; }
                        int age() { return age; }
                        Address address() { return address; }
                    }

                    public static void main(String[] args) {
                        int age = 25;
                        int salary = 8000;
                        if (age >= 18 && age <= 65 && (salary > 5000)) System.out.println("accepted");

                        calls = 0;
                        int n1 = next(5);
                        System.out.println(n1 >= 1 && n1 <= 5);
                        System.out.println(calls);
                        calls = 0;
                        int n2 = next(0);
                        System.out.println(n2 >= 1 && n2 <= boom());
                        System.out.println(calls);

                        String s = "hi";
                        System.out.println(s == "hi");
                        System.out.println(s != "no");
                        Color color = Color.RED;
                        System.out.println(color == Color.RED);
                        System.out.println(color != Color.BLUE);

                        Integer boxed = 1000;
                        Integer boxed2 = 1000;
                        System.out.println(boxed == boxed2);
                        System.out.println(boxed == 1000);
                        int primitive = 1000;
                        Integer wrapped = 1000;
                        System.out.println(primitive == wrapped);
                        System.out.println(wrapped == primitive);
                        Integer nil = null;
                        System.out.println(nil == null);
                        try {
                            boolean ignored = nil >= 0;
                            System.out.println("no-npe");
                        } catch (NullPointerException ex) {
                            System.out.println("npe");
                        }

                        double d = -1.5;
                        System.out.println(d >= -2.0 && d < 0.0);
                        int neg = -3;
                        System.out.println(neg >= -5 && neg <= -1);
                        int five = 5;
                        System.out.println(five >= 0 || five >= 100 && five >= 6);
                        System.out.println((five >= 0 || five >= 100) && five >= 6);
                        int thirtyTwo = 32;
                        System.out.println(thirtyTwo < 18 || thirtyTwo >= 30 && thirtyTwo < 40);
                        int ten = 10;
                        System.out.println(ten >= 18 || ten >= 30 && ten < 40);

                        Person person = new Person(25, new Address(4));
                        Integer house = person.address().house();
                        System.out.println(house != null && house >= 0);
                        Person empty = new Person(25, new Address(null));
                        Integer noHouse = empty.address().house();
                        System.out.println(noHouse != null && noHouse >= 0);
                        int[] arr = {1, 2, 3};
                        System.out.println(arr[1] >= 2 && arr[1] <= 2);
                        java.util.ArrayList<java.util.List<String>> once = freshList();
                        System.out.println(once.size() >= 0 && once.size() <= 0);
                        System.out.println(lists);

                        char ch = 'b';
                        System.out.println(ch >= 'a' && ch <= 'z');
                        boolean flag = true;
                        System.out.println(flag == true && flag != false);
                        int $gce$0 = 100;
                        System.out.println(age >= 18 && age <= $gce$0);
                        System.out.println(age >= $gce$0);

                        int a = side("A", 5);
                        System.out.println(a >= side("B", 1) && a <= side("C", 9));
                        System.out.println(false && side("Z", 1) >= 0);
                        int dSide = side("D", 5);
                        System.out.println(dSide >= 0 || dSide <= side("E", 0));
                        System.out.println(side("F", 0) >= 1 && side("G", 1) >= 0);
                        System.out.println(age >= 18 && (salary > 5000) && person.age() <= 65);

                        long big = 20L;
                        System.out.println(big >= 10L && big <= 20L);
                        float f = 1.5f;
                        System.out.println(f >= 1.0f && f < 2.0f);
                        int hex = 255;
                        System.out.println(hex == 0xFF && hex >= 0b1111_1111 && hex <= 255);
                        int low = 2;
                        int high = 3;
                        int sum = (low + high);
                        System.out.println(sum >= 5 && sum <= 5);
                        double nan = Double.NaN;
                        System.out.println(nan >= 0);
                        System.out.println(nan != nan);
                        String literal = "age (>= 18)";
                        System.out.println(literal);
                        System.out.println("done");
                    }
                }
                """;
    }
}
