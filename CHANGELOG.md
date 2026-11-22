# Changelog

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
