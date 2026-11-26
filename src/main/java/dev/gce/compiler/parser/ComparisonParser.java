package dev.gce.compiler.parser;

import dev.gce.compiler.GceLimits;
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
    private int depth;

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

    void rejectAt(int tokenIndex, String detail) {
        jump(tokenIndex);
        throw error(detail);
    }

    /** A parenthesized type followed by a cast operand, such as {@code (int) raw} or {@code (String) s}. */
    boolean isCastPattern(int openParen) {
        int after = typeParenEnd(openParen);
        return after >= 0 && startsCastOperand(after, primitiveParen(openParen));
    }

    /** Token index immediately after the closing parenthesis of a type in parentheses, or {@code -1}. */
    int typeParenEnd(int open) {
        if (open >= tokens.size() || tokens.get(open).type() != TokenType.LPAREN) {
            return -1;
        }
        int index = open + 1;
        if (index >= tokens.size()) {
            return -1;
        }
        Token token = tokens.get(index);
        if (token.type() == TokenType.KEYWORD && Keywords.isPrimitive(token.lexeme())) {
            index++;
        } else if (token.type() == TokenType.IDENTIFIER) {
            index++;
            while (index < tokens.size()) {
                if (tokens.get(index).type() == TokenType.LT) {
                    int after = AngleBrackets.skip(tokens, index);
                    if (after < 0) {
                        return -1;
                    }
                    index = after;
                    continue;
                }
                if (tokens.get(index).type() == TokenType.DOT
                        && index + 1 < tokens.size()
                        && tokens.get(index + 1).type() == TokenType.IDENTIFIER) {
                    index += 2;
                    continue;
                }
                break;
            }
        } else {
            return -1;
        }
        while (index + 1 < tokens.size()
                && tokens.get(index).type() == TokenType.LBRACKET
                && tokens.get(index + 1).type() == TokenType.RBRACKET) {
            index += 2;
        }
        if (index < tokens.size() && tokens.get(index).type() == TokenType.RPAREN) {
            return index + 1;
        }
        return -1;
    }

    private boolean primitiveParen(int open) {
        int inner = open + 1;
        return inner < tokens.size()
                && tokens.get(inner).type() == TokenType.KEYWORD
                && Keywords.isPrimitive(tokens.get(inner).lexeme());
    }

    private boolean startsCastOperand(int index, boolean primitiveType) {
        if (index >= tokens.size()) {
            return false;
        }
        Token token = tokens.get(index);
        return switch (token.type()) {
            case IDENTIFIER, NUMBER, STRING, CHAR, TEXT_BLOCK, BOOLEAN, NULL, LPAREN, BANG, TILDE, PLUSPLUS, MINUSMINUS -> true;
            case PLUS, MINUS -> primitiveType;
            case KEYWORD -> token.lexeme().equals("new") || token.lexeme().equals("this") || token.lexeme().equals("super");
            default -> false;
        };
    }

    private void enter() {
        if (depth >= GceLimits.MAX_NESTING) {
            throw error("nesting is too deep");
        }
        depth++;
    }

    private void leave() {
        depth--;
    }

    ComparisonNode parseGroup() {
        enter();
        try {
            Token open = peek();
            expect(TokenType.LPAREN, "'('");
            ComparisonNode condition = parseOr();
            expect(TokenType.RPAREN, "')'");
            if (condition == null) {
                throw at(open, "empty comparison group");
            }
            return condition;
        } finally {
            leave();
        }
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
            enter();
            try {
                ComparisonNode inner = parseOr();
                expect(TokenType.RPAREN, "')'");
                return new ParenthesizedCondition(inner);
            } finally {
                leave();
            }
        }
        if (peek().isKeyword("instanceof")) {
            position++;
            if (isGroupEnd(peek())) {
                throw error("missing right-hand operand after 'instanceof'");
            }
            int operandStart = peek().start();
            int startToken = position;
            parseInstancePattern();
            if (position == startToken) {
                throw error("missing right-hand operand after 'instanceof'");
            }
            rejectTrailingOperandSyntax();
            return new RelationalCondition("instanceof", operandStart, tokens.get(position - 1).end());
        }
        if (!peek().isComparisonOperator()) {
            throw error("expected a comparison operator (==, !=, <, <=, >, >=, instanceof) or '(' but found " + describe(peek()));
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
            position++;
            parseLambdaBody();
            return;
        }
        if (token.type() == TokenType.COLONCOLON) {
            position++;
            if (!(check(TokenType.IDENTIFIER) || peek().isKeyword("new"))) {
                throw error("expected a name after '::' but found " + describe(peek()));
            }
            position++;
            return;
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
            enter();
            try {
                parseConditional(true);
                expect(TokenType.COLON, "':'");
                parseConditional(allowLogic);
            } finally {
                leave();
            }
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
                parseInstancePattern();
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
        if (type == TokenType.PLUSPLUS || type == TokenType.MINUSMINUS) {
            position++;
            enter();
            try {
                parseUnary();
            } finally {
                leave();
            }
            return;
        }
        if (type == TokenType.PLUS || type == TokenType.MINUS || type == TokenType.BANG || type == TokenType.TILDE) {
            position++;
            enter();
            try {
                parseUnary();
            } finally {
                leave();
            }
            return;
        }
        parsePostfix();
    }

    private void parsePostfix() {
        parsePrimary();
        while (true) {
            if (match(TokenType.DOT)) {
                if (peek().isKeyword("new")) {
                    position++;
                    parseCreator();
                    continue;
                }
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
            if (check(TokenType.PLUSPLUS) || check(TokenType.MINUSMINUS)) {
                position++;
                continue;
            }
            if (check(TokenType.COLONCOLON)) {
                position++;
                if (!(check(TokenType.IDENTIFIER) || peek().isKeyword("new"))) {
                    throw error("expected a name after '::' but found " + describe(peek()));
                }
                position++;
                continue;
            }
            break;
        }
    }

    private void parsePrimary() {
        if (check(TokenType.LPAREN)) {
            int afterParen = Delimiters.skip(tokens, position, fullSource, baseOffset);
            if (afterParen < tokens.size() && tokens.get(afterParen).type() == TokenType.ARROW) {
                position = afterParen + 1;
                parseLambdaBody();
                return;
            }
            if (isCastPattern(position)) {
                position = typeParenEnd(position);
                parseUnary();
                return;
            }
            enter();
            try {
                position++;
                parseConditional(true);
                rejectTrailingOperandSyntax();
                expect(TokenType.RPAREN, "')'");
            } finally {
                leave();
            }
            return;
        }
        if (matchKeyword("new")) {
            parseCreator();
            return;
        }
        if (matchKeyword("switch")) {
            if (!check(TokenType.LPAREN)) {
                throw error("expected '(' after 'switch' but found " + describe(peek()));
            }
            position = Delimiters.skip(tokens, position, fullSource, baseOffset);
            if (!check(TokenType.LBRACE)) {
                throw error("expected '{' after a switch selector but found " + describe(peek()));
            }
            position = Delimiters.skip(tokens, position, fullSource, baseOffset);
            return;
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

    private void parseInstancePattern() {
        if (matchKeyword("final") && !(check(TokenType.IDENTIFIER)
                || (peek().type() == TokenType.KEYWORD && Keywords.isPrimitive(peek().lexeme())))) {
            throw error("expected a type after 'final' but found " + describe(peek()));
        }
        parseType();
        if (check(TokenType.LPAREN)) {
            position = Delimiters.skip(tokens, position, fullSource, baseOffset);
            return;
        }
        if (check(TokenType.IDENTIFIER) || (check(TokenType.KEYWORD) && "_".equals(peek().lexeme()))) {
            position++;
        }
    }

    private void parseLambdaBody() {
        enter();
        try {
            if (check(TokenType.LBRACE)) {
                position = Delimiters.skip(tokens, position, fullSource, baseOffset);
                return;
            }
            parseConditional(true);
            rejectTrailingOperandSyntax();
        } finally {
            leave();
        }
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
