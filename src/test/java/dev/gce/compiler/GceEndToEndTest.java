package dev.gce.compiler;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GceEndToEndTest {
    private final GceTranspiler transpiler = new GceTranspiler();

    @Test void translatedProgramMatchesSingleEvaluationJava() {
        String translated = transpiler.transpile(gceProgram());
        assertFalse(translated.contains("dev.gce.runtime.Gce"));
        assertFalse(translated.contains("__gce_value"));
        assertTrue(translated.contains("\"age (>= 18)\""));
        assertTrue(translated.contains("// age (>= 99)"));
        assertTrue(translated.contains("/* salary (>= 1) */"));
        assertTrue(translated.contains("var $gce$"));

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

    private static String gceProgram() {
        return """
                public class GceBehavior {
                    static int calls;
                    static int next(int value) { calls++; return value; }
                    static int boom() { throw new RuntimeException("rhs"); }
                    static int side(String name, int value) { System.out.print(name); return value; }

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
                        System.out.println(new java.util.ArrayList<java.util.List<String>>().size() (>= 0 && <= 0));

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
                    static int next(int value) { calls++; return value; }
                    static int boom() { throw new RuntimeException("rhs"); }
                    static int side(String name, int value) { System.out.print(name); return value; }

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
                        System.out.println(new java.util.ArrayList<java.util.List<String>>().size() >= 0
                                && new java.util.ArrayList<java.util.List<String>>().size() <= 0);

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
