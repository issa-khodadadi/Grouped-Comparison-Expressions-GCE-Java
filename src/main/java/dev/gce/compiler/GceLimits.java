// SPDX-License-Identifier: Apache-2.0
package dev.gce.compiler;

/** Shared bounds for recursive translation. Exceeding one throws {@link GceSyntaxException}. */
public final class GceLimits {
    /**
     * Maximum nesting of comparison groups, parentheses, unary operators, and conditional
     * operators. The parser checks this before it recurses, so a hostile file becomes a
     * syntax error instead of a {@link StackOverflowError}.
     */
    public static final int MAX_NESTING = 64;

    private GceLimits() {}
}
