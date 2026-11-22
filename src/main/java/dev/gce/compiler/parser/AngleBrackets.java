package dev.gce.compiler.parser;

import dev.gce.compiler.lexer.Token;
import dev.gce.compiler.lexer.TokenType;

import java.util.List;

/**
 * Skips a type-argument list. {@code >>} and {@code >>>} close two and three brackets
 * when the whole token is part of the type arguments. A token that would close more
 * brackets than are open is rejected rather than split.
 */
final class AngleBrackets {
    private AngleBrackets() {}

    /** @return the index after the closing bracket, or {@code -1} when the list is not well formed */
    static int skip(List<Token> tokens, int open) {
        if (open >= tokens.size() || tokens.get(open).type() != TokenType.LT) {
            return -1;
        }
        int depth = 0;
        int index = open;
        while (index < tokens.size() && tokens.get(index).type() != TokenType.EOF) {
            int delta = switch (tokens.get(index).type()) {
                case LT -> 1;
                case GT -> -1;
                case SHR -> -2;
                case USHR -> -3;
                default -> 0;
            };
            if (delta != 0) {
                if (depth + delta < 0) {
                    return -1;
                }
                depth += delta;
                index++;
                if (depth == 0) {
                    return index;
                }
                continue;
            }
            index++;
        }
        return -1;
    }
}
