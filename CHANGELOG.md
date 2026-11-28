# Changelog

## 1.0.0-rc1 — 2026-11-26

Release candidate. This is not a final 1.0.0 and it is not a claim that GCE is part of Java.

- Several independent comparison groups can share one condition. Each group keeps its own subject, and that subject is still evaluated once. The number of groups and the number of comparisons in each group are not fixed.
- `&&` and `||` between groups follow Java precedence and associativity. Parentheses still override that. A group is skipped when the surrounding operator short-circuits.
- An `if` or `while` (including `do`/`while`) whose `(` is the first subject's `(` now keeps the rest of the condition inside those parentheses. The previous output closed the header on the first switch expression, so `javac` reported `illegal start of expression`, `not a statement`, and `';' expected` on the following `&&`.
- Groups that were already inside one pair of parentheses, and ordinary Java beside a group, are unchanged.

## 1.0.0 — 2026-11-24

The 0.3.0 switch lowering stays. This release closes the correctness holes that blocked calling it 1.0.

- A subject that is a `null` literal wrapped in any number of parentheses is bound as `Object`. The check is on tokens, so `(null)` and `((null))` compile.
- An unparenthesized arithmetic, shift, or bitwise operator immediately before a group is a `GceSyntaxException`. `a + b (>= 0)` is no longer translated as a comparison of `b`. `(a + b) (>= 0)` is unchanged. `flag && age (>= 18)` is unchanged.
- A directly attached unary `+`, `-`, `!`, or `~` is part of the subject.
- A group in a `case` label or an annotation value is rejected. The switch form is not a constant expression, and the translator does not hand that failure to `javac`.
- `instanceof` operands accept a type pattern, `final`, `_`, and a record pattern, including a nested record pattern. The pattern text is copied through.
- End-to-end tests cover parenthesized null, one-evaluation of a parenthesized sum, multi-parameter lambdas, nested lambdas, method references, and record patterns.
- Comments inside the replaced span are still dropped. That is the preservation policy, and a test locks it. Comments outside the span stay.
- No performance number is part of this release.

## 0.3.0 — 2026-11-24

The translation no longer uses a lambda or `GceEval`.

- A group is a switch expression: `switch (0) { default -> { var $gce$N = (subject); yield predicate; } }`. The block is part of the enclosing method. A local used by the subject or an operand need not be effectively final, and a checked exception from either one propagates. `false &&` still skips the switch, so the subject stays lazy. `var` keeps the subject type. A `null` literal is bound as `Object`.
- `instanceof` is a group operator, including a single pattern variable (`instanceof String s`).
- Casts, prefix and postfix `++`/`--`, qualified `holder.new Inner()`, lambdas, method references, and `switch` expressions are accepted as subjects or operands. Assignment in an operand is still rejected.
- Generated source no longer depends on the compiler jar at compile time or run time. `Gce.test` remains available and still boxes.
- This is not a change to `javac` or to the Java language.

## 0.2.1 — 2026-11-22

Semantic and reliability fixes for the 0.2 translator. No new group operators.

- The subject is now the argument of `dev.gce.runtime.GceEval.of`, and the comparisons are the body of `is`. A surrounding `&&` or `||` still skips the call, so the subject stays lazy. A reassigned local, a `for` index, and `a[i]` in an index loop compile. A checked exception thrown by the subject propagates.
- Primitive overloads keep `==` from boxing. `int == Integer` stays numeric. `Integer == Integer` stays identity. `char`, `byte`, and `short` widen to `int`. `float` widens to `double`.
- A group nested inside a subject call, a parenthesized subject, or a subject array index is rewritten, and each subject still runs once.
- Casts, prefix and postfix `++`/`--`, and qualified instance creation are syntax errors when they are a GCE subject or operand. Ordinary Java using those forms is copied through.
- Nesting deeper than 64 groups, parentheses, unary operators, or conditional operators throws `GceSyntaxException` instead of `StackOverflowError`.
- Operands are still inside the predicate lambda. A checked exception from an operand, or an operand that names a non-effectively-final local, is rejected by `javac`.
- `Gce.test` is unchanged and still boxes. The translator does not call it.

## 0.2.0 — 2026-11-22

Source-to-source translator with a real lexer, a comparison AST, and JDK 25 compile-and-run tests.

- Replaced the regular-expression scan and the restricted condition parser with a lexer and a recursive-descent parser.
- Comments, strings, character literals, and text blocks are no longer rewritten.
- Subjects can be fields, calls with arguments, array access, `new`, `this`, `super`, and parenthesized expressions.
- Operands can be literals (including signed, hex, and binary numbers), names, calls, and a documented Java expression subset.
- `&&` binds more tightly than `||`. Both are left-associative. Nested parentheses override that.
- The subject is evaluated once, only when the group is evaluated, and `&&` / `||` still short-circuit.
- Generated code stores the subject in `var` inside a `Supplier<Boolean>`. Temporary names are `$gce$0`, `$gce$1`, … and skip identifiers already used in the file.
- **Breaking:** the translator no longer emits `dev.gce.runtime.Gce.test` or the fixed name `__gce_value`. Those boxed predicates could change `==` for primitives. `Gce.test` remains available for direct calls and documents the boxing.
- `instanceof` groups are rejected with an explicit 0.2 error instead of being parsed as a supported operator.
- Syntax exceptions include line and column.
- End-to-end tests compile the translation with the JDK 25 compiler API and execute it.

## 0.1.0

Initial prototype. A regular expression found a simple method-chain subject, a small parser rewrote the group, and `Gce.test` evaluated a boxed supplier and predicate. `instanceof`, strings, comments, and compile-and-run checks were out of scope.
