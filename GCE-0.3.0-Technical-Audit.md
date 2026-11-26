# GCE 0.3.0 — Independent Technical Audit

**Scope.** The tree at `gce-compiler` as inspected on 24 November 2026. No compiler source was modified. Claims below are taken from the current files, from `mvn clean verify`, from `jar tf` / `pom.properties`, or from a throwaway probe that called `GceTranspiler` and then `javac --release 25` with no GCE jar on the classpath. The probe lived outside the repository.

**JDK used for this audit.** `java -version` reported `25.0.2` (build `25.0.2+10-LTS-69`). Maven was Apache Maven 3.8.3, as printed by the verify log's plugin path `C:\apache-maven-3.8.3`.

**What this audit is not.** It is not a review of OpenJDK, and passing tests are not treated as proof of Java compatibility.

---

## 1. Lambda lowering architecture

### 1.1 Where parsing and lowering live

| Step | File | Method |
| --- | --- | --- |
| Entry | `src/main/java/dev/gce/compiler/GceTranspiler.java` | `transpile` (line 27), `rewrite` (line 32) |
| Find a group | `src/main/java/dev/gce/compiler/parser/GceRecognizer.java` | `recognize` (line 39), `translate` (line 109) |
| Parse the group and operands | `src/main/java/dev/gce/compiler/parser/ComparisonParser.java` | `parseGroup` (line 132), `parseTerm` (line 167), `parseOperand` (line 208) |
| Emit Java | `src/main/java/dev/gce/compiler/codegen/JavaCodeGenerator.java` | `generate` (line 21) |

`translate` rewrites the subject slice, picks a name, then calls the generator:

```109:117:src/main/java/dev/gce/compiler/parser/GceRecognizer.java
    private Replacement translate(int subjectToken, int groupToken) {
        parser.jump(groupToken);
        ComparisonNode condition = parser.parseGroup();
        int subjectStart = tokens.get(subjectToken).start();
        int subjectEnd = tokens.get(groupToken - 1).end();
        int rangeEnd = tokens.get(parser.position() - 1).end();
        String subject = operands.apply(subjectStart, subjectEnd);
        String temp = freshName();
        return new Replacement(subjectStart, rangeEnd, generator.generate(subject, condition, temp, operands));
    }
```

The 0.3.0 emitter is only this:

```21:27:src/main/java/dev/gce/compiler/codegen/JavaCodeGenerator.java
    public String generate(String subject, ComparisonNode condition, String temp,
                           BiFunction<Integer, Integer, String> operands) {
        String predicate = emit(condition, temp, operands);
        String binding = "null".equals(subject.strip())
                ? "Object " + temp + " = (" + subject + ");"
                : "var " + temp + " = (" + subject + ");";
        return "(switch (0) { default -> { " + binding + " yield " + predicate + "; } })";
    }
```

### 1.2 What the previous lambda lowering was

The old generator method is **not in this tree**. The retained evidence is:

- `CHANGELOG.md` lines 16–22: version 0.2.1 passed the subject to `GceEval.of` and put the comparisons in `is`. Operands stayed inside that predicate lambda, so a checked exception from an operand, or an operand naming a local that was not effectively final, was rejected by `javac`.
- `CHANGELOG.md` line 35: version 0.2 stored the subject in `var` inside a `Supplier<Boolean>`.
- `src/main/java/dev/gce/runtime/GceEval.java` lines 9–23: the class javadoc still describes `GceEval.of(subject).is(temp -> predicate)` and states that version 0.3.0 does not emit it.

A 0.2.1 group, as that javadoc and changelog describe it, had this shape:

```java
(dev.gce.runtime.GceEval.of((age)).is($gce$0 ->
        (($gce$0 >= (18)) && ($gce$0 <= (65)))))
```

That string is the form documented in those files. It is not a dump of a generator that still exists.

### 1.3 What 0.3.0 emits

Probe input `age (>= 18 && <= 65)` produced exactly:

```java
(switch (0) { default -> { var $gce$0 = (age); yield (($gce$0 >= (18)) && ($gce$0 <= (65))); } })
```

`containsGceRuntime` for that output was `false`. The same probe flag was `false` for every sample that translated.

### 1.4 Why a reassigned local is legal now

The `switch` rule block is part of the enclosing method. It is not a lambda and not an anonymous class, so a local it names is not captured. After the switch, that local may be assigned again.

Evidence that this compiles and runs, without the GCE jar:

`NoJar.java` contained `j` inside the generated switch and `j = 2` after it. `javac --release 25 NoJar.java` succeeded with no classpath entry for `gce-compiler`. `java NoJar` printed `false2true` (`age` was 25, `j` was 0 during the comparison, then `j` printed as 2, then `read() >= 0` printed `true`).

The suite test that locks the same rule against a handwritten twin is `operandThatUsesAReassignedLocalMatchesJava` in `GceEndToEndTest.java` lines 238–264. Surefire ran that class: 19 tests, 0 failures (section 8).

### 1.5 How checked exceptions propagate

`read()` appears in the switch block, which is still the enclosing method, so the method's `throws` clause is what `javac` checks.

- `checkedExceptionFromSubjectPropagates` and `checkedExceptionInOperandPropagates` (`GceEndToEndTest.java` lines 194–236) compare that form with ordinary Java. Both were part of the 19 passing end-to-end tests.
- `NoJar.viaGroup` is a method that `throws Exception` and whose body is the generated switch around `read()`. `javac --release 25` accepted the file. This audit did **not** execute `viaGroup`; compilation is what was observed.

If the same call sits inside a **user** lambda, the switch is inside that lambda. `javac` then reports the checked exception against the lambda, not against the enclosing method. See section 2.

### 1.6 Subject evaluation and short-circuit

Probe input `false && next() (>= 0)` produced:

```java
false && (switch (0) { default -> { var $gce$0 = (next()); yield ($gce$0 >= (0)); } })
```

`next()` is inside the right operand of `&&`. Java does not evaluate that operand when the left operand is `false`. The subject is also not copied onto each comparison; it is bound once and the yield expression uses `$gce$0`.

The executed test for a skipped group is `groupInsideMethodArgumentOfASubjectRunsOnce` (lines 418–450): after `calls = 0`, `false && id(age() (>= 18)) (>= 0)` is compared with a twin that does not call `age` or `id`. That test passed inside the 19.

### 1.7 Semantic differences that remain

| Case | What happened in this audit |
| --- | --- |
| `a + b (>= 0)` | Output was `a + (switch ... var $gce$0 = (b) ...)`. The sum is not the subject. |
| `(null) (== null)` | Output uses `var $gce$0 = ((null))`. `javac` rejected it: `cannot infer type for local variable $gce$0`. Bare `null (== null)` uses `Object` and is the case the end-to-end test runs. |
| Constant context | `case age >= 18 ? 1 : 0:` compiled (`CaseOrdinary`). The same comparison after lowering did not: `constant expression required` (`CaseGce.java:5`). |
| User lambda | A reassigned local or a checked exception **inside the lambda** is still illegal. The switch does not lift the expression out of the lambda. |
| Comments in the replaced span | `age /*c*/ (>= 18)` lost `/*c*/`. |

---

## 2. Lambda expression support

"Parser accepts" and "compiled and matched Java" are different. The table uses that distinction.

| Case | Test in the repository | Generated shape observed this audit | `javac --release 25` | Runtime vs ordinary Java |
| --- | --- | --- | --- | --- |
| Implicit lambda, GCE in the body | `lambdaBodiesCanContainGroups`, `GceEndToEndTest.java` 340–369: `filter(x -> x (>= 0))` | The suite compares stdout with `x >= 0`. This audit did not print that test's generated source separately. | The test passed, so the translated class compiled and ran. | Matched the twin. The twin is `x >= 0`, which is single-evaluation for a lambda parameter. |
| Explicitly typed lambda | Same method, lines 347–348: `filter((Integer x) -> x (>= 2))` | Not printed separately. | Passed inside the 19. | Matched `x >= 2`. |
| Block-bodied lambda | Same method, line 346: `filter(x -> { return x (>= 2); })` | Not printed separately. | Passed inside the 19. | Matched the block twin. |
| Lambda as a method argument | The three `filter(...)` calls above. | — | Passed inside the 19. | Matched. |
| Multiple parameters | **No repository test.** | Probe: `items.stream().filter((x, y) -> x (>= y))` became `filter((x, y) -> (switch (0) { default -> { var $gce$0 = (x); yield ($gce$0 >= (y)); } }))` | `MultiLambda.java` (a `BiPredicate<Integer,Integer>` with that body) compiled. | `java MultiLambda` printed `truefalse`, i.e. `2 >= 1` then `1 >= 2`. **Not** a committed test. |
| Nested lambdas | **No repository test.** | Probe: `f(a -> b -> a (>= 0))` kept both arrows and wrapped only `a (>= 0)`. | `NestedLambda.java` compiled. | Printed `truefalse` for `3 >= 1` and `1 >= 3`. **Not** a committed test. |
| Method reference beside a GCE | `acceptsPreviouslyRejectedExpressionForms` only asserts the fragment `target (>= String::valueOf)` **contains** `String::valueOf`. It does not compile it. | Probe: `list.stream().map(String::toUpperCase).filter(s -> s.length() (>= 1))` kept `String::toUpperCase` and wrapped `s.length()`. | `MethodRef.java` compiled with no GCE jar. | Printed `BB`. **Not** a committed test. |
| GCE inside a lambda body | Covered by `lambdaBodiesCanContainGroups` for the three shapes above. | See multiple-parameter and nested probes. | Those three suite cases passed. | Matched their twins. |
| Lambda whose body throws a checked exception | **No repository test.** | `LambdaChecked.java` places `read() throws Exception` in the switch inside `filter(x -> ...)`. | **Compile failed:** `unreported exception Exception; must be caught or declared to be thrown`, pointing at `read()`. | Not run. This is the lambda rule, not a switch escape. |
| Lambda capturing a mutable local | **No repository test.** | `LambdaMutable.java` uses `j` inside the switch inside `filter`, then assigns `j = 1`. | **Compile failed:** `local variables referenced from a lambda expression must be final or effectively final`, pointing at `j`. | Not run. |

`GceTranspilerTest.reportsMalformedGroupsWithLocation` line 96 only checks that `age (>= (x -> x))` still contains the characters `x -> x`. That fragment is not a compilable program (`int` compared with a lambda). It is not evidence of lambda support.

**Conclusion.** Implicit, explicit, and block lambdas that *contain* a group are executed by one end-to-end test. Multiple parameters, nesting, and method references were compiled and run only by this audit's probe. Checked exceptions and mutable captures inside a user lambda are still rejected by `javac`. The README sentence "Neither has to be effectively final" (`README.md` line 157) does not mention that boundary.

---

## 3. Switch-expression correctness

### 3.1 The construct

`switch (0) { default -> { ... yield ...; } }` is a Java switch expression with a rule block. Switch expressions and `yield` have been in the language since Java 14, so they are inside Java 25. The selector `0` is an `int` constant and has no side effect. `default` makes the switch exhaustive. `javac --release 25` accepted `NoJar`, `MultiLambda`, `MethodRef`, `NestedLambda`, and `YieldNest`.

### 3.2 `var`, primitives, references, null

`var` is used unless `subject.strip()` is exactly `null` (`JavaCodeGenerator.java` lines 24–26).

- Bare `null (== null)` became `Object $gce$0 = (null)`. The end-to-end test `nullLiteralSubjectMatchesJava` passed.
- `(null) (== null)` became `var $gce$0 = ((null))` and **did not compile**. The special case is a string compare, not a parse of "this expression is a null literal".
- `longNearTheDoubleBoundaryStaysIntegral` passed. That is the suite's check that a `long` near 2^53 is not widened by the translator. `var` is why: the binding is not an overload.
- The large behavior test includes `Integer` 1000 identity, `int`/`Integer` numeric `==`, `char`, `float`, `boolean`, and `String`. It passed as part of the 19. This audit did not re-print that program's generated source.
- `byte` and `short` subjects: **NOT VERIFIED** by any test this audit found.

### 3.3 Evaluation, side effects, nesting, names, scope

- One binding, then `&&` / `||` in the yield. Operand text is parenthesized (`emitRelational`, lines 39–45), except `instanceof`, which must not be parenthesized as a value.
- Nested groups are nested switches. Probe `cond ? a (>= 1) : b (>= 2)` produced two switches, `$gce$0` and `$gce$1`.
- `freshName` (`GceRecognizer.java` lines 120–127) skips any identifier already reserved. `rewrite` collects identifiers when `collectNames` is true (`GceTranspiler.java` lines 47–52). Probe `int $gce$0 = 1; age (>= $gce$0)` bound `var $gce$1` and compared it with `($gce$0)`. The unit test `avoidsGeneratedNameCollisions` checks the same idea and passed.
- `YieldNest` puts a GCE switch in the `yield` position of an outer switch expression. It compiled and printed `1`. Inner `yield` bound to the inner switch. **Not** a committed test.

### 3.4 What `switch (0)` costs and restricts

- It is not a constant expression. `CaseOrdinary` compiled. `CaseGce` failed with `constant expression required`. A `static final int` comparison that is a case label in ordinary Java is not a case label after translation.
- The source text constructs a switch, a local, and a yield. This audit did not measure whether the JIT allocates. Do not read the source shape as a heap measurement.
- Debuggers will stop on a synthetic switch whose selector is always 0. That is a readability cost, not a behavior bug in the cases that compiled here.
- No probe showed `switch (0)` changing left-to-right order of a subject and a later operand. `++i` and `j++` are executed by `castIncrementAndQualifiedCreationMatchJava`, which passed.

---

## 4. Semantic equivalence tests

`GceEndToEndTest.assertSameOutput` (line 543) compiles class `C` from the translated GCE source and class `J` from a handwritten Java source, runs both `main` methods, and requires equal stdout after `\r\n` is turned into `\n`. `JavaRunner` uses `ToolProvider.getSystemJavaCompiler()` with `--release 25`. That is a real compile-and-run comparison, not a string compare, for the tests that call `assertSameOutput`.

| Topic | Covered by an executed comparison? | Where |
| --- | --- | --- |
| Reassigned local as subject | Yes | `reassignedLocalMatchesJava` |
| `for` index as subject | Yes | `forLoopIndexMatchesJava` |
| `array[i]` while `i` changes | Yes | `arrayIndexInLoopMatchesJava` |
| Reassigned local as operand | Yes | `operandThatUsesAReassignedLocalMatchesJava` |
| Checked exception from subject | Yes | `checkedExceptionFromSubjectPropagates` |
| Checked exception from operand | Yes | `checkedExceptionInOperandPropagates` |
| One subject evaluation, second comparison skipped | Yes | `translatedProgramMatchesSingleEvaluationJava` (`next(0) (>= 1 && <= boom())`) and `nestedGroupInsideAnOperandKeepsBothSubjectsSingle` |
| Side-effect order | Yes | same behavior test (`side("A"...)`, absent `Z` / `E` / `G`) |
| Short-circuit of a whole group | Yes | `groupInsideMethodArgumentOfASubjectRunsOnce` |
| Null reference and null unbox | Yes | behavior test (`nil == null`, `npe`) and `nullLiteralSubjectMatchesJava` |
| Primitive vs boxed `==`, two boxed `Integer` 1000 | Yes | behavior test |
| `long` not rounded through `double` | Yes | `longNearTheDoubleBoundaryStaysIntegral` |
| Nested GCE in an operand | Yes | `nestedGroupInsideAnOperandKeepsBothSubjectsSingle` |
| Nested GCE in a subject argument, parentheses, index | Yes | the three `groupInside...RunsOnce` tests |
| Cast, `++`, postfix `++`, qualified `new` | Yes | `castIncrementAndQualifiedCreationMatchJava` |
| `instanceof` and one unused pattern name | Yes | `instanceofGroupMatchesJava` |
| Three lambda shapes | Yes | `lambdaBodiesCanContainGroups` |
| Multiple groups in one expression | **Partial.** The behavior test mixes `age (>= 18) && (salary > 5000) && person.age() (<= 65)`. Two groups joined by `? :` were only transpiled in this audit, not executed in the suite. | |
| Exceptions thrown from a lambda | **No test.** Probe: compile error, as in ordinary Java. | |
| Numeric promotion of `byte` / `short` | **No test found.** | |
| Constant-expression context | **No test.** Probe: ordinary Java compiles, GCE form does not. | |
| Parenthesized null subject | **No test.** Probe: does not compile. | |

**Weak oracle.** In `javaProgram()` lines 747–748 the twin writes `new ArrayList<...>().size()` twice. The GCE form on line 627 evaluates that call once. Both sizes are 0, so this line cannot catch a double evaluation. The `calls` / `side` counters are the tests that can.

**Gaps worth a test, with the input that already failed or was only probed:**

1. `(null) (== null)` — must compile and print `true`, or be rejected by `GceSyntaxException`. Today `javac` rejects the output.
2. `static final int age = 20; switch (1) { case age (>= 18) ? 1 : 0: }` — document the constant-expression break or stop emitting a switch when the group is a constant.
3. `filter((x, y) -> x (>= y))` and `a -> b -> a (>= b)` — compile and compare with `x >= y` and `a >= b`.
4. `map(String::toUpperCase)` next to a group — the current test only searches for the characters `String::valueOf`.
5. A second `new ArrayList<>().size()` in the Java twin of the behavior test, so that line can fail if the subject runs twice.

---

## 5. Parser and grammar limitations

| Construct | What the code does | Intentional? | Verified how |
| --- | --- | --- | --- |
| Unparenthesized `a + b (>= 0)` | Subject is the postfix `b` only (`canStart` / `tryPostfix` in `GceRecognizer`). | Documented (`README.md` line 153). It is a real footgun because the result compiles. | Probe output in section 1.7. |
| `(a + b) (>= 0)` | Parenthesized primary is the subject. | Yes. | `parenthesesMakeABinaryExpressionTheSubject`; probe output. |
| Assignment `n (>= a = 1)` | `rejectTrailingOperandSyntax` throws (`ComparisonParser.java` lines 228–229). | Intentional rejection. | Probe: `assignment expressions are not supported in a GCE operand`. |
| Ternary as operand | `parseConditional` parses `?:`. At operand depth 0, `&&` / `||` end the operand (`parseConditional(false)`, lines 233–238). | Documented. | Not re-executed as its own program this audit. The parser method is present. |
| Casts | Recognizer accepts a cast pattern as a subject (lines 61–74). Operand casts call `parseUnary` after `typeParenEnd` (`parsePrimary`). | Implemented, not rejected. | End-to-end cast test passed. |
| Prefix and postfix `++` / `--` | `recognize` lines 44–59 and 85–88 include them in the subject. Operand parser consumes them instead of throwing. | Implemented. | End-to-end test passed. |
| `instanceof Type` and `Type name` | `parseTerm` lines 175–187 and `parseInstancePattern` lines 523–530. Any following identifier is taken as the pattern name. | `Type name` is implemented. | End-to-end test passed. The pattern variable `s` is not read; the test does not prove the pattern is in scope for a later use. |
| Record pattern `instanceof Point(int x, int y)` | **Not parsed.** | Documented as unsupported (`README.md` line 158). | Probe: `expected ')' but found '('`. |
| Unnamed pattern `instanceof String _` | `_` is not consumed as a pattern (`parseInstancePattern` only takes `IDENTIFIER`). | Documented as unsupported. | Probe: `expected ')' but found '_'`. |
| Nested parentheses | Counted by `enter` before recursion. Cap is `GceLimits.MAX_NESTING = 64`. | Intentional bound. | `excessiveNestingIsASyntaxError` passed. |
| Generics | Arguments and `<...>` are skipped. `AngleBrackets.skip` (`AngleBrackets.java` lines 9–12, 24–33) refuses to split `>>` / `>>>`. | The refusal to split is intentional and incomplete relative to `javac`. | A split-token program was **NOT VERIFIED** by compilation this audit. `m.get(k) (>= 0)` after a generic declaration translated. |
| Method references | `::` is consumed in `rejectTrailingOperandSyntax` and in `parsePostfix`. | Implemented as "do not reject". | Compile-and-run only in the external probe. |
| Lambdas | `(...) ->` is detected by skipping a parenthesis and looking for `ARROW`. A block body is delimiter-skipped (`parseLambdaBody`, lines 533–545). | Implemented for the forms probed. | Section 2. |
| Comments and whitespace inside a group | Comments are not tokens. The replacement deletes the span from the subject through the group's closing parenthesis. | Documented. | Probe: `/*c*/` disappeared. |
| Precedence | `parseOr` / `parseAnd` build `OrCondition` and `AndCondition`. The locked string is `a (>= 1 \|\| >= 2 && >= 3)` in `andBindsTighterThanOr`. | Matches Java `&&` before `\|\|`. | That unit test passed. |
| Ambiguous `(Type)` | `isCastPattern` decides. A following group is not treated as the cast operand, so `(age) (>= 0)` stays a parenthesized subject. | Intentional heuristic, not a symbol table. | Not given its own executed test this audit. Code path is `GceRecognizer.java` lines 61–74. |

---

## 6. Generated code and runtime dependencies

**Compiles with JDK 25 only.** `javac --release 25` on `NoJar.java`, `MultiLambda.java`, `MethodRef.java`, `NestedLambda.java`, and `YieldNest.java` used no `-cp`. All five succeeded. `java NoJar` ran and printed `false2true`.

**Jar at compile time or run time of generated code.** Not required for those five programs. `GceEndToEndTest` still puts `java.class.path` on the compiler classpath (`JavaRunner`), so the suite does not prove the jar is unnecessary. The probe does, for the programs above.

**Does generated code name a GCE runtime class?** The probe set `containsGceRuntime` from `dev.gce`, `GceEval`, and `Gce.test`. It was `false` on every translated sample, including the canonical `age (>= 18 && <= 65)`. The behavior test asserts `switch (0)` and `!GceEval` (`GceEndToEndTest.java` lines 17–18) and passed.

**The jar still contains the runtime.** `jar tf target/gce-compiler-0.3.0.jar` lists `dev/gce/runtime/Gce.class` and `dev/gce/runtime/GceEval*.class`. Those classes are for direct calls. `Gce.test` still boxes (`src/main/java/dev/gce/runtime/Gce.java`). Generated code does not need them.

**Allocations.** The source introduces a switch and a local. No object-construction expression is emitted for the temporary. Whether the JIT allocates a frame or eliminates the switch was **NOT VERIFIED**. `GceBenchmark` does not count allocations.

**Name collisions.** See section 3.3. The generated name is `$gce$` plus a counter, skipping identifiers already in the file.

**Readability.** The canonical expansion of a two-comparison group is one long switch with `yield`. It is legal Java and it is harder to step through than a local variable the programmer wrote. That is a cost of staying in expression position.

---

## 7. Performance benchmark audit

Source: `src/test/java/dev/gce/compiler/GceBenchmark.java`. It is not a JUnit test. Surefire did not run it.

What it measures, from the source:

1. **Translation only.** Build a method with 1,000 groups. Call `transpile` 5 times (warmup), then 20 times, and divide `nanoTime` by 20 (lines 33–46). Report input length, output length, and that mean.
2. **Execution only, separately.** Compile one ordinary loop and one translated loop (`n` is `i & 127`, so `n` is assigned once per iteration and not assigned inside the group). Run each once and print the sums (lines 75–76). Then time each with 3 warm-up calls and the mean of 5 calls of 2,000,000 iterations (lines 109–119).

JIT: the 3 warm-up calls are not a forced compilation, not a profile, and not a check that C2 compiled the method. One process, one machine, no fork, no iteration histogram.

This audit **did not re-run** `GceBenchmark`. The numbers in `README.md` lines 162 and `docs/ARCHITECTURE.md` (10.4 ms, 24,812 → 107,482, 0.745 ms vs 0.751 ms, both sums 750,000) are the document's claim about a previous run. They are **NOT RE-VERIFIED** here. A 0.006 ms gap on a single `nanoTime` mean is not a performance conclusion.

A JMH benchmark would need: `@Benchmark` methods for the ordinary loop and the translated loop, at least a few forks, warmup iterations long enough for C2, and a separate benchmark for `transpile` so translation time is not mixed with execution. Allocation would be a JMH profiler or a flight recording, not a reading of the generated source. Until that exists, the honest statement is: no allocation result, and no runtime result strong enough to say the switch is free or costly.

---

## 8. Build and release verification

Command, from the project directory:

```text
mvn clean verify
```

Observed result (24 November 2026, 15:04):

```text
Building GCE Compiler Prototype 0.3.0
Compiling 22 source files with javac [debug release 25]
Compiling 5 source files with javac [debug release 25]
Tests run: 19, Failures: 0, Errors: 0, Skipped: 0  -- GceEndToEndTest
Tests run: 14, Failures: 0, Errors: 0, Skipped: 0  -- GceTranspilerTest
Tests run: 6,  Failures: 0, Errors: 0, Skipped: 0  -- LexerTest
Tests run: 39, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
Building jar: target/gce-compiler-0.3.0.jar
```

| Check | Evidence |
| --- | --- |
| Compiler release | `pom.xml` lines 15 and 36: `<maven.compiler.release>25</maven.compiler.release>`, plugin 3.13.0. The log says `javac [debug release 25]`. |
| Tests | 39 = 19 + 14 + 6. Zero failures, zero errors, zero skipped. |
| Artifact | `target/gce-compiler-0.3.0.jar`. Manifest: `Build-Jdk-Spec: 25`, `Main-Class: dev.gce.Main`, created by Maven JAR Plugin 3.4.2. |
| Coordinates inside the jar | `META-INF/maven/dev.gce/gce-compiler/pom.properties`: `groupId=dev.gce`, `artifactId=gce-compiler`, `version=0.3.0`. |
| Dependencies | `pom.xml`: only `junit-jupiter` 5.11.4, `test` scope. No compile dependency. |
| Version text | `pom.xml` 0.3.0. `README.md` usage example uses `gce-compiler-0.3.0.jar`. `CHANGELOG.md` has a 0.3.0 section. `docs/GCE-LANGUAGE-SPECIFICATION.md` status line says 0.3.0. |
| README drift | Line 166 still says "Version 0.3 is the place for `instanceof`". `instanceof` is implemented. Line 170 says no license is specified. This workspace has **no `LICENSE` file** (search for `LICENSE*` returned nothing). The technical paper's Apache 2.0 claim refers to a GitHub blob. That remote file was **NOT VERIFIED** in this audit. |
| Spec drift | `docs/GCE-LANGUAGE-SPECIFICATION.md` line 265 says "The concrete shape used by version 0.2.1" and the next fence is the 0.3.0 switch. |
| Paper | `docs/GCE-TECHNICAL-PAPER.md` has a banner that 0.3.0 superseded the body. The body still describes the 0.2.1 lambda restrictions as current. A reader who skips the banner will be wrong. |

---

## 9. Findings

### Critical

None observed. No compiled probe and no passing end-to-end test returned a value that disagreed with the handwritten single-evaluation twin. This is not a proof that no such program exists.

### High

**H1. A parenthesized null subject does not compile.**

- Where: `JavaCodeGenerator.java` lines 24–26. The `Object` binding is used only when the subject text strip-equals `null`.
- Reproduce: `(null) (== null)`.
- Expected: the same result as `null (== null)`, which prints `true`, or a `GceSyntaxException` before `javac`.
- Actual: `var $gce$0 = ((null));` and `javac` error `cannot infer type for local variable $gce$0`.
- Correction: treat a parenthesized null as a null subject, or emit `Object` whenever `var` cannot infer the initializer.

**H2. A constant comparison stops being a constant expression.**

- Where: `JavaCodeGenerator.java` line 27. JLS constant expressions do not include switch expressions. This audit did not re-read the JLS PDF; the `javac` diagnostic is the evidence.
- Reproduce: `static final int age = 20;` and `case age (>= 18) ? 1 : 0:`.
- Expected if GCE were transparent: the ordinary `case age >= 18 ? 1 : 0:` which `javac` accepted (`CaseOrdinary`).
- Actual: `constant expression required` (`CaseGce.java:5`).
- Correction: document constant contexts as unsupported, and add a test that records the failure. Removing the switch entirely is a different design.

**H3. `a + b (>= 0)` compiles and compares `b`.**

- Where: `GceRecognizer.canStart` / `tryPostfix`. The subject is a postfix expression.
- Reproduce: probe output `a + (switch ... var $gce$0 = (b) ...)`.
- Expected by a reader who does not know the postfix rule: one evaluation of `a + b`.
- Actual: `a` is outside the switch; `b` is the subject. README line 153 states this. It is still a silent change relative to the unparenthesized sum.
- Correction: keep the rule, but the diagnostic story is weak because `javac` accepts the output. A warning is the smallest fix. Extending the subject leftward is a grammar change and must not swallow a preceding `&&`.

**H4. The "no effectively final / checked exceptions propagate" claim is too wide.**

- Where: `README.md` line 157. The switch removes those restrictions only for code in the enclosing method.
- Reproduce: `LambdaMutable.java` and `LambdaChecked.java`.
- Expected by that sentence: both compile.
- Actual: `javac` reports effectively-final and unreported `Exception`, because the switch is inside the user's lambda.
- Correction: say the restriction is gone for the generated translation, and unchanged for a lambda the programmer wrote.

### Medium

**M1. Pattern support is narrower than "pattern matching".** Record patterns and `_` fail as syntax errors (section 5). The successful `instanceof` test never reads the pattern variable, so scope of `s` is not shown.

**M2. Several forms the README lists as implemented are not compile-and-run tests.** Multiple lambda parameters, nested lambdas, and method references. The external probe passed. The repository can still regress them without a red test.

**M3. The behavior test's `ArrayList` twin cannot catch a double evaluation** (`GceEndToEndTest.java` lines 627 and 747–748).

**M4. Comments inside the replaced span are deleted** (probe `age /*c*/ (>= 18)`). Documented. Still a source-fidelity bug for anyone using the output as the file they edit.

**M5. `>>` / `>>>` are not split** (`AngleBrackets.java` lines 32–33). A program that needs a split was not compiled in this audit. The failure mode is a GCE syntax error or a bad type argument, not a measured one.

**M6. Release metadata is inconsistent.** No `LICENSE` in this tree, README says none is specified, README line 166 still assigns `instanceof` to a future 0.3, and specification section 11 mislabels the current shape as 0.2.1.

### Low

**L1. `GceBenchmark` is one `nanoTime` process.** It does not support a performance claim. The README quotes a previous run. This audit did not repeat it.

**L2. `GceEval` and `Gce` remain in the runtime jar** after the translator stopped calling them. Harmless if unused. `Gce.test` can still change `==` if a caller uses it. The javadoc says so.

**L3. `docs/GCE-TECHNICAL-PAPER.md` is a 0.2.1 paper with a banner.** The banner is easy to miss. It should not be cited as the 0.3.0 design.

**L4. Generated switches are legal and noisy.** Not a functional defect in the programs that ran.

---

## 10. Verdict

**A. Reliable inside the documented subset?**  
Yes, for the programs the 19 end-to-end tests actually execute: one evaluation, short-circuit, reassigned locals in the enclosing method, checked exceptions in the enclosing method, identity versus unboxing, `instanceof` with a type or a single name, casts, increments, qualified `new`, and the three lambda shapes in `lambdaBodiesCanContainGroups`.  
No, if "documented subset" is read as every sentence in the README. Parenthesized `null`, constant case labels, and lambdas that capture or throw are outside what those sentences say, and H1–H4 are real.

**B. Ready for an independent public release?**  
Not as a 1.0 product, and not while `README.md` says there is no license and this tree has no `LICENSE` file. As a labeled 0.3.0 experiment, the jar builds, tests pass, and generated code can be compiled with JDK 25 alone. Shipping that experiment still wants the README/spec contradictions in M6 fixed and H1 written down as a known bug. Those are release-note defects, not a broken `mvn clean verify`.

**C. Mature enough for external developers to try?**  
Yes, if they use the postfix-subject rules and they treat constant expressions, record patterns, and GCE-inside-their-own-lambdas as untested or restricted. The 39 tests are real compile-and-run tests, not empty assertions. They are not broad coverage of Java 25.

**D. What blocks 1.0.0?**  
H1 and H2 (wrong or illegal output for null parentheses and constant contexts), a license file that matches the README, tests for the lambda and method-reference forms that are only probed here, a pattern story that either implements record and unnamed patterns or stops sounding like general pattern matching, and a benchmark that can support whatever performance sentence the README keeps. 39 green tests do not close that list.

**E. What blocks an OpenJDK / JLS proposal?**  
The implementation is a preprocessor. `javac` does not parse GCE. A proposal would need a grammar that is not "postfix, then a parenthesis", a constant-expression rule, a pattern rule, an explicit statement that `a + b (>= 0)` compares `b`, and an implementation inside a compiler rather than a switch pasted into user methods. None of that is started in this repository. Passing this audit's build does not move it.

---

## Evidence index

| Evidence | Where it came from |
| --- | --- |
| 39 tests, 0 failures, JDK 25 compile, jar path | `mvn clean verify` on 24 November 2026 |
| Jar manifest and `version=0.3.0` | `jar tf` and `pom.properties` inside `target/gce-compiler-0.3.0.jar` |
| Generated strings in section 1 and 2 | `GceTranspiler` on the classpath `target/classes`, probe outside the repo |
| `javac` results for null parentheses, case labels, lambdas, no-jar runtime | `javac --release 25` with no GCE classpath, then `java` on the classes that compiled |
| Benchmark numbers in the README | **NOT RE-VERIFIED** in this audit |
| Previous lambda generator source | **Not in the tree.** Cited only from `CHANGELOG.md` and `GceEval.java` javadoc |
| Remote Apache license | **NOT VERIFIED** |
)
