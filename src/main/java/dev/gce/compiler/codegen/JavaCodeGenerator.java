package dev.gce.compiler.codegen;

import dev.gce.compiler.ast.AndCondition;
import dev.gce.compiler.ast.ComparisonNode;
import dev.gce.compiler.ast.OrCondition;
import dev.gce.compiler.ast.ParenthesizedCondition;
import dev.gce.compiler.ast.RelationalCondition;

import java.util.function.BiFunction;

/**
 * Renders a comparison group as a Java expression. The subject is read into a {@code var}
 * inside a supplier, so it keeps its primitive or reference type and is evaluated once,
 * only when the surrounding expression evaluates the supplier.
 */
public final class JavaCodeGenerator {
    public String generate(String subject, ComparisonNode condition, String temp,
                           BiFunction<Integer, Integer, String> operands) {
        String predicate = emit(condition, temp, operands);
        return "(((java.util.function.Supplier<Boolean>) () -> { var "
                + temp
                + " = ("
                + subject
                + "); return "
                + predicate
                + "; }).get())";
    }

    private String emit(ComparisonNode node, String temp, BiFunction<Integer, Integer, String> operands) {
        return switch (node) {
            case OrCondition or -> "(" + emit(or.left(), temp, operands) + " || " + emit(or.right(), temp, operands) + ")";
            case AndCondition and -> "(" + emit(and.left(), temp, operands) + " && " + emit(and.right(), temp, operands) + ")";
            case ParenthesizedCondition parenthesized -> "(" + emit(parenthesized.inner(), temp, operands) + ")";
            case RelationalCondition relational -> "(" + temp + " " + relational.operator() + " ("
                    + operands.apply(relational.operandStart(), relational.operandEnd()) + "))";
        };
    }
}
