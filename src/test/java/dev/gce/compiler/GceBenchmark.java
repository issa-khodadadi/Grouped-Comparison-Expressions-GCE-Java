package dev.gce.compiler;

import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Manual measurement for version 0.2. Not part of the test suite.
 * Run: {@code java -cp target/classes;target/test-classes dev.gce.compiler.GceBenchmark}
 */
public final class GceBenchmark {
    public static void main(String[] args) throws Exception {
        StringBuilder source = new StringBuilder();
        source.append("class T { boolean m() { return ");
        for (int i = 0; i < 1000; i++) {
            if (i > 0) {
                source.append(" && ");
            }
            source.append("n").append(i).append(" (>= 0 && <= ").append(i).append(')');
        }
        source.append("; } }");
        String input = source.toString();
        GceTranspiler transpiler = new GceTranspiler();
        for (int i = 0; i < 5; i++) {
            transpiler.transpile(input);
        }
        long start = System.nanoTime();
        int iterations = 20;
        String output = null;
        for (int i = 0; i < iterations; i++) {
            output = transpiler.transpile(input);
        }
        long elapsed = System.nanoTime() - start;
        System.out.println("inputChars=" + input.length());
        System.out.println("outputChars=" + output.length());
        System.out.println("ratio=" + (output.length() / (double) input.length()));
        System.out.println("transpileMsFor1000Groups=" + (elapsed / iterations / 1_000_000.0));

        String plain = """
                public class PlainBench {
                    public static int run(int loops) {
                        int sum = 0;
                        for (int i = 0; i < loops; i++) {
                            int n = i & 127;
                            if (n >= 18 && n <= 65) sum++;
                        }
                        return sum;
                    }
                }
                """;
        String grouped = transpiler.transpile("""
                public class GroupedBench {
                    public static int run(int loops) {
                        int sum = 0;
                        for (int i = 0; i < loops; i++) {
                            int n = i & 127;
                            if (n (>= 18 && <= 65)) sum++;
                        }
                        return sum;
                    }
                }
                """);
        int loops = 2_000_000;
        Class<?> plainClass = compile("PlainBench", plain);
        Class<?> groupedClass = compile("GroupedBench", grouped);
        System.out.println("plainResult=" + run(plainClass, loops));
        System.out.println("groupedResult=" + run(groupedClass, loops));
        System.out.println("plainMs=" + timeMillis(plainClass, loops));
        System.out.println("groupedMs=" + timeMillis(groupedClass, loops));
    }

    private static Class<?> compile(String name, String source) throws Exception {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        Memory manager = new Memory(compiler.getStandardFileManager(null, null, StandardCharsets.UTF_8));
        JavaFileObject file = new SimpleJavaFileObject(URI.create("mem:///" + name + ".java"), JavaFileObject.Kind.SOURCE) {
            @Override public CharSequence getCharContent(boolean ignore) { return source; }
        };
        Boolean ok = compiler.getTask(null, manager, null,
                List.of("--release", "25", "--class-path", System.getProperty("java.class.path")),
                null, List.of(file)).call();
        if (!Boolean.TRUE.equals(ok)) {
            throw new IllegalStateException("benchmark source failed to compile");
        }
        ClassLoader loader = new ClassLoader() {
            @Override
            protected Class<?> findClass(String className) throws ClassNotFoundException {
                byte[] bytes = manager.classes.get(className);
                if (bytes == null) throw new ClassNotFoundException(className);
                return defineClass(className, bytes, 0, bytes.length);
            }
        };
        return Class.forName(name, true, loader);
    }

    private static int run(Class<?> type, int loops) throws Exception {
        Method method = type.getMethod("run", int.class);
        return (int) method.invoke(null, loops);
    }

    private static double timeMillis(Class<?> type, int loops) throws Exception {
        Method method = type.getMethod("run", int.class);
        for (int i = 0; i < 3; i++) {
            method.invoke(null, loops);
        }
        long start = System.nanoTime();
        int repeats = 5;
        for (int i = 0; i < repeats; i++) {
            method.invoke(null, loops);
        }
        return (System.nanoTime() - start) / repeats / 1_000_000.0;
    }

    private static final class Memory extends javax.tools.ForwardingJavaFileManager<javax.tools.JavaFileManager> {
        final Map<String, byte[]> classes = new HashMap<>();
        final Map<String, ByteArrayOutputStream> open = new HashMap<>();

        Memory(javax.tools.JavaFileManager manager) { super(manager); }

        @Override
        public JavaFileObject getJavaFileForOutput(Location location, String className, JavaFileObject.Kind kind, javax.tools.FileObject sibling) {
            return new SimpleJavaFileObject(URI.create("mem:///" + className + ".class"), kind) {
                @Override
                public OutputStream openOutputStream() {
                    ByteArrayOutputStream out = new ByteArrayOutputStream();
                    open.put(className, out);
                    return new OutputStream() {
                        @Override public void write(int b) { out.write(b); }
                        @Override public void write(byte[] b, int off, int len) { out.write(b, off, len); }
                        @Override public void close() { classes.put(className, out.toByteArray()); }
                    };
                }
            };
        }
    }
}
