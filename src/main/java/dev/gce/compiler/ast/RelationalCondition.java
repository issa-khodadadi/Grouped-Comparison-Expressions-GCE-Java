package dev.gce.compiler.ast;

/**
 * A single comparison such as {@code >= 18}. Operand offsets are indexes into the source
 * fragment being parsed. {@code operandEnd} is exclusive.
 */
public record RelationalCondition(String operator, int operandStart, int operandEnd) implements ComparisonNode {
}
