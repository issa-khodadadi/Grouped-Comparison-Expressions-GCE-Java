package dev.gce.compiler.ast;

/**
 * One boolean condition inside a grouped comparison.
 * {@code &&} binds tighter than {@code ||}, and both are left-associative, as in Java.
 */
public sealed interface ComparisonNode
        permits AndCondition, OrCondition, ParenthesizedCondition, RelationalCondition {
}
