# GCE Compiler Prototype

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

The subject is evaluated once. `&&` and `||` still short-circuit. Ordinary Java can sit beside the new syntax:

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

A local variable cannot be declared in the middle of an arbitrary expression without changing when the subject runs. `flag && age (>= 18)` must not read `age` when `flag` is false. Version 0.2 therefore emits a supplier that runs only when the group itself is evaluated:

```java
(((java.util.function.Supplier<Boolean>) () -> {
    var $gce$0 = (age);
    return (($gce$0 >= (18)) && ($gce$0 <= (65)));
}).get())
```

`var` keeps the subject's real type, primitive or reference. A fixed name such as `__gce_value` is not used; the translator picks `$gce$0`, `$gce$1`, … and skips any identifier already present in the file.

The older `dev.gce.runtime.Gce.test` helper is still in the jar for direct calls, but the translator does not emit it. A generic `Supplier`/`Predicate` pair would box primitives and can change the meaning of `==`.

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
Comparison AST  --->  operand expressions re-scanned for nested groups
        |
        v
Java expression using Supplier<Boolean> and var
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
mvn package
```

`mvn test` rewrites sample programs, compiles them with the JDK 25 compiler API, runs them, and compares the output with equivalent ordinary Java.

## Usage

Translate one file. The program writes the second path as UTF-8 and creates its folder:

```bash
java -jar target/gce-compiler-0.2.0.jar Input.java out/Input.java
javac --release 25 out/Input.java
java -cp out Input
```

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

## What version 0.2 implements

- Several comparisons of one subject, with `==`, `!=`, `<`, `<=`, `>`, `>=`
- `&&` and `||`, with Java precedence and left associativity, plus nested parentheses
- Operands that are literals, names, fields, calls, array access, arithmetic, bitwise operators, relational operators, equality, and `?:`
- Comparison with another variable, including `null`, `boolean`, `String`, and enum constants
- Subjects that are names, `this`, `super`, field and method chains, calls with arguments, array access, `new`, and parenthesized expressions
- A parenthesized binary expression as a subject: `(low + high) (>= 0)`
- Ordinary Java mixed with GCE in one expression
- One evaluation of each subject, including subjects nested inside an operand
- Short-circuit of both the group and the surrounding expression
- No rewrite inside comments, string literals, character literals, or text blocks
- Syntax errors that name the line and column

## Current limitations

The translator is not a Java compiler and does not accept every Java program.

- A subject is a postfix expression. `a + b (>= 0)` compares `b`, not the sum. Write `(a + b) (>= 0)`.
- `age (>= 18 < 65)` is `age >= (18 < 65)`, not a range. A range is `age (>= 18 && < 65)`.
- At parenthesis depth zero, `&&` and `||` always separate comparisons. Put them in parentheses when they belong to an operand: `(>= (ready && paid))`.
- `instanceof` is not a GCE operator yet. `value (instanceof String)` is a syntax error. `instanceof` inside a parenthesized operand is ordinary Java and is limited to a type, without a pattern variable.
- Lambdas, method references, assignments, and `switch` expressions are rejected as operands.
- The generated supplier cannot throw a checked exception. A subject or operand that throws one is rejected by `javac`.
- `var` cannot infer a subject whose type is only `null`.
- Comments that occur inside the replaced span, between the subject and the closing parenthesis of the group, are not copied into the output.
- A `>>` or `>>>` token is not split when only part of it closes a type-argument list.
- Translation expands the source. On one JDK 25.0.2 workstation run, 1,000 groups grew from 24,812 characters to 135,482 (about 5.5×) and took about 11 ms after warmup. A 2,000,000-iteration loop was about 0.74 ms in ordinary Java and about 0.87 ms after translation. Those figures are one run, not a JMH result, and they are not a claim that the translated form is faster.

## Roadmap

See [docs/ROADMAP.md](docs/ROADMAP.md). Version 0.3 is the place for `instanceof`, checked-exception transparency, and a realistic look at `javac` integration.

## License

No license is specified yet. Treat the tree as source shared for the experiment until one is added.
