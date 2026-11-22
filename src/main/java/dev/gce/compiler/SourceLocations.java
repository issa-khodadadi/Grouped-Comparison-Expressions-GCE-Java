package dev.gce.compiler;

/**
 * Converts an absolute source offset into a 1-based line and column.
 * A tab counts as one column. Carriage returns and line feeds both start a line;
 * a CRLF pair counts as one line break.
 */
public final class SourceLocations {
    private SourceLocations() {}

    public static int line(String source, int offset) {
        return position(source, offset)[0];
    }

    public static int column(String source, int offset) {
        return position(source, offset)[1];
    }

    public static int[] position(String source, int offset) {
        if (offset < 0) {
            offset = 0;
        }
        if (offset > source.length()) {
            offset = source.length();
        }
        int line = 1;
        int column = 1;
        for (int i = 0; i < offset; i++) {
            char c = source.charAt(i);
            if (c == '\n') {
                line++;
                column = 1;
            } else if (c == '\r') {
                line++;
                column = 1;
                if (i + 1 < offset && source.charAt(i + 1) == '\n') {
                    i++;
                }
            } else {
                column++;
            }
        }
        return new int[] {line, column};
    }
}
