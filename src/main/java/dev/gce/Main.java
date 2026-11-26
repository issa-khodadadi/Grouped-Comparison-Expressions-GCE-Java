// SPDX-License-Identifier: Apache-2.0
package dev.gce;

import dev.gce.compiler.GceTranspiler;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Translates a Java file that may contain grouped comparisons.
 * With no arguments, prints a small demonstration.
 * With one path, writes the translation to standard output.
 * With two paths, writes UTF-8 source to the second path. That avoids PowerShell's
 * {@code >} redirection, which saves stdout as UTF-16 and makes {@code javac} report {@code \\u0000}.
 */
public final class Main {
    private Main() {}

    public static void main(String[] args) throws Exception {
        if (args.length > 2) {
            usage();
            System.exit(2);
            return;
        }
        if (args.length == 0) {
            System.out.print(new GceTranspiler().transpile(demo()));
            return;
        }
        Path input = Path.of(args[0]);
        if (!Files.isRegularFile(input)) {
            System.err.println("File not found: " + input.toAbsolutePath());
            System.err.println("Pass the path of a .java file, or run with no arguments for a demo.");
            System.exit(1);
            return;
        }
        String translated = new GceTranspiler().transpile(Files.readString(input));
        if (args.length == 1) {
            System.out.print(translated);
            return;
        }
        Path output = Path.of(args[1]);
        Path parent = output.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        warnIfClassNameDiffers(translated, output);
        Files.writeString(output, translated, StandardCharsets.UTF_8);
    }

    private static final Pattern PUBLIC_CLASS = Pattern.compile(
            "\\bpublic\\s+(?:(?:final|abstract|sealed|non-sealed)\\s+)*class\\s+([A-Za-z_$][A-Za-z0-9_$]*)");

    private static void warnIfClassNameDiffers(String source, Path output) {
        Matcher matcher = PUBLIC_CLASS.matcher(source);
        if (!matcher.find()) {
            return;
        }
        String className = matcher.group(1);
        String fileName = output.getFileName() == null ? "" : output.getFileName().toString();
        if (!fileName.equals(className + ".java")) {
            System.err.println("Warning: public class " + className + " must be compiled from a file named " + className + ".java.");
        }
    }

    private static void usage() {
        System.err.println("Usage: java -jar gce-compiler.jar [Input.java [Output.java]]");
    }

    private static String demo() {
        return """
                int age = 25;
                int salary = 8000;
                if (age (>= 18 && <= 65) && (salary > 5000)) {
                    System.out.println("Accepted");
                }
                """;
    }
}
