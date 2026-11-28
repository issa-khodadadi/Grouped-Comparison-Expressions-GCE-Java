// SPDX-License-Identifier: Apache-2.0
package dev.gce.compiler;

import dev.gce.compiler.lexer.Lexer;
import dev.gce.compiler.lexer.Token;
import dev.gce.compiler.lexer.TokenType;
import dev.gce.compiler.parser.GceRecognizer;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Source-to-source translator for Grouped Comparison Expressions.
 *
 * <p>A form such as {@code age (>= 18 && <= 65)} becomes a standard Java expression that
 * evaluates {@code age} once and then applies each comparison with Java's {@code &&} and
 * {@code ||} short-circuit rules. Ordinary Java, including ordinary comparisons, is copied
 * unchanged. Text inside comments, string literals, character literals, and text blocks is
 * never treated as a comparison group.
 *
 * <p>Version 1.0.0-rc1 emits a switch expression with a {@code var} binding, or {@code Object} for a null literal. It does not modify
 * {@code javac} and it does not accept every Java program. See {@code docs/GCE-LANGUAGE-SPECIFICATION.md}.
 */
public final class GceTranspiler {
    /** Translates {@code source}, which may be a compilation unit or a fragment. */
    public String transpile(String source) {
        Objects.requireNonNull(source, "source");
        return rewrite(source, new HashSet<>(), source, 0, 0, true);
    }

    private String rewrite(String fragment, Set<String> reserved, String fullSource, int baseOffset, int depth, boolean collectNames) {
        if (depth > GceLimits.MAX_NESTING) {
            int[] position = SourceLocations.position(fullSource, baseOffset);
            throw new GceSyntaxException(
                    "nesting is too deep",
                    baseOffset,
                    position[0],
                    position[1]);
        }
        List<Token> tokens;
        try {
            tokens = new Lexer(fragment).lex();
        } catch (GceSyntaxException ex) {
            throw baseOffset == 0 ? ex : ex.relocate(fullSource, baseOffset);
        }
        if (collectNames) {
            for (Token token : tokens) {
                if (token.type() == TokenType.IDENTIFIER) {
                    reserved.add(token.lexeme());
                }
            }
        }
        GceRecognizer recognizer = new GceRecognizer(
                tokens,
                fragment,
                fullSource,
                baseOffset,
                reserved,
                (start, end) -> rewriteOperand(fragment, reserved, fullSource, baseOffset, depth, start, end));
        List<GceRecognizer.Replacement> replacements = recognizer.recognize();
        if (replacements.isEmpty()) {
            return fragment;
        }
        StringBuilder out = new StringBuilder(fragment.length() + replacements.size() * 64);
        int cursor = 0;
        for (GceRecognizer.Replacement replacement : replacements) {
            if (replacement.start() < cursor || replacement.end() < replacement.start() || replacement.end() > fragment.length()) {
                throw new IllegalStateException("internal GCE replacement overlap at offset " + replacement.start());
            }
            out.append(fragment, cursor, replacement.start());
            out.append(replacement.text());
            cursor = replacement.end();
        }
        out.append(fragment, cursor, fragment.length());
        return out.toString();
    }

    private String rewriteOperand(String fragment, Set<String> reserved, String fullSource, int baseOffset,
                                  int depth, int start, int end) {
        if (start < 0 || end > fragment.length() || start >= end) {
            int[] position = SourceLocations.position(fullSource, baseOffset + Math.max(start, 0));
            throw new GceSyntaxException("empty GCE operand", baseOffset + Math.max(start, 0), position[0], position[1]);
        }
        return rewrite(fragment.substring(start, end), reserved, fullSource, baseOffset + start, depth + 1, false);
    }
}
