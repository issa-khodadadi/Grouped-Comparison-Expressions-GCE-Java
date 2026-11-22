# Roadmap

Version 0.2.1 is a preprocessor with a tested semantics and an honest boundary. It is a base for language-extension experiments, not a JDK patch.

## Done in 0.2

- Lexer that keeps strings, text blocks, characters, and comments intact
- Comparison AST with Java `&&` / `||` precedence
- Operand expressions beyond a single name
- One evaluation of the subject, including a group nested in an operand
- Type-preserving `var` inside a supplier, so `==` is not silently changed by boxing. 0.2.1 replaced that supplier because it captured locals
- In-memory JDK 25 compile-and-run tests
- Specification that separates implemented syntax from later syntax

## Done in 0.2.1

- The subject is an argument of `GceEval.of`, so it is not captured and a checked exception it throws can propagate.
- Groups nested in a subject call, a parenthesized subject, or a subject index are rewritten.
- Casts, `++`/`--`, and qualified `new` are explicit syntax errors in subject and operand position.
- Parser nesting is capped at 64.

## Version 0.3

These are the next design problems, in the order they block a more serious prototype.

1. **`instanceof` groups.** `shape (instanceof Circle || instanceof Rectangle)` needs a type-test grammar, not another relational operator pasted onto a value. Pattern variables (`instanceof Circle c`) need a scope rule. This stays out until that grammar exists.

2. **Checked exceptions from operands.** A subject exception propagates as of 0.2.1. An operand is still inside the predicate lambda, so a checked exception there is rejected by `javac`. Sneaky-throw is not a substitute; it would make the output lie about the method's throws clause.

3. **Operand capture.** A local named only by the subject may be reassigned. A local named by an operand is still captured and must be effectively final.

4. **Cheaper lowering, measured with JMH.** 0.2.1 allocates one chain object per evaluated group. The 0.2 supplier numbers are not a measurement of that chain. If a statement-position temporary or a bytecode lowering is attempted, JMH belongs in the comparison.

5. **Subjects that are not postfix expressions.** `(a + b) (>= 0)` works. `a + b (>= 0)` does not treat the sum as the subject. Extending the subject leftward through binary operators needs a real precedence walk so `flag && a + b (>= 0)` does not swallow the `&&`.

6. **Comments inside a group.** They are currently inside the replaced span and disappear. A translation that keeps them has to thread trivia from the lexer through the generator.

7. **`javac` integration, as an experiment.** The viable path is still "rewrite, then compile". A `javac` plugin or a `com.sun.source.util.Plugin` would see Java, not GCE, unless the rewrite happens first. Exploring that boundary is useful. Claiming that OpenJDK has accepted the feature would not be.

## Explicitly later

- IDE highlighting and formatting
- A source map from generated lines back to GCE lines
- Pattern matching, record patterns, and `switch` expressions as operands
- Splitting `>>` and `>>>` when one character closes a type argument and the next is an operator
- Any proposal text aimed at amber-spec or the JLS

## Non-goals

- Replacing `javac` or shipping a forked JDK
- Silent acceptance of syntax the grammar does not define
- Treating the 0.1 `Gce.test` helper as the semantics of `==`
