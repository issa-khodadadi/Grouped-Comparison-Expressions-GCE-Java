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

A local variable cannot be declared in the middle of an arbitrary expression without changing when the subject runs. `flag && age (>= 18)` must not read `age` when `flag` is false. Version 0.2.1 therefore calls a small runtime method. The subject is an argument, so it runs only when that call runs:

```java
(dev.gce.runtime.GceEval.of((age)).is($gce$0 ->
        (($gce$0 >= (18)) && ($gce$0 <= (65)))))
```

`of` is overloaded for `boolean`, `int`, `long`, `double`, and reference types. A primitive stays unboxed, so `int == Integer` is numeric and `Integer == Integer` stays identity. `char`, `byte`, and `short` widen to `int`. `float` widens to `double`. The translator picks `$gce$0`, `$gce$1`, … and skips any identifier already present in the file.

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
Java expression: GceEval.of(subject).is(temp -> predicate)
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
java -jar target/gce-compiler-0.2.1.jar Input.java out/Input.java
javac --release 25 -cp target/gce-compiler-0.2.1.jar out/Input.java
java -cp "out;target/gce-compiler-0.2.1.jar" Input
```

On Unix the classpath separator is `:`. The translated file calls `dev.gce.runtime.GceEval`, so the jar has to be on the classpath at compile time and at run time.

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

## What version 0.2.1 implements

- Several comparisons of one subject, with `==`, `!=`, `<`, `<=`, `>`, `>=`
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

- A subject is a postfix expression. `a + b (>= 0)` compares `b`, not the sum. Write `(a + b) (>= 0)`.
- `age (>= 18 < 65)` is `age >= (18 < 65)`, not a range. A range is `age (>= 18 && < 65)`.
- At parenthesis depth zero, `&&` and `||` always separate comparisons. Put them in parentheses when they belong to an operand: `(>= (ready && paid))`.
- `instanceof` is not a GCE operator yet. `value (instanceof String)` is a syntax error. `instanceof` inside a parenthesized operand is ordinary Java and is limited to a type, without a pattern variable.
- Casts, prefix and postfix `++`/`--`, and qualified `holder.new Inner()` are rejected when they are the GCE subject or operand. The same tokens in ordinary Java, including inside a subject call's argument list, are left unchanged.
- Lambdas, method references, assignments, and `switch` expressions are rejected as operands.
- A checked exception from the subject propagates. A checked exception from an operand does not: the predicate is a lambda, and `javac` rejects it.
- An operand that names a local variable still captures that variable. The local has to be effectively final. The subject does not.
- Comments that occur inside the replaced span, between the subject and the closing parenthesis of the group, are not copied into the output.
- A `>>` or `>>>` token is not split when only part of it closes a type-argument list.
- More than 64 nested groups, parentheses, unary operators, or conditional operators is a syntax error.
- Translation expands the source and allocates one small chain object whenever a group is evaluated. A single JDK 25.0.2 workstation run of the older 0.2 supplier, not of this lowering, grew 1,000 groups from 24,812 characters to 135,482 and took about 11 ms after warmup. Those figures are not a JMH result and are not a claim about 0.2.1.

## Roadmap

See [docs/ROADMAP.md](docs/ROADMAP.md). Version 0.3 is the place for `instanceof`, checked-exception transparency, and a realistic look at `javac` integration.

## License

See [LICENSE](https://github.com/issa-khodadadi/Grouped-Comparison-Expressions-GCE-Java/blob/main/LICENSE)
