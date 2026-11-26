// SPDX-License-Identifier: Apache-2.0
package dev.gce.compiler.lexer;

/**
 * A token whose offsets are indexes into the original source that was scanned,
 * not into a unicode-decoded buffer. {@code end} is exclusive.
 */
public record Token(TokenType type, String lexeme, int start, int end) {
    public boolean is(TokenType expected) {
        return type == expected;
    }

    public boolean isKeyword(String text) {
        return type == TokenType.KEYWORD && lexeme.equals(text);
    }

    public boolean isComparisonOperator() {
        return switch (type) {
            case EQ, NEQ, LT, GT, LE, GE -> true;
            default -> false;
        };
    }
}
