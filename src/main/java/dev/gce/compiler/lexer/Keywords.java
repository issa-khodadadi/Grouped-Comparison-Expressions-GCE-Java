// SPDX-License-Identifier: Apache-2.0
package dev.gce.compiler.lexer;

import java.util.Set;

/** Hard keywords from JLS 3.9. Restricted identifiers such as {@code var} stay identifiers. */
public final class Keywords {
    private static final Set<String> KEYWORDS = Set.of(
            "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char", "class",
            "const", "continue", "default", "do", "double", "else", "enum", "extends", "final",
            "finally", "float", "for", "goto", "if", "implements", "import", "instanceof", "int",
            "interface", "long", "native", "new", "package", "private", "protected", "public",
            "return", "short", "static", "strictfp", "super", "switch", "synchronized", "this",
            "throw", "throws", "transient", "try", "void", "volatile", "while", "_"
    );

    private Keywords() {}

    public static boolean isKeyword(String lexeme) {
        return KEYWORDS.contains(lexeme);
    }

    public static boolean isPrimitive(String lexeme) {
        return switch (lexeme) {
            case "boolean", "byte", "short", "int", "long", "char", "float", "double" -> true;
            default -> false;
        };
    }
}
