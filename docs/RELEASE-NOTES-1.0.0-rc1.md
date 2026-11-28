# GCE 1.0.0-rc1

**Status:** release candidate, 2026-11-26. This is not a final 1.0.0 release and it is not production support for changing `javac`. GCE remains a source-to-source translator. JDK 25 compiles the Java it emits.

The license is Apache License 2.0. See `LICENSE`.

## Feature

One condition may contain any number of independent comparison groups. Each group has its own subject and its own list of comparisons. Nothing in the translator hardcodes three variables or the counts 3, 1, and 4.

```java
if (a)(>= 1 && <= 10 && != 5)
        && (b)(== 3)
        && (c)(> 0 && < 100 && != 50 && != 75) {
    System.out.println("here");
}
```

- `a` is compared three times, `b` once, and `c` four times.
- `&&` and `||` between groups use Java precedence, associativity, and short-circuit rules. Parentheses override precedence.
- Each subject is evaluated once per group, and only when that group is reached.
- The same groups are legal in a `while` or `do`/`while` header, in a `return` or other expression, and in the test of a `for` between the semicolons.

## Fix

Before this candidate, each group was already rewritten to its own parenthesized switch expression. For the sample above, the `(` after `if` is the parenthesis around `a`. The `)` that closed the first switch expression also closed the `if`. The next `&&` was then outside the condition, and JDK 25 `javac` reported:

- illegal start of expression
- not a statement
- ';' expected

Wrapping each switch expression in parentheses does not fix that, because those parentheses are what close the header. 1.0.0-rc1 emits the whole chain as one boolean expression inside the header:

```java
if ((switch /* a */) && (switch /* b */) && (switch /* c */)) {
```

Each switch still binds its own subject, so a later group does not reuse an earlier temporary. A condition that already wrapped every term was valid before and stays valid.

## Tests

`MultiGroupConditionTest` compiles the translated programs with JDK 25 and runs them. It covers one comparison, several comparisons of one subject, two and three groups, `&&`, `||`, mixed operators, parentheses, side-effecting subjects, and the previous `age (>= 18 && <= 65)` form. The existing suite is unchanged in intent and still runs under `mvn verify`.

## Limitations

- The translator is not a Java compiler. `javac` still type-checks the output.
- A group is not a constant expression. `case` labels and annotation values that contain a group are rejected.
- `a + b (>= 0)` is a syntax error. The subject of a sum is `(a + b) (>= 0)`.
- Assignment inside a comparison operand is rejected.
- `switch (` and `synchronized (` do not take this header repair. A boolean chain there must already be one enclosed expression.
- Comments inside a replaced span are not copied. Comments outside the span are kept.
- No performance number is part of this candidate.
