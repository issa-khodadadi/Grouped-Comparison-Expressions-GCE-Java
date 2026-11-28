# Architecture

Version 1.0.0-rc1 is a release candidate of a preprocessor in front of unmodified `javac`. It does not patch the JDK, register a `javac` plugin, or claim that the compiler understands GCE. It is not a final 1.0.0.

## Pipeline

```
source text
    |  UnicodeDecoder   (JLS 3.3, one pass, not recursive)
    v
Lexer                   (tokens; comments and whitespace are not tokens)
    |  offsets point back into the original spelling
    v
GceRecognizer           (postfix subject immediately before a comparison group)
    |
    v
ComparisonParser        (group AST + operand expression check)
    |  operand spans that contain '(' are translated again
    v
JavaCodeGenerator       (switch expression, var temp = subject, yield predicate)
    |
    v
original source with only those spans replaced
    |
    v
javac --release 25
```

`GceTranspiler.transpile` is the public entry point. `Main` reads a file or prints a demo.

## Packages

| Package | Role |
| --- | --- |
| `dev.gce.compiler` | Transpiler, syntax exception, source positions |
| `dev.gce.compiler.lexer` | Tokens, keywords, unicode escapes, lexer |
| `dev.gce.compiler.parser` | Group grammar, subject scan, bracket matching |
| `dev.gce.compiler.ast` | `&&`, `||`, parentheses, and one relational test |
| `dev.gce.compiler.codegen` | Java expression rendering |
| `dev.gce.runtime` | `Gce.test` for direct calls; it boxes. `GceEval` remains in the jar and is not emitted |

The AST is a sealed interface. Code generation walks that tree. It does not concatenate boolean operators while parsing, so precedence is a property of the tree (`&&` nodes under `||` nodes) and of the parentheses the generator prints.

## Why the input is not fed to a Java parser

A grouped comparison is not a Java program. JavaParser, Eclipse JDT, and `javac`'s own parser will reject it before any visitor runs. The integration strategy is:

1. A lexer that understands the lexical contexts where a parenthesis must not be a group: strings, text blocks, characters, and comments.
2. A local decision: a parenthesis is a group when its contents start with a comparison operator.
3. Emit legal Java.
4. Let JDK 25 `javac` parse, type-check, and generate bytecode.

A full Java grammar is not required to copy the unaffected text. The lexer still has to be right about token boundaries, or a group hiding inside a string would be rewritten and a `>>` in a generic type could be mis-counted. Numeric literals, text blocks, and unicode escapes are handled for that reason. The operand parser then checks the expression subset the specification allows, so `age (>= 18 19)` fails in the translator instead of being passed along as broken Java.

## Subject scan

The recognizer walks tokens from left to right. At each token that can start a postfix expression it scans a subject: name, literal, `this`, `super`, `new`, a balanced `(...)`, then `.name`, calls, and `[...]`. A call whose `(` is a comparison group stops the subject. The group parser then builds the AST. The scan continues after that group, so `a (>= 1) && b (>= 2)` becomes two independent translations.

When the `(` after `if` or `while` is itself the first subject's `(`, that parenthesis would also close the first generated switch expression. A later `&&` or `||` would then sit outside the header, which `javac` rejects. The recognizer treats the following expression as part of the same condition and emits one parenthesized boolean expression, so the header is `if ((groupA) && (groupB))`. Each group is still its own switch expression. A condition that already encloses every term, and a chain in an ordinary expression or in a `for` test between semicolons, is left as separate in-place replacements. `switch` and `synchronized` selectors are not boolean headers and are not rewritten this way.

Method arguments and array indexes are consumed by matching `()`, `[]`, and `{}`. Their interior is not a second Java parser. When a postfix expression is itself a group subject, the recognizer commits that whole span and does not visit the tokens inside it again. The subject text is therefore translated by a second pass before it is placed in the switch expression. That pass is what rewrites a group inside a call argument, a parenthesized expression, or an array index. A group that is not inside a larger subject is still found by the left-to-right walk.

## One evaluation without a leaked local

Hoisting `var tmp = subject;` above an `if` is wrong:

```java
if (flag && subject (>= 0)) { }
```

The hoist would evaluate `subject` even when `flag` is false.

A statement-level local would run even when `&&` skips the group. A lambda would capture locals and block checked exceptions. A switch expression is an expression whose block belongs to the enclosing method:

```java
(switch (0) { default -> { var $gce$0 = (subject);
        yield ($gce$0 >= (18)) && ($gce$0 <= (65)); } })
```

`false &&` does not evaluate that switch. `var` keeps the subject's type, so an `int` does not become an `Integer`. `Gce.test(Supplier, Predicate)` was the 0.1 approach and still boxes. The translator does not emit it.

The temporary name is `$gce$` plus a number. Every identifier in the file is reserved first, then each generated name. An operand that mentions `$gce$0` still refers to the user's variable. An operand that mentions another local uses that local directly. It does not have to be effectively final.

## Nested groups

Operand offsets, and the subject span, are slices of the current fragment. If the slice contains `(`, it is translated with the same reserved-name set and with positions shifted back to the original file. `flag (== (next() (>= 1)))` therefore becomes an outer switch whose predicate contains an inner switch. `id(age() (>= 18)) (>= 1)` rewrites the subject first, so `age()` is the inner call. Groups, parentheses, unary operators, and conditional operators are limited to 64 levels. The parser checks that limit before it recurses.

## Tests

`GceTranspilerTest` and `LexerTest` cover recognition, precedence, collisions, and diagnostics.

`GceEndToEndTest` writes the translated source to the JDK 25 compiler API (`--release 25`), loads the classes in memory, runs `main`, and compares the printout with a separate ordinary Java program that uses the same single-evaluation behavior. One case also checks that `int (== null)` is still illegal after translation, which is the boxing regression the old helper would have hidden.

`GceBenchmark` is not a test. It is a `main` for a manual measurement.

## Measurement

JMH is not a dependency. The table below is one historical 0.3.0 workstation run. It was not repeated for 1.0.0 and is not a release claim. The 1.0.0 lowering is the same switch expression for ordinary subjects.

| Measurement | Result on one JDK 25.0.2 / Windows run, 24 November 2026 |
| --- | --- |
| 1,000 groups, mean of 20 translations after 5 warmups | 10.4 ms |
| Characters out / characters in for that input | 4.33× (24,812 → 107,482) |
| 2,000,000 iterations, ordinary `n >= 18 && n <= 65`, mean of 5 after 3 warmups | 0.745 ms |
| The same loop after translation, same timing | 0.751 ms |
| Both loop results | 750,000 |

Reproduce with:

```bash
mvn -q test-compile
java -cp target/classes;target/test-classes dev.gce.compiler.GceBenchmark
```

`n` in that loop is `i & 127`, assigned once per iteration and not assigned again inside the group. The two loop times differ by less than 0.01 ms on this single run. That is not evidence that the lowering is free, and it is not a JMH result. Both loops returned 750,000.

## Build

Maven compiles with `--release 25`. Tests need a JDK, not a JRE, because they call `javax.tools.ToolProvider`. There is no third-party compile dependency.
