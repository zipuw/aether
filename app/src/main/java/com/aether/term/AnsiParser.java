package com.aether.term;

public class AnsiParser {
    private enum State { GROUND, ESC, CSI, OSC }
    private State state = State.GROUND;
    private final StringBuilder params = new StringBuilder();
    private TerminalBuffer buf;

    private byte curFg = 7, curBg = -1, curAttrs = 0;
    private int utf8Acc = 0, utf8Rem = 0;

    public AnsiParser(TerminalBuffer buf) { this.buf = buf; }
    public void setBuffer(TerminalBuffer b) { this.buf = b; }

    public void feed(byte[] data, int len) {
        for (int i = 0; i < len; i++) processByte(data[i] & 0xFF);
    }

    private void processByte(int b) {
        if (utf8Rem > 0) {
            if ((b & 0xC0) == 0x80) {
                utf8Acc = (utf8Acc << 6) | (b & 0x3F);
                utf8Rem--;
                if (utf8Rem == 0) handleCodepoint(utf8Acc);
                return;
            }
            utf8Rem = 0;
        }
        if (b < 0x80) { handleCodepoint(b); return; }
        if ((b & 0xE0) == 0xC0) { utf8Acc = b & 0x1F; utf8Rem = 1; }
        else if ((b & 0xF0) == 0xE0) { utf8Acc = b & 0x0F; utf8Rem = 2; }
        else if ((b & 0xF8) == 0xF0) { utf8Acc = b & 0x07; utf8Rem = 3; }
    }

    private void handleCodepoint(int cp) {
        if (state == State.GROUND) ground(cp);
        else if (state == State.ESC) esc(cp);
        else if (state == State.CSI) csi(cp);
        else if (state == State.OSC) osc(cp);
    }

    private void ground(int c) {
        if (c == 0x1B) { state = State.ESC; return; }
        if (c == '\r') { buf.cursorX = 0; return; }
        if (c == '\n') { buf.lineFeed(); return; }
        if (c == '\b') { if (buf.cursorX > 0) buf.cursorX--; return; }
        if (c == '\t') { int n = 8 - (buf.cursorX % 8); for (int i = 0; i < n; i++) buf.putChar(' ', curFg, curBg, curAttrs); return; }
        if (c == 0x07) return;
        if (c < 0x20) return;
        buf.putChar(c, curFg, curBg, curAttrs);
    }

    private void esc(int c) {
        if (c == '[') { state = State.CSI; params.setLength(0); return; }
        if (c == ']') { state = State.OSC; params.setLength(0); return; }
        state = State.GROUND;
    }

    private void osc(int c) {
        if (c == 0x07 || c == 0x1B) { state = State.GROUND; return; }
    }

    private void csi(int c) {
        if ((c >= '0' && c <= '9') || c == ';' || c == '?') {
            params.append((char)c); return;
        }
        dispatchCsi((char)c, params.toString());
        state = State.GROUND;
        params.setLength(0);
    }

    private int[] parseParams(String s) {
        if (s.startsWith("?")) s = s.substring(1);
        if (s.isEmpty()) return new int[]{0};
        String[] parts = s.split(";", -1);
        int[] r = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            try { r[i] = parts[i].isEmpty() ? 0 : Integer.parseInt(parts[i]); }
            catch (Exception e) { r[i] = 0; }
        }
        return r;
    }

    private void dispatchCsi(char cmd, String rawParams) {
        int[] p = parseParams(rawParams);
        switch (cmd) {
            case 'm': sgr(p); break;
            case 'H': case 'f': {
                int row = p.length > 0 ? p[0] : 1;
                int col = p.length > 1 ? p[1] : 1;
                buf.cursorY = Math.max(0, Math.min(buf.rows - 1, row - 1));
                buf.cursorX = Math.max(0, Math.min(buf.cols - 1, col - 1));
                break;
            }
            case 'A': buf.cursorY = Math.max(0, buf.cursorY - Math.max(1, p[0])); break;
            case 'B': buf.cursorY = Math.min(buf.rows - 1, buf.cursorY + Math.max(1, p[0])); break;
            case 'C': buf.cursorX = Math.min(buf.cols - 1, buf.cursorX + Math.max(1, p[0])); break;
            case 'D': buf.cursorX = Math.max(0, buf.cursorX - Math.max(1, p[0])); break;
            case 'G': buf.cursorX = Math.max(0, Math.min(buf.cols - 1, p[0] - 1)); break;
            case 'd': buf.cursorY = Math.max(0, Math.min(buf.rows - 1, p[0] - 1)); break;
            case 'J': buf.eraseInDisplay(p[0]); break;
            case 'K': buf.eraseInLine(p[0]); break;
            case 'h': if (rawParams.startsWith("?25")) buf.cursorVisible = true; break;
            case 'l': if (rawParams.startsWith("?25")) buf.cursorVisible = false; break;
        }
    }

    private void sgr(int[] p) {
        if (p.length == 0) p = new int[]{0};
        for (int i = 0; i < p.length; i++) {
            int c = p[i];
            if (c == 0) { curFg = 7; curBg = -1; curAttrs = 0; }
            else if (c == 1) curAttrs |= TerminalBuffer.ATTR_BOLD;
            else if (c == 4) curAttrs |= TerminalBuffer.ATTR_UNDERLINE;
            else if (c == 7) curAttrs |= TerminalBuffer.ATTR_INVERSE;
            else if (c == 22) curAttrs &= ~TerminalBuffer.ATTR_BOLD;
            else if (c == 24) curAttrs &= ~TerminalBuffer.ATTR_UNDERLINE;
            else if (c == 27) curAttrs &= ~TerminalBuffer.ATTR_INVERSE;
            else if (c >= 30 && c <= 37) curFg = (byte)(c - 30);
            else if (c == 39) curFg = 7;
            else if (c >= 40 && c <= 47) curBg = (byte)(c - 40);
            else if (c == 49) curBg = -1;
            else if (c >= 90 && c <= 97) curFg = (byte)(c - 90 + 8);
            else if (c >= 100 && c <= 107) curBg = (byte)(c - 100 + 8);
            else if (c == 38 && i + 2 < p.length && p[i+1] == 5) { curFg = (byte) p[i+2]; i += 2; }
            else if (c == 48 && i + 2 < p.length && p[i+1] == 5) { curBg = (byte) p[i+2]; i += 2; }
        }
    }
}
