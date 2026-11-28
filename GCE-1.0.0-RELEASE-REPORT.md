# GCE 1.0.0 release report

> Historical record of the build labeled 1.0.0 on 2026-11-24.
> The current artifact is the release candidate **1.0.0-rc1**, not a final 1.0.0.
> See `CHANGELOG.md` and `docs/RELEASE-NOTES-1.0.0-rc1.md`.

Date of the clean build: 2026-11-24, 16:52:25 +03:30. JDK observed on this workstation: the compiler used by Maven is release 25 (`Build-Jdk-Spec: 25`). This report records the release evidence supplied with the project archive. The originally supplied archive contained line-ending-only working-tree differences in several files; the release candidate normalizes the Unix `mvnw` script issue by removing the incomplete wrapper rather than retaining a broken launcher.

## Verdict

The translator earns the 1.0.0 version for the behavior this repository implements and tests. It is a source-to-source preprocessor in front of unmodified `javac`. It is not a Java language change and not an OpenJDK candidate.

The project license is Apache License, Version 2.0. The root `LICENSE` file contains the license text, the README identifies the license, and `pom.xml` declares the license metadata. No `NOTICE` file is required by the current project contents: the only declared library dependency is JUnit in test scope, and Maven build plugins are not bundled into the application JAR.

## What changed

### Parenthesized null

`JavaCodeGenerator.generate` no longer compares the subject text with `"null"`. `GceRecognizer.isNullLiteral` accepts a subject whose tokens are one `null` and any number of parentheses. The binding is `Object`.

Checked forms, all compiled and executed against ordinary Java in `GceEndToEndTest.parenthesizedNullSubjectsMatchJava`:

- `null (== null)`
- `(null) (== null)`
- `((null)) (== null)`
- `value (== null)`
- `value (!= null)`

An independent program compiled with `javac --release 25` and no GCE jar contains `Object $gce$1 = ((null));` and printed `true` for that comparison.

### Ambiguous subjects

`GceRecognizer.rejectAmbiguousSubject` rejects a binary, shift, or bitwise operator immediately before the subject when that operator has an expression to its left. `a + b (>= 0)` is a `GceSyntaxException`. It is not rewritten as a comparison of `b`.

`(a + b) (>= 0)` is still the sum. `flag && age (>= 18)` is still two expressions joined by Java `&&`. A directly attached unary `+`, `-`, `!`, or `~` is included in the subject (`-n (>= 0)`, `!ready (== false)`).

`GceTranspilerTest.unparenthesizedBinarySubjectsAreRejected` covers `+ - * / % << >> >>> & | ^` and `flag && a + b (>= 0)`. `GceEndToEndTest.parenthesizedArithmeticSubjectIsEvaluatedOnce` runs `(tick(2) + tick(3)) (>= 5 && <= 5)` and checks the counter is 2, then checks a field subject and an array-index subject with the same counter.

### Constant-expression contexts

The switch lowering is not a JLS constant expression. `GceRecognizer.rejectConstantContext` reports that itself when the group is in a `case` label or an annotation value. The translator does not emit a switch that later fails with `constant expression required`.

A `static final` initializer that contains a group is not rejected. The field can still compile, and it is not a constant variable. That limitation is stated in the language specification. There is no implementation of GCE as a constant expression.

### Patterns, lambdas, and semantics

`ComparisonParser.parseInstancePattern` accepts `final`, a type pattern, `_`, and a record pattern, including nested parentheses. The operand text is copied into `temp instanceof <pattern>`. `GceEndToEndTest.recordPatternsMatchJava` compiles and runs `Point(int x, int y)`, `String _`, `String s`, `Box(Point(int x, int y))`, and `final Point p`.

`GceEndToEndTest.lambdasAndMethodReferencesMatchJava` compiles and runs:

- `x -> x (>= 0)`
- `(x, y) -> x (>= y)`
- `(String x) -> x.length() (> 0)`
- `x -> { return x (>= 0); }`
- `a -> b -> a (>= 0)`
- `String::toUpperCase` beside a group

Those lambdas follow Java's own capture and checked-exception rules. The README and the specification say so. GCE does not add an effectively-final or checked-exception restriction in an ordinary enclosing method.

The list-allocation line in the large behavior test now increments a counter. The ordinary Java twin calls that method once. Both programs print the counter.

### Comments and shifts

Comments outside the replaced span are kept. Comments inside the span are not copied. `GceTranspilerTest.commentsOutsideAGroupStayAndCommentsInsideTheSpanDoNot` locks that policy. `>>` and `>>>` are still not split across a type argument and an operator. A shift operand is preserved: `n (>= flags >> 1)`.

## Fixed issues

| Issue | Where |
| --- | --- |
| `(null)` inferred `var` from a null initializer | `GceRecognizer.isNullLiteral`; `JavaCodeGenerator.generate` |
| `a + b (>= 0)` silently compared `b` | `GceRecognizer.rejectAmbiguousSubject` |
| A group in a `case` label failed later inside `javac` | `GceRecognizer.rejectConstantContext` |
| The same for an annotation value | `GceRecognizer.isAnnotationArgument` |
| README claimed effectively-final freedom without the lambda boundary | `README.md` |
| README still pointed `instanceof` at a future 0.3 | `README.md` |
| Specification section 11 labeled the switch as the 0.2.1 shape | `docs/GCE-LANGUAGE-SPECIFICATION.md` |
| The list-size twin could not catch a second evaluation | `GceEndToEndTest` `freshList` counter |
| Record patterns and `_` were syntax errors | `ComparisonParser.parseInstancePattern` |
| Multi-parameter lambdas, nested lambdas, and method references had no repository test | `GceEndToEndTest.lambdasAndMethodReferencesMatchJava` |

## Remaining limitations

- The subject of an unparenthesized operator expression is not parsed as the whole expression. The program is rejected. Extending the subject leftward through `+` without also swallowing `&&` is future work.
- A group is not a constant expression. `case` labels and annotation values are rejected. A `static final` field initialized with a group is not a constant variable.
- Comments inside the replaced span are dropped.
- A `>>` or `>>>` token that must be split between a type argument and an operator is rejected.
- Pattern-variable scope is whatever Java gives the generated `instanceof` expression. A pattern variable is not given extra scope across later GCE terms.
- Guard patterns (`when`) are not a separate tested form.
- User-written lambdas still capture and still cannot throw a checked exception unless the functional interface allows it.
- `dev.gce.runtime.Gce` and `GceEval` remain in the jar for direct calls. Generated code does not call them. `Gce.test` still boxes.
- No JMH result is part of this release. The numbers in `docs/ARCHITECTURE.md` are one historical 0.3.0 `nanoTime` run.
- The body of `docs/GCE-TECHNICAL-PAPER.md` is the 0.2.1 paper. The banner says so. The current design is the specification and the changelog.

## Tests and commands

```text
mvn clean verify
```

Result: `Tests run: 49, Failures: 0, Errors: 0, Skipped: 0`. `BUILD SUCCESS`. Jar: `target/gce-compiler-1.0.0.jar`.

Counts: `GceEndToEndTest` 23, `GceTranspilerTest` 20, `LexerTest` 6. The 0.3.0 baseline on this tree was 39.

Independent check, with no GCE jar on the compiler classpath:

```text
java -jar target/gce-compiler-1.0.0.jar NoJar.java out/NoJar.java
javac --release 25 -d classes out/NoJar.java
java -cp classes NoJar
```

The generated source uses `var` for `age`, `Object` for `(null)`, `var` for `(2 + 3)`, and `instanceof Point(int x, int y)`. The process printed `truetruetruetrue`.

Manifest of `target/gce-compiler-1.0.0.jar`:

```text
Main-Class: dev.gce.Main
Build-Jdk-Spec: 25
Created-By: Maven JAR Plugin 3.4.2
```

`META-INF/maven/dev.gce/gce-compiler/pom.properties`: `groupId=dev.gce`, `artifactId=gce-compiler`, `version=1.0.0`.

## License

The project is licensed under the Apache License, Version 2.0. The complete text is in the root `LICENSE` file. `pom.xml` declares the same license and the README links to it. No copyright-holder name or year has been added because those details were not supplied; the standard Apache license text is otherwise retained.


## Packaging review note

The project retains `mvnw` and `mvnw.cmd`.
The Maven Wrapper configuration and launcher files should be
verified before release to ensure that both Unix and Windows
environments can invoke Maven successfully.

## Independent review environment limitation

The review container used OpenJDK 21 and did not have Maven installed. It therefore could not rerun `mvn clean verify` or independently reproduce the JDK 25 compile-and-run check. The 49-test result and standalone `javac --release 25` result above are retained from the supplied release report and should be confirmed by the updated JDK 25 GitHub Actions workflow before creating the public release tag.
