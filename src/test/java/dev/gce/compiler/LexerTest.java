// SPDX-License-Identifier: Apache-2.0
package dev.gce.compiler;

import dev.gce.compiler.lexer.Lexer;
import dev.gce.compiler.lexer.Token;
import dev.gce.compiler.lexer.TokenType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LexerTest {
    @Test void skipsCommentsAndKeepsStringBoundaries() {
        List<Token> tokens = lex("""
                // age (>= 1)
                String s = "age (>= 2)";
                /* block (>= 3) */
                int age;
                """);
        assertTrue(tokens.stream().noneMatch(token -> ">=".equals(token.lexeme()) || "<=".equals(token.lexeme())));
        Token string = tokens.stream().filter(token -> token.type() == TokenType.STRING).findFirst().orElseThrow();
        assertTrue(string.lexeme().contains("age (>= 2)"));
        assertEquals("age", tokens.stream().filter(token -> token.type() == TokenType.IDENTIFIER)
                .reduce((first, second) -> second).orElseThrow().lexeme());
    }

    @Test void lexesNumbersOperatorsAndTextBlocks() {
        String source = "double d = 10_20.3_4e-1_0d + .5 + 0x1.0p4 + 0b1_0;\n"
                + "String t = \"\"\"\n"
                + "        age (>= 9)\n"
                + "        \"\"\";\n";
        List<Token> tokens = lex(source);
        assertTrue(tokens.stream().anyMatch(token -> token.lexeme().equals("10_20.3_4e-1_0d")));
        assertTrue(tokens.stream().anyMatch(token -> token.lexeme().equals(".5")));
        assertTrue(tokens.stream().anyMatch(token -> token.lexeme().equals("0x1.0p4")));
        assertTrue(tokens.stream().anyMatch(token -> token.lexeme().equals("0b1_0")));
        assertTrue(tokens.stream().anyMatch(token -> token.type() == TokenType.TEXT_BLOCK
                && token.lexeme().contains("age (>= 9)")));
        assertEquals(3, tokens.stream().filter(token -> token.type() == TokenType.PLUS).count());
    }

    @Test void doesNotTreatSuffixInsideIdentifierAsNumericSuffix() {
        List<Token> tokens = lex("double a = 1.done;");
        assertEquals("1.", tokens.get(3).lexeme());
        assertEquals("done", tokens.get(4).lexeme());
    }

    @Test void reportsLineAndColumnForBadOctal() {
        GceSyntaxException ex = assertThrows(GceSyntaxException.class, () -> lex("int a =\n08;"));
        assertEquals(2, ex.line());
        assertTrue(ex.detail().contains("octal"));
    }

    @Test void translatesUnicodeEscapesBeforeLexing() {
        List<Token> tokens = lex("int \\" + "u0061ge = 1;");
        assertEquals("age", tokens.get(1).lexeme());
        assertEquals(4, tokens.get(1).start());
    }

    @Test void characterLiteralDoesNotEndOnEscapedQuote() {
        List<Token> tokens = lex("char q = '\\'';");
        assertEquals(TokenType.CHAR, tokens.get(3).type());
        assertEquals("'\\''", tokens.get(3).lexeme());
    }

    private static List<Token> lex(String source) {
        return new Lexer(source).lex();
    }
}
