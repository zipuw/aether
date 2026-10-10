package com.aether.term;

public class TerminalBuffer {
    public static final int ATTR_BOLD = 1;
    public static final int ATTR_UNDERLINE = 2;
    public static final int ATTR_INVERSE = 4;

    public int rows, cols;
    public char[][] chars;
    public byte[][] fg;
    public byte[][] bg;
    public byte[][] attrs;
    public int cursorX, cursorY;
    public boolean cursorVisible = true;

    public TerminalBuffer(int rows, int cols) { resize(rows, cols); }

    public void resize(int r, int c) {
        this.rows = r; this.cols = c;
        chars = new char[r][c];
        fg = new byte[r][c];
        bg = new byte[r][c];
        attrs = new byte[r][c];
        clear();
    }

    public void clear() {
        for (int y = 0; y < rows; y++)
            for (int x = 0; x < cols; x++) {
                chars[y][x] = ' '; fg[y][x] = 7; bg[y][x] = -1; attrs[y][x] = 0;
            }
        cursorX = cursorY = 0;
    }

    public void scrollUp() {
        for (int y = 0; y < rows - 1; y++) {
            System.arraycopy(chars[y+1], 0, chars[y], 0, cols);
            System.arraycopy(fg[y+1], 0, fg[y], 0, cols);
            System.arraycopy(bg[y+1], 0, bg[y], 0, cols);
            System.arraycopy(attrs[y+1], 0, attrs[y], 0, cols);
        }
        int y = rows - 1;
        for (int x = 0; x < cols; x++) {
            chars[y][x] = ' '; fg[y][x] = 7; bg[y][x] = -1; attrs[y][x] = 0;
        }
    }

    public void lineFeed() {
        cursorY++;
        if (cursorY >= rows) { scrollUp(); cursorY = rows - 1; }
    }

    public void putChar(int cp, byte f, byte b, byte a) {
        if (cursorX >= cols) { cursorX = 0; lineFeed(); }
        chars[cursorY][cursorX] = (char)cp;
        fg[cursorY][cursorX] = f;
        bg[cursorY][cursorX] = b;
        attrs[cursorY][cursorX] = a;
        cursorX++;
    }

    public void eraseInLine(int mode) {
        int y = cursorY;
        if (mode == 0) for (int x = cursorX; x < cols; x++) wipe(y, x);
        else if (mode == 1) for (int x = 0; x <= cursorX && x < cols; x++) wipe(y, x);
        else if (mode == 2) for (int x = 0; x < cols; x++) wipe(y, x);
    }

    public void eraseInDisplay(int mode) {
        if (mode == 0) {
            eraseInLine(0);
            for (int y = cursorY + 1; y < rows; y++)
                for (int x = 0; x < cols; x++) wipe(y, x);
        } else if (mode == 1) {
            for (int y = 0; y < cursorY; y++)
                for (int x = 0; x < cols; x++) wipe(y, x);
            eraseInLine(1);
        } else if (mode == 2 || mode == 3) {
            for (int y = 0; y < rows; y++)
                for (int x = 0; x < cols; x++) wipe(y, x);
            if (mode == 2) { cursorX = 0; cursorY = 0; }
        }
    }

    private void wipe(int y, int x) {
        chars[y][x] = ' '; fg[y][x] = 7; bg[y][x] = -1; attrs[y][x] = 0;
    }
}
