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
 * <p>The subject is the argument of {@code GceEval.of}, so it is evaluated in the
 * enclosing expression. The predicate lambda only names the chain's parameter.
 * {@code of} is not invoked when a surrounding {@code &&} or {@code ||} skips the group.
 */
public final class JavaCodeGenerator {
    public String generate(String subject, ComparisonNode condition, String temp,
                           BiFunction<Integer, Integer, String> operands) {
        String predicate = emit(condition, temp, operands);
        return "(dev.gce.runtime.GceEval.of((" + subject + ")).is(" + temp + " -> " + predicate + "))";
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
