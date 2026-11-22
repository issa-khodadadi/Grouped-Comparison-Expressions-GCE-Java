# Changelog

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
