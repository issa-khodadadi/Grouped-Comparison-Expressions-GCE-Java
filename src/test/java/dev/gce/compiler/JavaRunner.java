// SPDX-License-Identifier: Apache-2.0
package dev.gce.compiler;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.FileObject;
import javax.tools.ForwardingJavaFileManager;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Compiles translated Java in memory with the running JDK and executes its {@code main}. */
final class JavaRunner {
    private static final Object OUTPUT_LOCK = new Object();

    private JavaRunner() {}

    static String compileAndRun(String className, String source) {
        Map<String, byte[]> classes = compile(className, source);
        ClassLoader loader = new ClassLoader(JavaRunner.class.getClassLoader()) {
            @Override
            protected Class<?> findClass(String name) throws ClassNotFoundException {
                byte[] bytes = classes.get(name);
                if (bytes == null) {
                    throw new ClassNotFoundException(name);
                }
                return defineClass(name, bytes, 0, bytes.length);
            }
        };
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        PrintStream sink = new PrintStream(captured, true, StandardCharsets.UTF_8);
        synchronized (OUTPUT_LOCK) {
            PrintStream previous = System.out;
            System.setOut(sink);
            try {
                Class<?> type = Class.forName(className, true, loader);
                Method main = type.getMethod("main", String[].class);
                main.invoke(null, (Object) new String[0]);
            } catch (InvocationTargetException ex) {
                throw new AssertionError("translated program threw " + ex.getCause() + "\n" + source, ex.getCause());
            } catch (ReflectiveOperationException ex) {
                throw new AssertionError("could not execute " + className + "\n" + source, ex);
            } finally {
                System.setOut(previous);
            }
        }
        return captured.toString(StandardCharsets.UTF_8);
    }

    static void assertCompiles(String className, String source) {
        compile(className, source);
    }

    static void assertDoesNotCompile(String className, String source) {
        try {
            compile(className, source);
        } catch (AssertionError ex) {
            return;
        }
        throw new AssertionError("expected compilation to fail\n" + source);
    }

    private static Map<String, byte[]> compile(String className, String source) {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new IllegalStateException("No Java compiler. Run tests on a JDK.");
        }
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        MemoryFileManager files = new MemoryFileManager(
                compiler.getStandardFileManager(diagnostics, Locale.ROOT, StandardCharsets.UTF_8));
        JavaCompiler.CompilationTask task = compiler.getTask(
                null,
                files,
                diagnostics,
                List.of("--release", "25", "--class-path", System.getProperty("java.class.path")),
                null,
                List.of(new SourceFile(className, source)));
        boolean success = Boolean.TRUE.equals(task.call());
        if (!success) {
            StringBuilder message = new StringBuilder("JDK 25 rejected the translated source:\n");
            for (Diagnostic<? extends JavaFileObject> diagnostic : diagnostics.getDiagnostics()) {
                message.append(diagnostic.getKind())
                        .append(" line ")
                        .append(diagnostic.getLineNumber())
                        .append(": ")
                        .append(diagnostic.getMessage(Locale.ROOT))
                        .append('\n');
            }
            message.append("--- source ---\n").append(source);
            throw new AssertionError(message.toString());
        }
        return files.bytes();
    }

    private static final class SourceFile extends SimpleJavaFileObject {
        private final String source;

        SourceFile(String className, String source) {
            super(URI.create("mem:///" + className.replace('.', '/') + ".java"), Kind.SOURCE);
            this.source = source;
        }

        @Override
        public CharSequence getCharContent(boolean ignoreEncodingErrors) {
            return source;
        }
    }

    private static final class MemoryFileManager extends ForwardingJavaFileManager<javax.tools.JavaFileManager> {
        private final Map<String, ByteArrayOutputStream> classes = new HashMap<>();

        MemoryFileManager(javax.tools.JavaFileManager manager) {
            super(manager);
        }

        @Override
        public JavaFileObject getJavaFileForOutput(Location location, String className, JavaFileObject.Kind kind, FileObject sibling) {
            return new SimpleJavaFileObject(URI.create("mem:///" + className.replace('.', '/') + ".class"), kind) {
                @Override
                public OutputStream openOutputStream() {
                    ByteArrayOutputStream out = new ByteArrayOutputStream();
                    classes.put(className, out);
                    return out;
                }
            };
        }

        Map<String, byte[]> bytes() {
            Map<String, byte[]> copied = new HashMap<>();
            classes.forEach((name, out) -> copied.put(name, out.toByteArray()));
            return copied;
        }
    }
}
