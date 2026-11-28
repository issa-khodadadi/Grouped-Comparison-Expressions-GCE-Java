# GCE Compiler — Grouped Comparison Expressions for Java

Grouped Comparison Expressions (GCE) is an experimental syntax for applying several comparisons to one Java expression without writing that expression again.

This repository is a source-to-source prototype. It rewrites GCE into ordinary Java, then leaves parsing, type checking, and bytecode generation to the JDK 25 `javac`. It is not a change to the Java language and it does not modify the JDK.

## Motivation

A condition often repeats a long receiver:

```java
if (person.getAddress().getHouseNumber() != null
        && person.getAddress().getHouseNumber() >= 0) {
    accept();
}
```

If `getHouseNumber()` has a side effect, repeating it is also a change in behavior, not only a change in length. GCE names the subject once:

```java
if (person.getAddress().getHouseNumber() (!= null && >= 0)) {
    accept();
}
```

The subject is evaluated once. `&&` and `||` still short-circuit. Several subjects can share one condition, and each subject still runs only when that group is reached:

```java
if (a)(>= 1 && <= 10 && != 5)
        && (b)(== 3)
        && (c)(> 0 && < 100 && != 50 && != 75) {
    System.out.println("here");
}
```

`(a)` has three comparisons, `(b)` has one, and `(c)` has four. The `(` after `if` is the first subject's parenthesis; the translator keeps the whole chain inside the `if`. Ordinary Java can sit beside the new syntax:

```java
if (person.getAge() (>= 18 && <= 65)
        && (person.getSalary() > 5000)
        && person.getAddress().getHouseNumber() (!= null && >= 0)) {
    accept();
}
```

`(person.getSalary() > 5000)` is normal Java and is not rewritten.

## What the translator emits

The readable equivalent of `age (>= 18 && <= 65)` is:

```java
var ageValue = age;
ageValue >= 18 && ageValue <= 65
```

A local variable cannot be declared in the middle of an arbitrary expression without changing when the subject runs. `flag && age (>= 18)` must not read `age` when `flag` is false. Version 1.0.0-rc1 binds the subject inside a switch expression. The switch is evaluated only when the surrounding expression reaches it, and the block is part of the enclosing method, so it is not a lambda:

```java
(switch (0) { default -> { var $gce$0 = (age); yield (($gce$0 >= (18)) && ($gce$0 <= (65))); } })
```

`var` keeps the subject type, so `int == Integer` stays numeric and `Integer == Integer` stays identity. In the enclosing method, a local used by the subject or an operand does not have to be effectively final, and a checked exception propagates. A group written inside a lambda the programmer wrote still follows Java's lambda rules. The translator picks `$gce$0`, `$gce$1`, … and skips any identifier already present in the file. A subject that is a `null` literal, including `(null)` and `((null))`, is bound as `Object`. The decision uses the token stream, not a string comparison.

`dev.gce.runtime.Gce.test` is still in the jar for direct calls. The translator does not emit it. That helper boxes its subject, and after boxing `==` can disagree with primitive equality.

## Architecture

```
Java source with GCE
        |
        v
Unicode-escape decode and lexer
        |
        v
Recognizer: postfix subject + comparison group
        |
        v
Comparison AST  --->  subjects and operands re-scanned for nested groups
        |
        v
Java expression: switch (0) { default -> { var temp = subject; yield predicate; } }
        |
        v
Unchanged surrounding source
        |
        v
javac (JDK 25)  --->  bytecode
```

Lexical analysis, the comparison grammar, the AST, and code generation are separate packages under `dev.gce.compiler`. Details and the reason a stock Java parser is not used to read GCE source are in [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md). The language rules are in [docs/GCE-LANGUAGE-SPECIFICATION.md](docs/GCE-LANGUAGE-SPECIFICATION.md).

## Requirements

- JDK 25, including `javac` (`ToolProvider.getSystemJavaCompiler()`). The tests compile translated programs in memory.
- Maven 3.8 or newer.

## Build

```bash
mvn test
mvn clean verify
```

`mvnw` / `mvnw.cmd` is the Maven Wrapper (Maven 3.9.9). GitHub Actions runs `bash mvnw -B verify` on JDK 25.

`mvn test` rewrites sample programs, compiles them with the JDK 25 compiler API, runs them, and compares the output with equivalent ordinary Java.

## Usage

Translate one file. The program writes the second path as UTF-8 and creates its folder:

```bash
java -jar target/gce-compiler-1.0.0-rc1.jar Input.java out/Input.java
javac --release 25 out/Input.java
java -cp out Input
```

Translated source uses only the JDK. It does not call `GceEval`, so the compiler jar is not required on the `javac` or `java` classpath. `dev.gce.runtime.Gce.test` remains in the jar for a direct call and still boxes.

The output file name has to match a `public class` inside it. `public class Input` cannot be compiled from `Output.java`.

The second path is written as UTF-8 by the program itself. In Windows PowerShell, `>` saves stdout as UTF-16, and `javac` then reports `illegal character: '\u0000'`.

With no arguments, `dev.gce.Main` prints a short demonstration.

From code:

```java
String javaSource = new dev.gce.compiler.GceTranspiler().transpile(gceSource);
```

Malformed GCE throws `dev.gce.compiler.GceSyntaxException`. The message includes a 1-based line and column in the original file.

A manual timing program, not part of the test run:

```bash
mvn -q test-compile
java -cp target/classes;target/test-classes dev.gce.compiler.GceBenchmark
```

On Windows the classpath separator is `;`. On Unix it is `:`.

## What version 1.0.0-rc1 implements

This is a release candidate, not a final 1.0.0.

- Several comparisons of one subject, with `==`, `!=`, `<`, `<=`, `>`, `>=`, and `instanceof` (a type pattern, a record pattern, or `_`)
- Any number of independent groups in one condition, joined by `&&` or `||`, including an `if` or `while` whose parenthesis is the first subject
- `&&` and `||`, with Java precedence and left associativity, plus nested parentheses
- Operands that are literals, names, fields, calls, array access, arithmetic, bitwise operators, relational operators, equality, and `?:`
- Comparison with another variable, including `null`, `boolean`, `String`, and enum constants
- Subjects that are names, `this`, `super`, field and method chains, calls with arguments, array access, `new`, and parenthesized expressions
- A parenthesized binary expression as a subject: `(low + high) (>= 0)`
- Ordinary Java mixed with GCE in one expression
- One evaluation of each subject, including a group nested in an operand or in a subject call, parenthesized subject, or subject index
- A subject that is a reassigned local, a `for`-loop index, or `array[i]` while `i` changes
- Short-circuit of both the group and the surrounding expression
- No rewrite inside comments, string literals, character literals, or text blocks
- Syntax errors that name the line and column

## Current limitations

The translator is not a Java compiler and does not accept every Java program.

- A subject is a postfix expression, plus a directly attached unary `+`, `-`, `!`, or `~`. `(a + b) (>= 0)` compares the sum. `a + b (>= 0)` is a syntax error. It is not rewritten as a comparison of `b`.
- The translated group is not a JLS constant expression. A group in a `case` label or an annotation value is a syntax error. A `static final` field initialized with a group is not a constant variable.
- `age (>= 18 < 65)` is `age >= (18 < 65)`, not a range. A range is `age (>= 18 && < 65)`.
- At parenthesis depth zero, `&&` and `||` always separate comparisons. Put them in parentheses when they belong to an operand: `(>= (ready && paid))`.
- Casts, prefix and postfix `++`/`--`, qualified `holder.new Inner()`, lambdas, method references, and `switch` expressions are accepted as subjects or operands. An assignment in an operand is still a syntax error.
- In the enclosing method, a checked exception from the subject or an operand propagates, and the local need not be effectively final. Inside a lambda the programmer wrote, Java's capture and checked-exception rules still apply.
- Comments outside the replaced span are kept. A comment inside the span, such as `age /*inside*/ (>= 18)`, is not copied.
- A `>>` or `>>>` token is not split when only part of it closes a type-argument list. A shift written as an operand, such as `n (>= flags >> 1)`, is kept.
- More than 64 nested groups, parentheses, unary operators, or conditional operators is a syntax error.
- A chain after `switch (` or `synchronized (` is not pulled into that header. Write the chain where a boolean expression is already legal, or inside parentheses that already enclose every term.
- Version 1.0.0-rc1 does not publish a performance number. `GceBenchmark` is a manual `nanoTime` program and is not JMH. It is not a production JDK change.

## Roadmap

See [docs/ROADMAP.md](docs/ROADMAP.md). Integration into `javac` is not part of this release.

## License

See [LICENSE](https://github.com/issa-khodadadi/Grouped-Comparison-Expressions-GCE-Java/blob/main/LICENSE)