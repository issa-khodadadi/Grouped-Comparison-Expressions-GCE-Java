package dev.gce.compiler.parser;

import dev.gce.compiler.GceSyntaxException;
import dev.gce.compiler.SourceLocations;
import dev.gce.compiler.lexer.Token;
import dev.gce.compiler.lexer.TokenType;

import java.util.ArrayDeque;
import java.util.List;

/** Balanced {@code ()}, {@code []}, and {@code {}} skipping. Angle brackets are not delimiters. */
final class Delimiters {
    private Delimiters() {}

    static int skip(List<Token> tokens, int open, String fullSource, int baseOffset) {
        Token opener = tokens.get(open);
        TokenType closing = closing(opener.type());
        if (closing == null) {
            throw at(fullSource, baseOffset, opener, "expected a bracket");
        }
        ArrayDeque<TokenType> stack = new ArrayDeque<>();
        stack.push(closing);
        int index = open + 1;
        while (index < tokens.size() && tokens.get(index).type() != TokenType.EOF) {
            Token token = tokens.get(index);
            TokenType opened = closing(token.type());
            if (opened != null) {
                stack.push(opened);
            } else if (isCloser(token.type())) {
                if (stack.isEmpty() || stack.peek() != token.type()) {
                    throw at(fullSource, baseOffset, token, "mismatched '" + token.lexeme() + "'");
                }
                stack.pop();
                if (stack.isEmpty()) {
                    return index + 1;
                }
            }
            index++;
        }
        throw at(fullSource, baseOffset, opener, "unclosed '" + opener.lexeme() + "'");
    }

    private static TokenType closing(TokenType type) {
        return switch (type) {
            case LPAREN -> TokenType.RPAREN;
            case LBRACKET -> TokenType.RBRACKET;
            case LBRACE -> TokenType.RBRACE;
            default -> null;
        };
    }

    private static boolean isCloser(TokenType type) {
        return type == TokenType.RPAREN || type == TokenType.RBRACKET || type == TokenType.RBRACE;
    }

    private static GceSyntaxException at(String fullSource, int baseOffset, Token token, String detail) {
        int offset = baseOffset + token.start();
        int[] position = SourceLocations.position(fullSource, offset);
        return new GceSyntaxException(detail, offset, position[0], position[1]);
    }
}
