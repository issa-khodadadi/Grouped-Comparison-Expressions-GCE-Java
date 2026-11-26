// SPDX-License-Identifier: Apache-2.0
package dev.gce.compiler.codegen;

import dev.gce.compiler.ast.AndCondition;
import dev.gce.compiler.ast.ComparisonNode;
import dev.gce.compiler.ast.OrCondition;
import dev.gce.compiler.ast.ParenthesizedCondition;
import dev.gce.compiler.ast.RelationalCondition;

import java.util.function.BiFunction;

/**
 * Renders a comparison group as a Java expression.
 *
 * <p>The subject is bound with {@code var} inside a switch-expression block. That block is part of
 * the enclosing method: it is not a lambda, so a local used by the subject or an operand does not
 * have to be effectively final, and a checked exception propagates. The switch runs only when the
 * surrounding expression evaluates the group, so {@code false && subject (>= 0)} still skips the
 * subject. {@code instanceof} operands are types, not parenthesized expressions.
 */
public final class JavaCodeGenerator {
    public String generate(String subject, ComparisonNode condition, String temp,
                           BiFunction<Integer, Integer, String> operands, boolean nullLiteral) {
        String predicate = emit(condition, temp, operands);
        String binding = nullLiteral
                ? "Object " + temp + " = (" + subject + ");"
                : "var " + temp + " = (" + subject + ");";
        return "(switch (0) { default -> { " + binding + " yield " + predicate + "; } })";
    }

    private String emit(ComparisonNode node, String temp, BiFunction<Integer, Integer, String> operands) {
        return switch (node) {
            case OrCondition or -> "(" + emit(or.left(), temp, operands) + " || " + emit(or.right(), temp, operands) + ")";
            case AndCondition and -> "(" + emit(and.left(), temp, operands) + " && " + emit(and.right(), temp, operands) + ")";
            case ParenthesizedCondition parenthesized -> "(" + emit(parenthesized.inner(), temp, operands) + ")";
            case RelationalCondition relational -> emitRelational(relational, temp, operands);
        };
    }

    private String emitRelational(RelationalCondition relational, String temp,
                                  BiFunction<Integer, Integer, String> operands) {
        String operand = operands.apply(relational.operandStart(), relational.operandEnd());
        if ("instanceof".equals(relational.operator())) {
            return "(" + temp + " instanceof " + operand + ")";
        }
        return "(" + temp + " " + relational.operator() + " (" + operand + "))";
    }
}
