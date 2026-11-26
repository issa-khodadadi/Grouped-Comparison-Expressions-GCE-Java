// SPDX-License-Identifier: Apache-2.0
package dev.gce.compiler.ast;

/** Parentheses that force condition precedence, for example {@code (>= 0 || >= 1)}. */
public record ParenthesizedCondition(ComparisonNode inner) implements ComparisonNode {
}
