package dev.gce.compiler.parser;

import dev.gce.compiler.ast.ComparisonNode;
import dev.gce.compiler.codegen.JavaCodeGenerator;
import dev.gce.compiler.lexer.Keywords;
import dev.gce.compiler.lexer.Token;
import dev.gce.compiler.lexer.TokenType;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.BiFunction;

/** Finds postfix subjects that are immediately followed by a comparison group. */
public final class GceRecognizer {
    public record Replacement(int start, int end, String text) {}

    private final List<Token> tokens;
    private final String fragment;
    private final String fullSource;
    private final int baseOffset;
    private final Set<String> reserved;
    private final ComparisonParser parser;
    private final JavaCodeGenerator generator;
    private final BiFunction<Integer, Integer, String> operands;

    public GceRecognizer(List<Token> tokens, String fragment, String fullSource, int baseOffset,
                  Set<String> reserved, BiFunction<Integer, Integer, String> operands) {
        this.tokens = tokens;
        this.fragment = fragment;
        this.fullSource = fullSource;
        this.baseOffset = baseOffset;
        this.reserved = reserved;
        this.operands = operands;
        this.parser = new ComparisonParser(tokens, fullSource, baseOffset);
        this.generator = new JavaCodeGenerator();
    }

    public List<Replacement> recognize() {
        List<Replacement> found = new ArrayList<>();
        int index = 0;
        while (index < tokens.size() && tokens.get(index).type() != TokenType.EOF) {
            Token token = tokens.get(index);
            if (isIncDec(token.type())) {
                int cursor = index;
                while (cursor < tokens.size() && isIncDec(tokens.get(cursor).type())) {
                    cursor++;
                }
                int end = tryPostfix(cursor);
                while (end < tokens.size() && isIncDec(tokens.get(end).type())) {
                    end++;
                }
                if (end > cursor && parser.isGroupStart(end)) {
                    parser.rejectAt(index, prefixMessage(token.type()) + " in a GCE subject");
                }
                index++;
                continue;
            }
            if (token.type() == TokenType.LPAREN && parser.isCastPattern(index)) {
                int cursor = parser.typeParenEnd(index);
                while (cursor < tokens.size() && isUnaryPrefix(tokens.get(cursor).type())) {
                    cursor++;
                }
                int end = cursor < tokens.size() ? tryPostfix(cursor) : cursor;
                while (end < tokens.size() && isIncDec(tokens.get(end).type())) {
                    end++;
                }
                if (parser.isGroupStart(end)) {
                    parser.rejectAt(index, "casts are not supported in a GCE subject");
                }
            }
            if (!canStart(token)) {
                index++;
                continue;
            }
            int end = tryPostfix(index);
            int afterInc = end;
            while (afterInc < tokens.size() && isIncDec(tokens.get(afterInc).type())) {
                afterInc++;
            }
            if (afterInc != end && parser.isGroupStart(afterInc)) {
                parser.rejectAt(end, postfixMessage(tokens.get(end).type()) + " in a GCE subject");
            }
            if (end > index && parser.isGroupStart(end)) {
                parser.jump(end);
                ComparisonNode condition = parser.parseGroup();
                int subjectStart = tokens.get(index).start();
                int subjectEnd = tokens.get(end - 1).end();
                int rangeEnd = tokens.get(parser.position() - 1).end();
                String subject = operands.apply(subjectStart, subjectEnd);
                String temp = freshName();
                String code = generator.generate(subject, condition, temp, operands);
                found.add(new Replacement(subjectStart, rangeEnd, code));
                index = parser.position();
                continue;
            }
            index++;
        }
        return found;
    }

    private static boolean isIncDec(TokenType type) {
        return type == TokenType.PLUSPLUS || type == TokenType.MINUSMINUS;
    }

    private static boolean isUnaryPrefix(TokenType type) {
        return isIncDec(type) || type == TokenType.PLUS || type == TokenType.MINUS
                || type == TokenType.BANG || type == TokenType.TILDE;
    }

    private static String prefixMessage(TokenType type) {
        return type == TokenType.PLUSPLUS
                ? "prefix increment is not supported"
                : "prefix decrement is not supported";
    }

    private static String postfixMessage(TokenType type) {
        return type == TokenType.PLUSPLUS
                ? "postfix increment is not supported"
                : "postfix decrement is not supported";
    }

    private String freshName() {
        int number = 0;
        while (true) {
            String name = "$gce$" + number++;
            if (reserved.add(name)) {
                return name;
            }
        }
    }

    private boolean canStart(Token token) {
        return switch (token.type()) {
            case IDENTIFIER, NUMBER, STRING, CHAR, TEXT_BLOCK, BOOLEAN, NULL, LPAREN -> true;
            case KEYWORD -> token.lexeme().equals("this") || token.lexeme().equals("super") || token.lexeme().equals("new");
            default -> false;
        };
    }

    private int tryPostfix(int start) {
        int pos = tryPrimary(start);
        if (pos == start) {
            return start;
        }
        boolean sawQualifiedNew = false;
        int qualifiedAt = -1;
        while (pos < tokens.size() && tokens.get(pos).type() != TokenType.EOF) {
            TokenType type = tokens.get(pos).type();
            if (type == TokenType.DOT) {
                if (pos + 1 >= tokens.size()) {
                    break;
                }
                Token next = tokens.get(pos + 1);
                if (next.isKeyword("new")) {
                    int after = tryNew(pos + 1);
                    if (after < 0) {
                        break;
                    }
                    sawQualifiedNew = true;
                    qualifiedAt = pos + 1;
                    pos = after;
                    continue;
                }
                if (next.type() == TokenType.LT) {
                    int after = AngleBrackets.skip(tokens, pos + 1);
                    if (after < 0 || after >= tokens.size() || tokens.get(after).type() != TokenType.IDENTIFIER) {
                        break;
                    }
                    pos = after + 1;
                    pos = consumeCallOrGroup(pos);
                    continue;
                }
                if (next.type() == TokenType.IDENTIFIER || next.isKeyword("class") || next.isKeyword("this") || next.isKeyword("super")) {
                    pos += 2;
                    if (pos < tokens.size() && tokens.get(pos).type() == TokenType.LT) {
                        int after = AngleBrackets.skip(tokens, pos);
                        if (after < 0) {
                            return start;
                        }
                        pos = after;
                    }
                    pos = consumeCallOrGroup(pos);
                    continue;
                }
                break;
            }
            if (type == TokenType.LBRACKET) {
                pos = Delimiters.skip(tokens, pos, fullSource, baseOffset);
                continue;
            }
            if (type == TokenType.LPAREN && !parser.isGroupStart(pos)) {
                pos = Delimiters.skip(tokens, pos, fullSource, baseOffset);
                continue;
            }
            break;
        }
        if (sawQualifiedNew && parser.isGroupStart(pos)) {
            parser.rejectAt(qualifiedAt, "qualified instance creation is not supported in a GCE subject");
        }
        return pos;
    }

    private int consumeCallOrGroup(int pos) {
        if (pos < tokens.size() && tokens.get(pos).type() == TokenType.LPAREN && !parser.isGroupStart(pos)) {
            return Delimiters.skip(tokens, pos, fullSource, baseOffset);
        }
        return pos;
    }

    private int tryPrimary(int start) {
        Token token = tokens.get(start);
        if (token.type() == TokenType.IDENTIFIER || token.type() == TokenType.NUMBER || token.type() == TokenType.STRING
                || token.type() == TokenType.CHAR || token.type() == TokenType.TEXT_BLOCK
                || token.type() == TokenType.BOOLEAN || token.type() == TokenType.NULL
                || token.isKeyword("this") || token.isKeyword("super")) {
            return start + 1;
        }
        if (token.isKeyword("new")) {
            int after = tryNew(start);
            return after < 0 ? start : after;
        }
        if (token.type() == TokenType.LPAREN) {
            return Delimiters.skip(tokens, start, fullSource, baseOffset);
        }
        return start;
    }

    private int tryNew(int start) {
        int pos = start + 1;
        if (pos >= tokens.size()) {
            return -1;
        }
        Token token = tokens.get(pos);
        if (token.type() == TokenType.KEYWORD && Keywords.isPrimitive(token.lexeme())) {
            pos++;
            if (pos >= tokens.size() || tokens.get(pos).type() != TokenType.LBRACKET) {
                return -1;
            }
            while (pos < tokens.size() && tokens.get(pos).type() == TokenType.LBRACKET) {
                pos = Delimiters.skip(tokens, pos, fullSource, baseOffset);
            }
            if (pos < tokens.size() && tokens.get(pos).type() == TokenType.LBRACE) {
                pos = Delimiters.skip(tokens, pos, fullSource, baseOffset);
            }
            return pos;
        }
        int afterName = createdName(pos);
        if (afterName < 0) {
            return -1;
        }
        pos = afterName;
        if (pos < tokens.size() && tokens.get(pos).type() == TokenType.LPAREN) {
            pos = Delimiters.skip(tokens, pos, fullSource, baseOffset);
            if (pos < tokens.size() && tokens.get(pos).type() == TokenType.LBRACE) {
                pos = Delimiters.skip(tokens, pos, fullSource, baseOffset);
            }
            return pos;
        }
        if (pos < tokens.size() && tokens.get(pos).type() == TokenType.LBRACKET) {
            while (pos < tokens.size() && tokens.get(pos).type() == TokenType.LBRACKET) {
                pos = Delimiters.skip(tokens, pos, fullSource, baseOffset);
            }
            if (pos < tokens.size() && tokens.get(pos).type() == TokenType.LBRACE) {
                pos = Delimiters.skip(tokens, pos, fullSource, baseOffset);
            }
            return pos;
        }
        return pos;
    }

    private int createdName(int pos) {
        if (pos >= tokens.size() || tokens.get(pos).type() != TokenType.IDENTIFIER) {
            return -1;
        }
        pos++;
        while (true) {
            if (pos < tokens.size() && tokens.get(pos).type() == TokenType.LT) {
                int after = AngleBrackets.skip(tokens, pos);
                if (after < 0) {
                    return -1;
                }
                pos = after;
            }
            if (pos + 1 < tokens.size() && tokens.get(pos).type() == TokenType.DOT
                    && tokens.get(pos + 1).type() == TokenType.IDENTIFIER) {
                pos += 2;
                continue;
            }
            break;
        }
        return pos;
    }
}
