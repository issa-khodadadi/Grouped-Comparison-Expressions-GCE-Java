package dev.gce.compiler.lexer;

import dev.gce.compiler.GceSyntaxException;
import dev.gce.compiler.SourceLocations;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Lightweight Java lexer. It is complete enough to keep strings, text blocks, characters,
 * comments, and numeric literals intact while the GCE recognizer looks for comparison groups.
 * It is not a certified OpenJDK lexer: a few obscure lexical splits (a {@code >>} that is
 * half a type argument and half an operator) are documented in the language specification.
 */
public final class Lexer {
    private static final Map<String, TokenType> OPERATORS = Map.ofEntries(
            Map.entry(">>>=", TokenType.USHR_ASSIGN),
            Map.entry(">>=", TokenType.SHR_ASSIGN),
            Map.entry("<<=", TokenType.SHL_ASSIGN),
            Map.entry(">>>", TokenType.USHR),
            Map.entry("...", TokenType.ELLIPSIS),
            Map.entry("==", TokenType.EQ),
            Map.entry("!=", TokenType.NEQ),
            Map.entry("<=", TokenType.LE),
            Map.entry(">=", TokenType.GE),
            Map.entry("&&", TokenType.AND),
            Map.entry("||", TokenType.OR),
            Map.entry("++", TokenType.PLUSPLUS),
            Map.entry("--", TokenType.MINUSMINUS),
            Map.entry("<<", TokenType.SHL),
            Map.entry(">>", TokenType.SHR),
            Map.entry("+=", TokenType.PLUS_ASSIGN),
            Map.entry("-=", TokenType.MINUS_ASSIGN),
            Map.entry("*=", TokenType.STAR_ASSIGN),
            Map.entry("/=", TokenType.SLASH_ASSIGN),
            Map.entry("%=", TokenType.PERCENT_ASSIGN),
            Map.entry("&=", TokenType.AND_ASSIGN),
            Map.entry("|=", TokenType.OR_ASSIGN),
            Map.entry("^=", TokenType.XOR_ASSIGN),
            Map.entry("->", TokenType.ARROW),
            Map.entry("::", TokenType.COLONCOLON),
            Map.entry("(", TokenType.LPAREN),
            Map.entry(")", TokenType.RPAREN),
            Map.entry("{", TokenType.LBRACE),
            Map.entry("}", TokenType.RBRACE),
            Map.entry("[", TokenType.LBRACKET),
            Map.entry("]", TokenType.RBRACKET),
            Map.entry(".", TokenType.DOT),
            Map.entry(",", TokenType.COMMA),
            Map.entry(";", TokenType.SEMICOLON),
            Map.entry("@", TokenType.AT),
            Map.entry("?", TokenType.QUESTION),
            Map.entry(":", TokenType.COLON),
            Map.entry("~", TokenType.TILDE),
            Map.entry("!", TokenType.BANG),
            Map.entry("%", TokenType.PERCENT),
            Map.entry("^", TokenType.BITXOR),
            Map.entry("&", TokenType.BITAND),
            Map.entry("|", TokenType.BITOR),
            Map.entry("*", TokenType.STAR),
            Map.entry("/", TokenType.SLASH),
            Map.entry("+", TokenType.PLUS),
            Map.entry("-", TokenType.MINUS),
            Map.entry("=", TokenType.ASSIGN),
            Map.entry("<", TokenType.LT),
            Map.entry(">", TokenType.GT)
    );

    private final String original;
    private final UnicodeDecoder unicode;
    private final String decoded;
    private final List<Token> tokens = new ArrayList<>();
    private int index;

    public Lexer(String source) {
        this(UnicodeDecoder.decode(source), source);
    }

    public Lexer(UnicodeDecoder unicode, String original) {
        this.unicode = unicode;
        this.original = original;
        this.decoded = unicode.decoded();
    }

    public List<Token> lex() {
        if (index == 0 && !decoded.isEmpty() && decoded.charAt(0) == '\uFEFF') {
            index++;
        }
        while (index < decoded.length()) {
            char c = decoded.charAt(index);
            if (Character.isWhitespace(c)) {
                index++;
                continue;
            }
            if (c == '/' && peek(1) == '/') {
                lineComment();
                continue;
            }
            if (c == '/' && peek(1) == '*') {
                blockComment();
                continue;
            }
            if (c == '"') {
                if (peek(1) == '"' && peek(2) == '"') {
                    textBlock();
                } else {
                    stringLiteral();
                }
                continue;
            }
            if (c == '\'') {
                charLiteral();
                continue;
            }
            if (c == '.' && isDigit(peek(1))) {
                number();
                continue;
            }
            if (isDigit(c)) {
                number();
                continue;
            }
            if (Character.isJavaIdentifierStart(c)) {
                identifier();
                continue;
            }
            if (!operator()) {
                throw error("unexpected character '" + printable(c) + "'", index);
            }
        }
        emit(TokenType.EOF, index, index);
        return List.copyOf(tokens);
    }

    private void lineComment() {
        index += 2;
        while (index < decoded.length()) {
            char c = decoded.charAt(index);
            if (c == '\n' || c == '\r') {
                break;
            }
            index++;
        }
    }

    private void blockComment() {
        int start = index;
        index += 2;
        while (index + 1 < decoded.length()) {
            if (decoded.charAt(index) == '*' && decoded.charAt(index + 1) == '/') {
                index += 2;
                return;
            }
            index++;
        }
        throw error("unclosed block comment", start);
    }

    private void stringLiteral() {
        int start = index;
        index++;
        while (index < decoded.length()) {
            char c = decoded.charAt(index);
            if (c == '\\') {
                consumeEscape(false);
                continue;
            }
            if (c == '"') {
                index++;
                emit(TokenType.STRING, start, index);
                return;
            }
            if (c == '\n' || c == '\r') {
                throw error("unclosed string literal", start);
            }
            index++;
        }
        throw error("unclosed string literal", start);
    }

    private void textBlock() {
        int start = index;
        index += 3;
        if (index >= decoded.length() || (decoded.charAt(index) != '\n' && decoded.charAt(index) != '\r')) {
            throw error("text block opening delimiter must be followed by a line terminator", start);
        }
        if (decoded.charAt(index) == '\r') {
            index++;
            if (index < decoded.length() && decoded.charAt(index) == '\n') {
                index++;
            }
        } else {
            index++;
        }
        while (index < decoded.length()) {
            char c = decoded.charAt(index);
            if (c == '\\') {
                consumeEscape(true);
                continue;
            }
            if (c == '"' && peek(1) == '"' && peek(2) == '"') {
                index += 3;
                emit(TokenType.TEXT_BLOCK, start, index);
                return;
            }
            index++;
        }
        throw error("unclosed text block", start);
    }

    private void charLiteral() {
        int start = index;
        index++;
        if (index >= decoded.length()) {
            throw error("unclosed character literal", start);
        }
        char c = decoded.charAt(index);
        if (c == '\'') {
            throw error("empty character literal", start);
        }
        if (c == '\n' || c == '\r') {
            throw error("unclosed character literal", start);
        }
        if (c == '\\') {
            consumeEscape(false);
        } else {
            index += Character.charCount(decoded.codePointAt(index));
        }
        if (index >= decoded.length() || decoded.charAt(index) != '\'') {
            throw error("unclosed character literal", start);
        }
        index++;
        emit(TokenType.CHAR, start, index);
    }

    private void consumeEscape(boolean textBlock) {
        int start = index;
        index++;
        if (index >= decoded.length()) {
            throw error("truncated escape", start);
        }
        char c = decoded.charAt(index);
        switch (c) {
            case 'b', 't', 'n', 'f', 'r', '"', '\'', '\\' -> index++;
            case 's' -> {
                if (!textBlock) {
                    throw error("illegal escape character", start);
                }
                index++;
            }
            case '\n' -> {
                if (!textBlock) {
                    throw error("illegal escape character", start);
                }
                index++;
            }
            case '\r' -> {
                if (!textBlock) {
                    throw error("illegal escape character", start);
                }
                index++;
                if (index < decoded.length() && decoded.charAt(index) == '\n') {
                    index++;
                }
            }
            case '0', '1', '2', '3', '4', '5', '6', '7' -> consumeOctal(c);
            default -> throw error("illegal escape character", start);
        }
    }

    private void consumeOctal(char first) {
        index++;
        int max = first <= '3' ? 3 : 2;
        int count = 1;
        while (count < max && index < decoded.length() && isOctal(decoded.charAt(index))) {
            index++;
            count++;
        }
    }

    private void identifier() {
        int start = index;
        int first = decoded.codePointAt(index);
        index += Character.charCount(first);
        while (index < decoded.length()) {
            int cp = decoded.codePointAt(index);
            if (!Character.isJavaIdentifierPart(cp)) {
                break;
            }
            index += Character.charCount(cp);
        }
        String lexeme = decoded.substring(start, index);
        TokenType type = switch (lexeme) {
            case "true", "false" -> TokenType.BOOLEAN;
            case "null" -> TokenType.NULL;
            default -> Keywords.isKeyword(lexeme) ? TokenType.KEYWORD : TokenType.IDENTIFIER;
        };
        emit(type, start, index);
    }

    private void number() {
        int start = index;
        char first = decoded.charAt(index);
        if (first == '0' && (peek(1) == 'x' || peek(1) == 'X')) {
            hexNumber(start);
            return;
        }
        if (first == '0' && (peek(1) == 'b' || peek(1) == 'B')) {
            binaryNumber(start);
            return;
        }
        if (first == '.') {
            index++;
            if (decimalDigits() == 0) {
                throw error("malformed floating-point literal", start);
            }
            if (index < decoded.length() && (decoded.charAt(index) == 'e' || decoded.charAt(index) == 'E')) {
                exponent(start);
            }
            floatSuffix();
            emit(TokenType.NUMBER, start, index);
            return;
        }
        boolean leadingZero = first == '0';
        int digits = decimalDigitsTracking();
        boolean nonOctalDigit = lastSawNonOctal;
        if (digits == 0) {
            throw error("malformed numeric literal", start);
        }
        boolean floating = false;
        if (index < decoded.length() && decoded.charAt(index) == '.') {
            floating = true;
            index++;
            decimalDigits();
        }
        if (index < decoded.length() && (decoded.charAt(index) == 'e' || decoded.charAt(index) == 'E')) {
            floating = true;
            exponent(start);
        }
        if (floating || hasFloatSuffix()) {
            floatSuffix();
        } else {
            if (leadingZero && nonOctalDigit) {
                throw error("illegal digit in an octal literal", start);
            }
            intSuffix();
        }
        emit(TokenType.NUMBER, start, index);
    }

    private boolean hasFloatSuffix() {
        if (index >= decoded.length()) {
            return false;
        }
        char c = decoded.charAt(index);
        return (c == 'f' || c == 'F' || c == 'd' || c == 'D') && !isIdentifierPart(peek(1));
    }

    private boolean lastSawNonOctal;

    private int decimalDigitsTracking() {
        lastSawNonOctal = false;
        int count = 0;
        boolean underscore = false;
        while (index < decoded.length()) {
            char c = decoded.charAt(index);
            if (c >= '0' && c <= '9') {
                if (c > '7') {
                    lastSawNonOctal = true;
                }
                index++;
                count++;
                underscore = false;
            } else if (c == '_') {
                if (count == 0 || underscore) {
                    throw error("illegal underscore", index);
                }
                index++;
                underscore = true;
            } else {
                break;
            }
        }
        if (underscore) {
            throw error("illegal underscore", index - 1);
        }
        return count;
    }

    /** Decimal digits. An underscore must sit between digits. Returns the digit count. */
    private int decimalDigits() {
        int count = 0;
        boolean underscore = false;
        while (index < decoded.length()) {
            char c = decoded.charAt(index);
            if (c >= '0' && c <= '9') {
                index++;
                count++;
                underscore = false;
            } else if (c == '_') {
                if (count == 0 || underscore) {
                    throw error("illegal underscore", index);
                }
                index++;
                underscore = true;
            } else {
                break;
            }
        }
        if (underscore) {
            throw error("illegal underscore", index - 1);
        }
        return count;
    }

    private void exponent(int start) {
        index++;
        if (index < decoded.length() && (decoded.charAt(index) == '+' || decoded.charAt(index) == '-')) {
            index++;
        }
        if (decimalDigits() == 0) {
            throw error("malformed floating-point literal", start);
        }
    }

    private void floatSuffix() {
        if (index < decoded.length()) {
            char c = decoded.charAt(index);
            if ((c == 'f' || c == 'F' || c == 'd' || c == 'D') && !isIdentifierPart(peek(1))) {
                index++;
            }
        }
    }

    private void intSuffix() {
        if (index < decoded.length()) {
            char c = decoded.charAt(index);
            if ((c == 'l' || c == 'L') && !isIdentifierPart(peek(1))) {
                index++;
            }
        }
    }

    private void hexNumber(int start) {
        index += 2;
        int digits = hexDigits();
        boolean dot = false;
        if (index < decoded.length() && decoded.charAt(index) == '.') {
            dot = true;
            index++;
            digits += hexDigits();
        }
        if (index < decoded.length() && (decoded.charAt(index) == 'p' || decoded.charAt(index) == 'P')) {
            if (digits == 0) {
                throw error("malformed floating-point literal", start);
            }
            exponent(start);
            floatSuffix();
            emit(TokenType.NUMBER, start, index);
            return;
        }
        if (dot) {
            throw error("malformed floating-point literal", start);
        }
        if (digits == 0) {
            throw error("malformed hexadecimal literal", start);
        }
        intSuffix();
        emit(TokenType.NUMBER, start, index);
    }

    private int hexDigits() {
        int count = 0;
        boolean underscore = false;
        while (index < decoded.length()) {
            char c = decoded.charAt(index);
            if (isHex(c)) {
                index++;
                count++;
                underscore = false;
            } else if (c == '_') {
                if (count == 0 || underscore) {
                    throw error("illegal underscore", index);
                }
                index++;
                underscore = true;
            } else {
                break;
            }
        }
        if (underscore) {
            throw error("illegal underscore", index - 1);
        }
        return count;
    }

    private void binaryNumber(int start) {
        index += 2;
        int count = 0;
        boolean underscore = false;
        while (index < decoded.length()) {
            char c = decoded.charAt(index);
            if (c == '0' || c == '1') {
                index++;
                count++;
                underscore = false;
            } else if (c == '_') {
                if (count == 0 || underscore) {
                    throw error("illegal underscore", index);
                }
                index++;
                underscore = true;
            } else if (c >= '2' && c <= '9') {
                throw error("illegal digit in a binary literal", start);
            } else {
                break;
            }
        }
        if (underscore) {
            throw error("illegal underscore", index - 1);
        }
        if (count == 0) {
            throw error("binary numbers must contain at least one binary digit", start);
        }
        intSuffix();
        emit(TokenType.NUMBER, start, index);
    }

    private boolean operator() {
        int remaining = decoded.length() - index;
        int max = Math.min(4, remaining);
        for (int length = max; length >= 1; length--) {
            String text = decoded.substring(index, index + length);
            TokenType type = OPERATORS.get(text);
            if (type != null) {
                int start = index;
                index += length;
                emit(type, start, index);
                return true;
            }
        }
        return false;
    }

    private void emit(TokenType type, int decodedStart, int decodedEnd) {
        int start = unicode.originalIndex(decodedStart);
        int end = unicode.originalIndex(decodedEnd);
        String lexeme = decoded.substring(decodedStart, Math.min(decodedEnd, decoded.length()));
        tokens.add(new Token(type, lexeme, start, end));
    }

    private GceSyntaxException error(String detail, int decodedIndex) {
        int offset = unicode.originalIndex(decodedIndex);
        int[] position = SourceLocations.position(original, offset);
        return new GceSyntaxException(detail, offset, position[0], position[1]);
    }

    private char peek(int ahead) {
        int at = index + ahead;
        return at >= 0 && at < decoded.length() ? decoded.charAt(at) : '\0';
    }

    private static String printable(char c) {
        if (c >= 32 && c < 127) {
            return String.valueOf(c);
        }
        return String.format("\\u%04x", (int) c);
    }

    private static boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }

    private static boolean isOctal(char c) {
        return c >= '0' && c <= '7';
    }

    private static boolean isHex(char c) {
        return isDigit(c) || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
    }

    private boolean isIdentifierPart(char c) {
        if (c == '\0') {
            return false;
        }
        return Character.isJavaIdentifierPart(c);
    }
}
