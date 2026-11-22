package dev.gce.compiler.ast;

/** Left-associative {@code ||}. */
public record OrCondition(ComparisonNode left, ComparisonNode right) implements ComparisonNode {
}
