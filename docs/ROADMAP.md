# Roadmap

Version 0.2 is a preprocessor with a tested semantics and an honest boundary. It is a base for language-extension experiments, not a JDK patch.

## Done in 0.2

- Lexer that keeps strings, text blocks, characters, and comments intact
- Comparison AST with Java `&&` / `||` precedence
- Operand expressions beyond a single name
- One evaluation of the subject, including a group nested in an operand
- Type-preserving `var`, so `==` is not silently changed by boxing
- In-memory JDK 25 compile-and-run tests
- Specification that separates implemented syntax from later syntax

## Version 0.3

These are the next design problems, in the order they block a more serious prototype.

1. **`instanceof` groups.** `shape (instanceof Circle || instanceof Rectangle)` needs a type-test grammar, not another relational operator pasted onto a value. Pattern variables (`instanceof Circle c`) need a scope rule. This stays out of 0.2 so it is not a half-implemented operator.

2. **Checked exceptions.** The supplier that stores the subject cannot throw a checked exception. A sound translation has to either stay inside a context that already handles the exception or stop using a supplier. Sneaky-throw is not a substitute; it would make the output lie about the method's throws clause.

3. **Cheaper lowering, measured with JMH.** The 0.2 supplier is correct and, on one workstation, a few tenths of a millisecond slower across two million iterations. That is not a tuning target by itself. If a statement-position temporary or a bytecode lowering is attempted, JMH belongs in the comparison. It was not added in 0.2 because there is only one lowering to measure.

4. **Subjects that are not postfix expressions.** `(a + b) (>= 0)` works. `a + b (>= 0)` does not treat the sum as the subject. Extending the subject leftward through binary operators needs a real precedence walk so `flag && a + b (>= 0)` does not swallow the `&&`.

5. **Comments inside a group.** They are currently inside the replaced span and disappear. A translation that keeps them has to thread trivia from the lexer through the generator.

6. **`javac` integration, as an experiment.** The viable path is still "rewrite, then compile". A `javac` plugin or a `com.sun.source.util.Plugin` would see Java, not GCE, unless the rewrite happens first. Exploring that boundary is useful. Claiming that OpenJDK has accepted the feature would not be.

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
