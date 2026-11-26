// SPDX-License-Identifier: Apache-2.0
package dev.gce.compiler;

/**
 * A lexical or syntactic error in source submitted to {@link GceTranspiler}.
 * Positions are 1-based and refer to the original source, before generated code is inserted.
 * The offset is 0-based and may equal the source length when the error is at end of input.
 */
public final class GceSyntaxException extends IllegalArgumentException {
    private final String detail;
    private final int offset;
    private final int line;
    private final int column;

    public GceSyntaxException(String detail, int offset, int line, int column) {
        super(detail + " (line " + line + ", column " + column + ")");
        this.detail = detail;
        this.offset = offset;
        this.line = line;
        this.column = column;
    }

    /** Message without the trailing location suffix. */
    public String detail() {
        return detail;
    }

    /** 0-based offset into the source string this exception was reported against. */
    public int offset() {
        return offset;
    }

    public int line() {
        return line;
    }

    public int column() {
        return column;
    }

    /**
     * Re-expresses an error raised against a fragment so that {@link #offset()},
     * {@link #line()}, and {@link #column()} refer to {@code fullSource}.
     */
    public GceSyntaxException relocate(String fullSource, int baseOffset) {
        int absolute = baseOffset + offset;
        int[] position = SourceLocations.position(fullSource, absolute);
        return new GceSyntaxException(detail, absolute, position[0], position[1]);
    }
}
