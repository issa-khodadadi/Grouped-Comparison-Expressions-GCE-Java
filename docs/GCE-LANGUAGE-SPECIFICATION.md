# GCE Language Specification

**Status:** version 0.2.1, implemented by this repository.  
**This is not a JLS change and it is not implemented by `javac`.**

Sections marked **Implemented** describe behavior covered by the translator and tests. Sections marked **Proposed** are not accepted by version 0.2.

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
ComparisonOperator ::= "==" | "!=" | "<" | "<=" | ">" | ">="
```

A `'('` starts a group only when, after any further opening parentheses, the next token is a comparison operator. Otherwise the parentheses keep their existing Java meaning. In particular, `age()` is still a call, and `salary > 5000` is still a normal comparison.

```java
age (>= 18)     // group
age()           // call
(salary > 5000) // ordinary Java
```

`value (instanceof String)` matches this shape but is rejected. See section 8.

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

**Not implemented:** pulling a binary operator into the subject. `a + b (>= 0)` has subject `b`. The sum is written `(a + b) (>= 0)`.

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
- lambdas and method references
- `switch` expressions
- casts (`(int) raw`, `(String) value`)
- prefix and postfix `++` and `--`
- qualified instance creation (`holder.new Inner()`)
- pattern variables after `instanceof` (`instanceof String s`)
- a GCE `instanceof` operator (section 8)

### 3.4 Proposed and not accepted

```ebnf
ComparisonOperator ::= ... | "instanceof"     (* proposed, 0.3 *)
```

`obj (instanceof String)` and `obj (instanceof String && != null)` are planned. Version 0.2 reports a syntax error that says `instanceof` is planned for 0.3.

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

Version 0.2.1 does not type-check. It passes the subject to `dev.gce.runtime.GceEval.of` and lets overload resolution choose a primitive chain (`boolean`, `int`, `long`, `double`) or a reference chain. `javac` then applies the usual rules:

- relational operators unbox numeric wrappers
- `==` and `!=` on two references compare identity, including `String` and enum constants
- `==` between a primitive and a wrapper unboxes the wrapper
- `int == null` remains illegal
- `Integer >= 0` when the value is null throws `NullPointerException`
- `!= null && >= 0` does not unbox when the null test is false
- `char`, `byte`, and `short` subjects widen to `int`
- `float` subjects widen to `double`. Every `float` value is exactly representable as a `double`
- a `null` literal is a reference subject

There is no separate GCE type lattice. Compiling the translation requires `gce-compiler` on the classpath, because the generated expression calls `GceEval`.

## 6. Evaluation (**Implemented**)

For a group that is actually evaluated:

1. Evaluate the subject once and remember the value.
2. Evaluate comparison terms from left to right.
3. Do not evaluate the right operand of `&&` or `||` when the left operand decides the result.
4. Evaluate a comparison's operand only when that comparison is reached. The operand runs after the subject.

The group itself is evaluated only when the surrounding Java expression reaches it. `false && next() (>= 0)` does not call `next()`, because `next()` is an argument of the call that the `&&` skips.

The subject expression is that argument, so it is type-checked in the enclosing method. A local variable used only as the subject does not have to be effectively final, and a checked exception thrown by the subject propagates. The predicate lambda still captures every local variable named in an operand, and a checked exception thrown by an operand is still illegal.

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

## 8. Unsupported constructs

**Rejected with `GceSyntaxException`:**

- `instanceof` used as the operator of a group
- a missing operand, a missing parenthesis, or two operands with no operator
- assignment, lambda, method reference, or `switch` expression in an operand
- a cast used as a GCE subject or operand
- prefix or postfix `++` or `--` used as a GCE subject or operand
- qualified instance creation (`holder.new Inner()`) used as a GCE subject or operand
- pattern-matching `instanceof` in an operand
- an unclosed string, character literal, text block, or comment
- an illegal numeric literal such as a trailing underscore or an octal digit `8`
- more than 64 nested groups, parentheses, unary operators, or conditional operators

**Copied through and left to `javac`, or not treated as the subject:**

- ordinary comparisons (`age >= 18`)
- `++i`, `(int) raw`, and `holder.new Inner()` when they are not the subject or operand of a group. The same forms written inside a subject call's argument list are ordinary Java and are kept
- binary operators to the left of a postfix subject (`a + b (>= 0)` compares `b`)
- a checked exception thrown by an operand (the predicate lambda cannot declare it)
- an operand that names a local variable which is not effectively final (the predicate lambda captures it)

**Recognized only in the simple form:**

- a shift or unsigned-shift token closes two or three type-argument brackets when the whole token belongs to the type arguments. A token that would have to be split between a type argument and an operator is not supported.

## 9. Errors (**Implemented**)

`GceTranspiler.transpile` throws `GceSyntaxException` (an `IllegalArgumentException`) and does not return a partial rewrite.

The detail text says what was expected. `line()` and `column()` are 1-based positions in the original source. `offset()` is the 0-based index. A failure inside a nested operand is reported against the original file, not against the fragment.

## 10. Differences from ordinary Java

| Topic | Ordinary Java | GCE 0.2.1 |
| --- | --- | --- |
| Repeating a subject | The programmer writes it again | The subject is written once and evaluated once |
| `age (>= 18 && <= 65)` | Syntax error | Grouped comparison |
| `age >= 18 && age <= 65` | Unchanged | Unchanged; this is not GCE |
| Range written as `>= 18 < 65` | Not an expression of that shape | One comparison, operand `18 < 65` |
| `&&` inside `?:` at group depth 0 | `?:` can contain `&&` | `&&` ends the operand; parenthesize to keep it |
| `instanceof` in a group | Not this syntax | Syntax error until 0.3 |
| Checked exceptions from the subject | Propagate | Propagate, because the subject is an argument |
| Checked exceptions from an operand | Propagate | Rejected by `javac`; the predicate is a lambda |
| Local used only as the subject | May be reassigned | May be reassigned |
| Local used in an operand | May be reassigned | Must be effectively final |
| Identity of `==` | Java rules | Same rules: primitives select a primitive chain, references select `RefChain` |

## 11. Translation shape

The normative runtime effect is section 6. The concrete shape used by version 0.2.1 is an implementation choice, not part of the language:

```java
(dev.gce.runtime.GceEval.of((subject)).is($gce$N -> (predicate using $gce$N)))
```

`of` is overloaded on `boolean`, `int`, `long`, `double`, and a generic reference type. The lambda is the argument of `is`, not of an overload, so choosing the chain does not depend on the lambda body. `$gce$N` is chosen so it is not an identifier already spelled in the compilation unit. A later version may lower a group differently, including in bytecode, as long as section 6 still holds.

Version 0.2 stored the subject in `var` inside a `Supplier`. That captured every local the subject named. 0.2.1 does not emit that supplier. `dev.gce.runtime.Gce.test` remains available for direct calls and still boxes; the translator does not emit it.
