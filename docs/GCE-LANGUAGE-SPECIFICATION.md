# GCE Language Specification

**Status:** version 1.0.0-rc1, implemented by this repository. This is a release candidate, not a final 1.0.0.  
**This is not a JLS change and it is not implemented by `javac`.**

Sections marked **Implemented** describe behavior covered by the translator and tests. Any explicitly unsupported behavior is listed in section 8.

## 1. Overview

A grouped comparison applies one or more relational tests to a single subject expression:

```java
age (>= 18 && <= 65)
```

The subject is evaluated once. Each comparison then uses that value. `&&` and `||` keep Java's precedence, associativity, and short-circuit rules. Source that is not a grouped comparison is left unchanged, so new and old syntax can be mixed:

```java
person.getAge() (>= 18) && (person.getSalary() > 5000)
```

## 2. Motivation

Repeating a long or effectful expression is noisy and easy to get wrong:

```java
order.getCustomer().getAddress().getHouseNumber() != null
    && order.getCustomer().getAddress().getHouseNumber() >= 0
```

GCE exists so the subject is written once and executed once, without defining a new statement form and without changing the meaning of existing Java.

## 3. Syntax

### 3.1 Group (**Implemented**)

```ebnf
GroupedComparison ::= PostfixSubject ComparisonGroup

ComparisonGroup    ::= "(" ComparisonOr ")"
ComparisonOr       ::= ComparisonAnd { "||" ComparisonAnd }
ComparisonAnd      ::= ComparisonUnary { "&&" ComparisonUnary }
ComparisonUnary    ::= "(" ComparisonOr ")"
                     | ComparisonOperator Operand
ComparisonOperator ::= "==" | "!=" | "<" | "<=" | ">" | ">=" | "instanceof"
```

A `'('` starts a group only when, after any further opening parentheses, the next token is a comparison operator. Otherwise the parentheses keep their existing Java meaning. In particular, `age()` is still a call, and `salary > 5000` is still a normal comparison.

```java
age (>= 18)     // group
age()           // call
(salary > 5000) // ordinary Java
```

`value (instanceof String)`, `value (instanceof String s)`, `value (instanceof String _)`, and `value (instanceof Point(int x, int y))` are grouped comparisons. The operand is copied into `temp instanceof <pattern>`. A record pattern's parentheses are part of that operand. A pattern variable is in scope only where Java would put it in the generated `instanceof` expression.

### 3.2 Subject (**Implemented**)

The subject is the postfix expression immediately before the group:

```ebnf
PostfixSubject ::= Primary { Suffix }
Primary        ::= Literal
                 | Identifier
                 | "this" | "super"
                 | "new" Creator
                 | "(" BalancedTokens ")"
Suffix         ::= "." Identifier [TypeArguments] [Arguments]
                 | "." TypeArguments Identifier [Arguments]
                 | "[" BalancedTokens "]"
                 | Arguments
```

`Arguments` is not consumed when that parenthesis is itself a comparison group.

Implemented subjects include:

- names and dotted fields: `age`, `person.address.house`
- calls with arguments: `map.get(key)`, `person.getAddress().getHouseNumber()`
- array access: `arr[i + 1]`
- `this` and `super`
- `new`, including a simple type-argument list: `new java.util.ArrayList<java.util.List<String>>().size()`
- a parenthesized expression: `(low + high)`

**Not implemented:** pulling an unparenthesized binary operator into the subject. `a + b (>= 0)` is rejected with `GceSyntaxException`; it is never rewritten as a comparison of `b`. To compare the sum, write `(a + b) (>= 0)`.

### 3.3 Operand (**Implemented**)

An operand is an expression. At parenthesis depth zero, `&&` and `||` end the operand and belong to the group. Inside parentheses they are ordinary Java operators.

Implemented operand forms:

- numeric literals, including signs, hex, binary, underscores, and suffixes (`-2.0`, `0xFF`, `0b1111_1111`, `10L`, `1.5f`)
- string, character, and text-block literals
- `true`, `false`, and `null`
- names, fields, calls, and array access
- unary `+ - ! ~`
- `* / % + - << >> >>> < > <= >= instanceof == != & ^ |`
- the conditional operator `?:`
- `new`, including an anonymous class body or array initializer scanned as a balanced `{ ... }` span
- parenthesized expressions, which may themselves contain grouped comparisons

Rejected operand forms, with a GCE syntax error:

- assignment and compound assignment

Accepted operand forms that version 0.2.1 rejected:

- lambdas and method references
- `switch` expressions copied as a selector plus a brace body
- casts
- prefix and postfix `++` and `--`
- qualified instance creation (`holder.new Inner()`)
- `instanceof`, including type patterns, `final` type patterns, unnamed patterns (`_`), and record patterns (including nested record patterns)

### 3.4 Several subjects (**Implemented**)

Each group has one subject. Groups combine with the same `&&` and `||` as ordinary Java boolean expressions, including mixed operators and parentheses. The count of groups, and the count of comparisons inside each group, is not fixed.

```java
if (a)(>= 1 && <= 10 && != 5)
        && (b)(== 3)
        && (c)(> 0 && < 100 && != 50 && != 75) {
    accept();
}
```

`(a)`, `(b)`, and `(c)` are three subjects. The first group has three comparisons, the second has one, and the third has four. `&&` and `||` between groups are Java's, so `||` is looser than `&&` unless parentheses say otherwise. Each subject runs once, and only when evaluation reaches that group.

The `(` after `if` or `while` may be the first subject's `(`. The comparisons and any later `&&` or `||` terms are still the condition. The same form works for `do`/`while`. A condition that is already wrapped, such as `if ((a)(>= 1) && (b)(== 3))`, is the same expression. So is a group written in an ordinary expression:

```java
boolean open = (a)(>= 1 && <= 10) && (b)(== 3);
return (a)(>= 1) || (b)(== 3);
for (; (a)(>= 1) && (b)(== 3); ) { }
```

`switch (` and `synchronized (` are not boolean headers. A chain written after those parentheses is not pulled into the selector. Put the chain in one expression that is already inside the parentheses, or use it in an `if`.

### 3.5 Not accepted

Guard patterns (`when`) are not a separately tested form. Pattern-variable scope is exactly the scope Java assigns to the generated `instanceof` expression; GCE does not extend it across later comparison terms. An unparenthesized binary, shift, or bitwise expression immediately before a group is rejected; parenthesize the complete subject.

## 4. Precedence and associativity (**Implemented**)

Inside a group, from tightest to loosest:

| Level | Form | Associativity |
| --- | --- | --- |
| 1 | `( ... )` | grouping |
| 2 | comparison term (`>= 18`) | — |
| 3 | `&&` | left |
| 4 | `||` | left |

This matches Java. So:

```java
age (>= 18 || >= 30 && < 40)
```

means:

```java
age >= 18 || (age >= 30 && age < 40)
```

Parentheses change that grouping:

```java
five ((>= 0 || >= 100) && >= 6)
```

means `(five >= 0 || five >= 100) && five >= 6`.

Operands are parenthesized in the output, so an operand's own operators are not reassociated with the comparison:

```java
limit (>= a ? b : c)     // limit >= (a ? b : c)
flag (== ready == paid)  // flag == (ready == paid)
```

Pasting the operator between the subject and the operand without parentheses would not mean the same thing. `limit >= a ? b : c` in ordinary Java is `(limit >= a) ? b : c`.

Juxtaposition does not create another comparison. `age (>= 18 < 65)` is one term whose operand is the expression `18 < 65`.

## 5. Types (**Implemented by delegation**)

Version 1.0.0-rc1 does not type-check. It binds the subject with `var` and lets `javac` apply the usual rules:

- relational operators unbox numeric wrappers
- `==` and `!=` on two references compare identity, including `String` and enum constants
- `==` between a primitive and a wrapper unboxes the wrapper
- `int == null` remains illegal
- `Integer >= 0` when the value is null throws `NullPointerException`
- `!= null && >= 0` does not unbox when the null test is false
- `char`, `byte`, `short`, `float`, and `long` subjects keep the type `var` infers. They are not widened by the translator
- a subject whose tokens are a `null` literal wrapped only in parentheses is bound as `Object`. `(null)` and `((null))` are included. The translator does not decide this by comparing the subject text with `"null"`

There is no separate GCE type lattice. The generated expression uses only JDK syntax. The compiler jar is not required to compile or run it.

## 6. Evaluation (**Implemented**)

For a group that is actually evaluated:

1. Evaluate the subject once and remember the value.
2. Evaluate comparison terms from left to right.
3. Do not evaluate the right operand of `&&` or `||` when the left operand decides the result.
4. Evaluate a comparison's operand only when that comparison is reached. The operand runs after the subject.

The group itself is evaluated only when the surrounding Java expression reaches it. `false && next() (>= 0)` does not call `next()`, because the switch expression that contains `next()` is the right operand of `&&`.

The switch block is type-checked in the enclosing method. A local variable used by the subject or by an operand does not have to be effectively final, and a checked exception thrown by either one propagates. That does not relax the rules of a lambda the programmer wrote. A group inside `x -> x (>= 0)` is still inside that lambda.

This is not the same as copying the subject text onto every comparison. That expansion would run side effects once per comparison.

Nested groups follow the same rules. In `flag (== (next() (>= 1 && <= 1)))` the outer subject is `flag` and the inner subject is `next()`. Each runs once, and `next()` runs only because the outer comparison is reached.

A group inside the subject is rewritten before the outer group is emitted. In `id(age() (>= 18)) (>= 1)` the call `age()` runs once, while `id` is evaluating its argument, and the outer comparison then uses `id`'s result. The same rewrite applies to a parenthesized subject and to an array index that belongs to an outer subject.

## 7. Examples (**Implemented**)

```java
age (>= 18 && <= 65)

score (>= 90 || (>= 70 && < 80))

person.getAddress().getHouseNumber() (!= null && >= 0)

hex (== 0xFF && >= 0b1111_1111 && <= 255)

(low + high) (>= 5 && <= 5)

flag (== (next() (>= 1 && <= 1)))
```

Comments, strings, character literals, and text blocks are inert:

```java
// age (>= 18)            not a group
String s = "age (>= 18)"; // not a group
String block = """
        age (>= 18)
        """;              // not a group
if (age (>= 18)) { }      // this one is a group
```

A comment outside the replaced span stays in the output. A comment that sits inside the span, between the first character of the subject and the closing parenthesis of the group, is not copied. `/*keep*/ age /*inside*/ (>= 18) // tail` keeps `/*keep*/` and `// tail`.

## 8. Unsupported constructs

**Rejected with `GceSyntaxException`:**

- a missing operand, a missing parenthesis, or two operands with no operator
- assignment in an operand
- an unparenthesized binary, shift, or bitwise operator immediately before the subject, such as `a + b (>= 0)`. Parenthesize the subject: `(a + b) (>= 0)`. `&&` and `||` are not this case; `flag && age (>= 18)` stays valid
- a group in a `case` label or in an annotation value. The switch lowering is not a constant expression, so the translator reports that instead of emitting code that `javac` would reject later
- an unclosed string, character literal, text block, or comment
- an illegal numeric literal such as a trailing underscore or an octal digit `8`
- more than 64 nested groups, parentheses, unary operators, or conditional operators

**Copied through and left to `javac`, or not a constant:**

- ordinary comparisons (`age >= 18`)
- a `static final` initializer that contains a group. The field compiles when the rest of the program is legal, and it is not a constant variable

**Recognized only in the simple form:**

- a shift or unsigned-shift token closes two or three type-argument brackets when the whole token belongs to the type arguments. A token that would have to be split between a type argument and an operator is not supported.

## 9. Errors (**Implemented**)

`GceTranspiler.transpile` throws `GceSyntaxException` (an `IllegalArgumentException`) and does not return a partial rewrite.

The detail text says what was expected. `line()` and `column()` are 1-based positions in the original source. `offset()` is the 0-based index. A failure inside a nested operand is reported against the original file, not against the fragment.

## 10. Differences from ordinary Java

| Topic | Ordinary Java | GCE 1.0.0-rc1 |
| --- | --- | --- |
| Repeating a subject | The programmer writes it again | The subject is written once and evaluated once |
| `age (>= 18 && <= 65)` | Syntax error | Grouped comparison |
| `age >= 18 && age <= 65` | Unchanged | Unchanged; this is not GCE |
| Range written as `>= 18 < 65` | Not an expression of that shape | One comparison, operand `18 < 65` |
| `&&` inside `?:` at group depth 0 | `?:` can contain `&&` | `&&` ends the operand; parenthesize to keep it |
| `instanceof` in a group | Not this syntax until translated | `instanceof`, including a type pattern, `_`, or a record pattern |
| Checked exceptions from the subject or an operand | Propagate | Propagate. The switch block is in the enclosing method |
| Local used by the subject or an operand | May be reassigned | May be reassigned |
| Identity of `==` | Java rules | Same rules. `var` keeps the subject type |

## 11. Translation shape

The normative runtime effect is section 6. The concrete shape used by version 1.0.0-rc1 is an implementation choice, not part of the language. It is not a constant expression:

```java
(switch (0) { default -> { var $gce$N = (subject); yield (predicate using $gce$N); } })
```

`$gce$N` is chosen so it is not an identifier already spelled in the compilation unit. The switch selector is the constant `0` and has no side effect. A null-literal subject uses `Object` instead of `var`. Version 0.2.1 called `GceEval.of(subject).is(...)`. The translator does not emit that call. `dev.gce.runtime.Gce.test` remains available for direct calls and still boxes.
