# Architecture

Version 0.2 is a preprocessor in front of unmodified `javac`. The prototype does not patch the JDK, register a `javac` plugin, or claim that the compiler understands GCE.

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
JavaCodeGenerator       (Supplier<Boolean> + var)
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
| `dev.gce.runtime` | Optional `Gce.test` helper. The translator does not call it |

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

Method arguments and array indexes are consumed by matching `()`, `[]`, and `{}`. Their interior is not a second Java parser. A group nested in an argument is still found, because the scan visits every token and only jumps forward across a group it has already translated.

## One evaluation without a leaked local

Hoisting `var tmp = subject;` above an `if` is wrong:

```java
if (flag && subject (>= 0)) { }
```

The hoist would evaluate `subject` even when `flag` is false.

A block is not an expression in Java, so the temporary cannot be declared at the use site. The generated supplier is an expression, it runs only when evaluated, and `var` inside it keeps the static type:

```java
(((java.util.function.Supplier<Boolean>) () -> {
    var $gce$0 = (subject);
    return ($gce$0 >= (18)) && ($gce$0 <= (65));
}).get())
```

`Gce.test(Supplier, Predicate)` was the 0.1 approach. The predicate's parameter type is erased to a reference type, so a primitive subject is boxed. `int == Integer` then becomes reference equality for some values (for example two boxed `1000`s) instead of numeric equality. Version 0.2 does not emit that call. The helper remains for callers who already hold a supplier and accept boxing; its Javadoc says so.

The temporary's name is `$gce$` plus a number. Every identifier in the file is reserved first, then each generated name. An operand that mentions `$gce$0` still refers to the user's variable.

## Nested groups

Operand offsets are slices of the current fragment. If the slice contains `(`, it is translated with the same reserved-name set and with positions shifted back to the original file. `flag (== (next() (>= 1)))` therefore becomes an outer supplier whose operand is an inner supplier. Depth is capped at 32.

## Tests

`GceTranspilerTest` and `LexerTest` cover recognition, precedence, collisions, and diagnostics.

`GceEndToEndTest` writes the translated source to the JDK 25 compiler API (`--release 25`), loads the classes in memory, runs `main`, and compares the printout with a separate ordinary Java program that uses the same single-evaluation behavior. One case also checks that `int (== null)` is still illegal after translation, which is the boxing regression the old helper would have hidden.

`GceBenchmark` is not a test. It is a `main` for a manual measurement.

## Measurement

JMH is not a dependency. One run is not a distribution, and the prototype's question was whether the lowering is correct before tuning it. On the JDK 25.0.2 workstation used for this release:

| Measurement | Result |
| --- | --- |
| 1,000 groups, mean of 20 translations after 5 warmups | about 11 ms |
| Characters out / characters in for that input | about 5.5× (24,812 → 135,482) |
| 2,000,000 iterations, ordinary `n >= 18 && n <= 65` | about 0.74 ms |
| The same loop after translation | about 0.87 ms |
| Both loop results | 750,000 |

Reproduce with:

```bash
mvn -q test-compile
java -cp target/classes;target/test-classes dev.gce.compiler.GceBenchmark
```

The translated loop was slower in that run. Nothing here claims a speedup. A later lowering can avoid the supplier when a cheaper form has the same evaluation rules.

## Build

Maven compiles with `--release 25`. Tests need a JDK, not a JRE, because they call `javax.tools.ToolProvider`. There is no third-party compile dependency.
