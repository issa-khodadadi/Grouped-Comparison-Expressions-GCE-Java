# Roadmap

Version 1.0.0 is a preprocessor with a tested semantics and an honest boundary. It is not a JDK patch.

## Done in 0.2

- Lexer that keeps strings, text blocks, characters, and comments intact
- Comparison AST with Java `&&` / `||` precedence
- Operand expressions beyond a single name
- One evaluation of the subject, including a group nested in an operand
- Type-preserving `var` lowering was introduced during 0.2 development; the 0.2.1 supplier-based design was later replaced by switch-expression lowering in 0.3.0
- In-memory JDK 25 compile-and-run tests
- Specification that separates implemented syntax from later syntax

## Done in 0.2.1

- The subject is an argument of `GceEval.of`, so it is not captured and a checked exception it throws can propagate.
- Groups nested in a subject call, a parenthesized subject, or a subject index are rewritten.
- Casts, `++`/`--`, and qualified `new` are explicit syntax errors in subject and operand position.
- Parser nesting is capped at 64.

## Done in 0.3.0

- The subject is bound with `var` inside a switch-expression block. Operand locals need not be effectively final, and checked exceptions from operands propagate.
- `instanceof` groups, including one pattern name.
- Casts, `++`/`--`, qualified `new`, lambdas, method references, and `switch` expressions in subject or operand position.

## Done in 1.0.0

- Parenthesized `null` subjects bind as `Object`.
- `a + b (>= 0)` is a diagnostic. The parenthesized sum remains the way to compare the sum.
- `case` labels and annotation values that contain a group are diagnostics.
- Record patterns and `_` are copied into the `instanceof` operand.
- Lambdas with several parameters, nested lambdas, and method references have compile-and-run tests.

## Still open

1. **A precedence walk that accepts `a + b (>= 0)` as the sum.** 1.0.0 rejects it so the meaning cannot change silently. Accepting it has to stop at `&&` and `||`.

2. **Comments inside a group.** They sit in the replaced span and are dropped on purpose.

3. **`javac` integration, as an experiment.** The path is still "rewrite, then compile". A compiler plugin sees Java, not GCE, unless the rewrite happens first. That experiment would not mean OpenJDK had accepted the feature.

4. **A JMH comparison.** `GceBenchmark` is not part of the 1.0.0 claim.

## Explicitly later

- IDE highlighting and formatting
- A source map from generated lines back to GCE lines
- Additional pattern forms, guard patterns, and broader `switch`-expression contexts
- Splitting `>>` and `>>>` when one character closes a type argument and the next is an operator
- Guard-pattern support and broader pattern-flow analysis beyond the tested forms
- A more complete parser for Java switch expressions in every operand context
- Any proposal text aimed at amber-spec or the JLS

## Non-goals

- Replacing `javac` or shipping a forked JDK
- Silent acceptance of syntax the grammar does not define
- Treating the 0.1 `Gce.test` helper as the semantics of `==`
