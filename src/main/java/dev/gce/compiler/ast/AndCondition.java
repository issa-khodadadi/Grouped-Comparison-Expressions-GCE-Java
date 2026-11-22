package dev.gce.compiler.ast;

/** Left-associative {@code &&}. */
public record AndCondition(ComparisonNode left, ComparisonNode right) implements ComparisonNode {
}
