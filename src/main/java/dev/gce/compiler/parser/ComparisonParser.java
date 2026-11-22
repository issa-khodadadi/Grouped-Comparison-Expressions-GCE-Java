package dev.gce.compiler.parser;

import dev.gce.compiler.GceSyntaxException;
import dev.gce.compiler.SourceLocations;
import dev.gce.compiler.ast.AndCondition;
import dev.gce.compiler.ast.ComparisonNode;
import dev.gce.compiler.ast.OrCondition;
import dev.gce.compiler.ast.ParenthesizedCondition;
import dev.gce.compiler.ast.RelationalCondition;
import dev.gce.compiler.lexer.Keywords;
import dev.gce.compiler.lexer.Token;
import dev.gce.compiler.lexer.TokenType;

import java.util.List;

/**
 * Recursive-descent parser for one comparison group and for the operand expressions inside it.
 *
 * <p>Condition precedence, highest first: parentheses, comparison terms, {@code &&}, {@code ||}.
 * {@code &&} and {@code ||} are left-associative. At parenthesis depth zero they always separate
 * comparison terms, so an operand that needs those operators must wrap them in parentheses.
 */
final class ComparisonParser {
    private final List<Token> tokens;
    private final String fullSource;
    private final int baseOffset;
    private int position;

    ComparisonParser(List<Token> tokens, String fullSource, int baseOffset) {
        this.tokens = tokens;
        this.fullSource = fullSource;
        this.baseOffset = baseOffset;
    }

    int position() {
        return position;
    }

    void jump(int position) {
        this.position = position;
    }

    ComparisonNode parseGroup() {
        Token open = peek();
        expect(TokenType.LPAREN, "'('");
        if (peek().isKeyword("instanceof")) {
            throw error("GCE instanceof is not supported in version 0.2; it is planned for version 0.3");
        }
        ComparisonNode condition = parseOr();
        expect(TokenType.RPAREN, "')'");
        if (condition == null) {
            throw at(open, "empty comparison group");
        }
        return condition;
    }

    private ComparisonNode parseOr() {
        ComparisonNode left = parseAnd();
        while (match(TokenType.OR)) {
            left = new OrCondition(left, parseAnd());
        }
        return left;
    }

    private ComparisonNode parseAnd() {
        ComparisonNode left = parseTerm();
        while (match(TokenType.AND)) {
            left = new AndCondition(left, parseTerm());
        }
        return left;
    }

    private ComparisonNode parseTerm() {
        if (match(TokenType.LPAREN)) {
            ComparisonNode inner = parseOr();
            expect(TokenType.RPAREN, "')'");
            return new ParenthesizedCondition(inner);
        }
        if (!peek().isComparisonOperator()) {
            throw error("expected a comparison operator (==, !=, <, <=, >, >=) or '(' but found " + describe(peek()));
        }
        String operator = peek().lexeme();
        position++;
        if (isGroupEnd(peek())) {
            throw error("missing right-hand operand after '" + operator + "'");
        }
        int operandStart = peek().start();
        int startToken = position;
        parseOperand();
        if (position == startToken) {
            throw error("missing right-hand operand after '" + operator + "'");
        }
        rejectTrailingOperandSyntax();
        int operandEnd = tokens.get(position - 1).end();
        return new RelationalCondition(operator, operandStart, operandEnd);
    }

    private void parseOperand() {
        parseConditional(false);
        rejectTrailingOperandSyntax();
    }

    private void rejectTrailingOperandSyntax() {
        Token token = peek();
        if (token.type() == TokenType.ARROW) {
            throw error("lambda expressions are not supported in a GCE operand");
        }
        if (token.type() == TokenType.COLONCOLON) {
            throw error("method references are not supported in a GCE operand");
        }
        if (isAssignment(token.type())) {
            throw error("assignment expressions are not supported in a GCE operand");
        }
    }

    /** {@code allowLogic} is false at the top of an operand, where {@code &&} and {@code ||} end the term. */
    private void parseConditional(boolean allowLogic) {
        if (allowLogic) {
            parseLogicOr();
        } else {
            parseBitwiseOr();
        }
        if (match(TokenType.QUESTION)) {
            parseConditional(true);
            expect(TokenType.COLON, "':'");
            parseConditional(allowLogic);
        }
    }

    private void parseLogicOr() {
        parseLogicAnd();
        while (match(TokenType.OR)) {
            parseLogicAnd();
        }
    }

    private void parseLogicAnd() {
        parseBitwiseOr();
        while (match(TokenType.AND)) {
            parseBitwiseOr();
        }
    }

    private void parseBitwiseOr() {
        parseXor();
        while (match(TokenType.BITOR)) {
            parseXor();
        }
    }

    private void parseXor() {
        parseBitAnd();
        while (match(TokenType.BITXOR)) {
            parseBitAnd();
        }
    }

    private void parseBitAnd() {
        parseEquality();
        while (match(TokenType.BITAND)) {
            parseEquality();
        }
    }

    private void parseEquality() {
        parseRelational();
        while (match(TokenType.EQ) || match(TokenType.NEQ)) {
            parseRelational();
        }
    }

    private void parseRelational() {
        parseShift();
        while (isRelational(peek().type()) || peek().isKeyword("instanceof")) {
            if (matchKeyword("instanceof")) {
                parseType();
                if (peek().type() == TokenType.IDENTIFIER || peek().isKeyword("final")) {
                    throw error("pattern-matching instanceof is not supported in a GCE operand");
                }
            } else {
                position++;
                parseShift();
            }
        }
    }

    private void parseShift() {
        parseAdditive();
        while (peek().type() == TokenType.SHL || peek().type() == TokenType.SHR || peek().type() == TokenType.USHR) {
            position++;
            parseAdditive();
        }
    }

    private void parseAdditive() {
        parseMultiplicative();
        while (peek().type() == TokenType.PLUS || peek().type() == TokenType.MINUS) {
            position++;
            parseMultiplicative();
        }
    }

    private void parseMultiplicative() {
        parseUnary();
        while (peek().type() == TokenType.STAR || peek().type() == TokenType.SLASH || peek().type() == TokenType.PERCENT) {
            position++;
            parseUnary();
        }
    }

    private void parseUnary() {
        TokenType type = peek().type();
        if (type == TokenType.PLUS || type == TokenType.MINUS || type == TokenType.BANG || type == TokenType.TILDE
                || type == TokenType.PLUSPLUS || type == TokenType.MINUSMINUS) {
            position++;
            parseUnary();
            return;
        }
        parsePostfix();
    }

    private void parsePostfix() {
        parsePrimary();
        while (true) {
            if (match(TokenType.DOT)) {
                if (check(TokenType.LT)) {
                    int after = AngleBrackets.skip(tokens, position);
                    if (after < 0) {
                        throw error("unclosed type arguments");
                    }
                    position = after;
                }
                if (!check(TokenType.IDENTIFIER) && !peek().isKeyword("class") && !peek().isKeyword("this") && !peek().isKeyword("super")) {
                    throw error("expected a name after '.' but found " + describe(peek()));
                }
                position++;
                if (check(TokenType.LT)) {
                    int after = AngleBrackets.skip(tokens, position);
                    if (after < 0) {
                        throw error("unclosed type arguments");
                    }
                    position = after;
                }
                if (check(TokenType.LPAREN)) {
                    if (isGroupStart(position)) {
                        parseGroup();
                    } else {
                        parseArguments();
                    }
                }
                continue;
            }
            if (check(TokenType.LBRACKET)) {
                position++;
                parseConditional(true);
                expect(TokenType.RBRACKET, "']'");
                continue;
            }
            if (check(TokenType.LPAREN)) {
                if (isGroupStart(position)) {
                    parseGroup();
                } else {
                    parseArguments();
                }
                continue;
            }
            if (match(TokenType.PLUSPLUS) || match(TokenType.MINUSMINUS)) {
                continue;
            }
            break;
        }
    }

    private void parsePrimary() {
        if (check(TokenType.LPAREN)) {
            position++;
            parseConditional(true);
            rejectTrailingOperandSyntax();
            expect(TokenType.RPAREN, "')'");
            return;
        }
        if (matchKeyword("new")) {
            parseCreator();
            return;
        }
        if (matchKeyword("switch")) {
            throw error("switch expressions are not supported in a GCE operand");
        }
        if (check(TokenType.IDENTIFIER) || peek().isKeyword("this") || peek().isKeyword("super") || isLiteral(peek())) {
            position++;
            return;
        }
        throw error("expected an operand expression but found " + describe(peek()));
    }

    private void parseCreator() {
        if (peek().type() == TokenType.KEYWORD && Keywords.isPrimitive(peek().lexeme())) {
            position++;
            if (!check(TokenType.LBRACKET)) {
                throw error("expected '[' after a primitive array creation");
            }
            parseDimensions();
            if (check(TokenType.LBRACE)) {
                position = Delimiters.skip(tokens, position, fullSource, baseOffset);
            }
            return;
        }
        parseType();
        if (check(TokenType.LPAREN)) {
            parseArguments();
            if (check(TokenType.LBRACE)) {
                position = Delimiters.skip(tokens, position, fullSource, baseOffset);
            }
            return;
        }
        if (check(TokenType.LBRACKET)) {
            parseDimensions();
            if (check(TokenType.LBRACE)) {
                position = Delimiters.skip(tokens, position, fullSource, baseOffset);
            }
            return;
        }
        throw error("expected arguments or dimensions after 'new'");
    }

    private void parseDimensions() {
        boolean any = false;
        while (check(TokenType.LBRACKET)) {
            any = true;
            position++;
            if (!check(TokenType.RBRACKET)) {
                parseConditional(true);
            }
            expect(TokenType.RBRACKET, "']'");
        }
        if (!any) {
            throw error("expected '['");
        }
    }

    private void parseArguments() {
        expect(TokenType.LPAREN, "'('");
        if (!check(TokenType.RPAREN)) {
            parseConditional(true);
            while (match(TokenType.COMMA)) {
                parseConditional(true);
            }
        }
        expect(TokenType.RPAREN, "')'");
    }

    private void parseType() {
        if (peek().type() == TokenType.KEYWORD && Keywords.isPrimitive(peek().lexeme())) {
            position++;
        } else if (check(TokenType.IDENTIFIER)) {
            position++;
            consumeTypeArguments();
            while (match(TokenType.DOT)) {
                if (!check(TokenType.IDENTIFIER)) {
                    throw error("expected a type name after '.' but found " + describe(peek()));
                }
                position++;
                consumeTypeArguments();
            }
        } else {
            throw error("expected a type but found " + describe(peek()));
        }
        while (check(TokenType.LBRACKET) && position + 1 < tokens.size() && tokens.get(position + 1).type() == TokenType.RBRACKET) {
            position += 2;
        }
    }

    private void consumeTypeArguments() {
        if (!check(TokenType.LT)) {
            return;
        }
        int after = AngleBrackets.skip(tokens, position);
        if (after < 0) {
            throw error("unclosed or ambiguous type arguments");
        }
        position = after;
    }

    boolean isGroupStart(int index) {
        if (index >= tokens.size() || tokens.get(index).type() != TokenType.LPAREN) {
            return false;
        }
        int cursor = index;
        while (cursor < tokens.size() && tokens.get(cursor).type() == TokenType.LPAREN) {
            cursor++;
        }
        if (cursor >= tokens.size()) {
            return false;
        }
        Token token = tokens.get(cursor);
        return token.isComparisonOperator() || token.isKeyword("instanceof");
    }

    private boolean isGroupEnd(Token token) {
        return token.type() == TokenType.AND || token.type() == TokenType.OR
                || token.type() == TokenType.RPAREN || token.type() == TokenType.EOF;
    }

    private static boolean isRelational(TokenType type) {
        return type == TokenType.LT || type == TokenType.GT || type == TokenType.LE || type == TokenType.GE;
    }

    private static boolean isAssignment(TokenType type) {
        return switch (type) {
            case ASSIGN, PLUS_ASSIGN, MINUS_ASSIGN, STAR_ASSIGN, SLASH_ASSIGN, PERCENT_ASSIGN,
                    AND_ASSIGN, OR_ASSIGN, XOR_ASSIGN, SHL_ASSIGN, SHR_ASSIGN, USHR_ASSIGN -> true;
            default -> false;
        };
    }

    private static boolean isLiteral(Token token) {
        return switch (token.type()) {
            case NUMBER, STRING, CHAR, TEXT_BLOCK, BOOLEAN, NULL -> true;
            default -> false;
        };
    }

    private boolean matchKeyword(String keyword) {
        if (peek().isKeyword(keyword)) {
            position++;
            return true;
        }
        return false;
    }

    private boolean match(TokenType type) {
        if (check(type)) {
            position++;
            return true;
        }
        return false;
    }

    private boolean check(TokenType type) {
        return peek().type() == type;
    }

    private Token expect(TokenType type, String what) {
        if (check(type)) {
            return tokens.get(position++);
        }
        throw error("expected " + what + " but found " + describe(peek()));
    }

    private Token peek() {
        return tokens.get(position);
    }

    private GceSyntaxException error(String detail) {
        return at(peek(), detail);
    }

    private GceSyntaxException at(Token token, String detail) {
        int offset = baseOffset + token.start();
        int[] lineColumn = SourceLocations.position(fullSource, offset);
        return new GceSyntaxException(detail, offset, lineColumn[0], lineColumn[1]);
    }

    private static String describe(Token token) {
        if (token.type() == TokenType.EOF) {
            return "end of input";
        }
        return "'" + token.lexeme() + "'";
    }
}
