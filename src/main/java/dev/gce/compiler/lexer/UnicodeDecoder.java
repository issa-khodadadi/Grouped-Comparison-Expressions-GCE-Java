// SPDX-License-Identifier: Apache-2.0
package dev.gce.compiler.lexer;

import dev.gce.compiler.GceSyntaxException;
import dev.gce.compiler.SourceLocations;

/**
 * Translates Java unicode escapes ({@code \\uXXXX}) once, left to right, before lexical analysis.
 * Translation is not recursive, matching JLS 3.3. Each decoded UTF-16 char records the original
 * index where its spelling begins, so later stages can slice and diagnose the user's source.
 */
public final class UnicodeDecoder {
    private final String decoded;
    private final int[] origin;
    private final int originalLength;

    private UnicodeDecoder(String decoded, int[] origin, int originalLength) {
        this.decoded = decoded;
        this.origin = origin;
        this.originalLength = originalLength;
    }

    public String decoded() {
        return decoded;
    }

    public static UnicodeDecoder decode(String source) {
        StringBuilder out = new StringBuilder(source.length());
        int[] map = new int[source.length()];
        int written = 0;
        int i = 0;
        while (i < source.length()) {
            if (source.charAt(i) == '\\' && startsUnicodeEscape(source, i)) {
                int start = i;
                i++;
                while (i < source.length() && source.charAt(i) == 'u') {
                    i++;
                }
                if (i + 4 > source.length()) {
                    throw error(source, start, "truncated unicode escape");
                }
                int value = 0;
                for (int hex = 0; hex < 4; hex++) {
                    int digit = hexDigit(source.charAt(i + hex));
                    if (digit < 0) {
                        throw error(source, start, "invalid unicode escape");
                    }
                    value = (value << 4) | digit;
                }
                i += 4;
                out.append((char) value);
                map[written++] = start;
            } else {
                out.append(source.charAt(i));
                map[written++] = i;
                i++;
            }
        }
        int[] trimmed = new int[written];
        System.arraycopy(map, 0, trimmed, 0, written);
        return new UnicodeDecoder(out.toString(), trimmed, source.length());
    }

    /** Original index of a decoded char, or the original length when {@code decodedIndex} is at the end. */
    public int originalIndex(int decodedIndex) {
        if (decodedIndex >= origin.length) {
            return originalLength;
        }
        return origin[decodedIndex];
    }

    private static boolean startsUnicodeEscape(String source, int backslash) {
        int i = backslash + 1;
        return i < source.length() && source.charAt(i) == 'u';
    }

    private static int hexDigit(char c) {
        if (c >= '0' && c <= '9') {
            return c - '0';
        }
        if (c >= 'a' && c <= 'f') {
            return c - 'a' + 10;
        }
        if (c >= 'A' && c <= 'F') {
            return c - 'A' + 10;
        }
        return -1;
    }

    private static GceSyntaxException error(String source, int offset, String detail) {
        int[] position = SourceLocations.position(source, offset);
        return new GceSyntaxException(detail, offset, position[0], position[1]);
    }
}
