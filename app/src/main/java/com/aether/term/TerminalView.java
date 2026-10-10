package com.aether.term;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.text.InputType;
import android.util.AttributeSet;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.inputmethod.BaseInputConnection;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethodManager;

public class TerminalView extends View {
    public interface Host {
        void onInput(String text);
        void onKey(int keyCode, int metaState);
        void onResize(int rows, int cols);
    }

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bgPaint = new Paint();
    private TerminalBuffer buffer;
    private Host host;
    private float cellW, cellH, baseY;
    private int padX = 12, padY = 12;

    private final int[] PALETTE = {
        0xFF1A1A1A, 0xFFCC0000, 0xFF4E9A06, 0xFFC4A000,
        0xFF3465A4, 0xFF75507B, 0xFF06989A, 0xFFD3D7CF,
        0xFF555753, 0xFFEF2929, 0xFF8AE234, 0xFFFCE94F,
        0xFF729FCF, 0xFFAD7FA8, 0xFF34E2E2, 0xFFEEEEEC
    };
    private final int BG = 0xFF0A0E14;
    private final int CURSOR = 0xFF00FF9F;

    public TerminalView(Context c) { super(c); init(); }
    public TerminalView(Context c, AttributeSet a) { super(c, a); init(); }

    private void init() {
        paint.setTypeface(Typeface.MONOSPACE);
        paint.setTextSize(32f);
        paint.setColor(Color.WHITE);
        bgPaint.setColor(BG);
        setBackgroundColor(BG);
        setFocusable(true);
        setFocusableInTouchMode(true);
    }

    public void setHost(Host h) { this.host = h; }
    public TerminalBuffer getBuffer() { return buffer; }

    private void measureCells() {
        Paint.FontMetrics fm = paint.getFontMetrics();
        cellH = fm.descent - fm.ascent;
        baseY = -fm.ascent;
        cellW = paint.measureText("M");
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        measureCells();
        int cols = Math.max(20, (int)((w - padX * 2) / cellW));
        int rows = Math.max(8, (int)((h - padY * 2) / cellH));
        if (buffer == null) buffer = new TerminalBuffer(rows, cols);
        else buffer.resizeKeep(rows, cols);
        if (host != null) host.onResize(rows, cols);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (buffer == null) return;
        canvas.drawColor(BG);

        StringBuilder sb = new StringBuilder(buffer.cols);
        for (int y = 0; y < buffer.rows; y++) {
            int x = 0;
            float fy = padY + baseY + y * cellH;
            while (x < buffer.cols) {
                int f0 = buffer.fg[y][x], b0 = buffer.bg[y][x], a0 = buffer.attrs[y][x];
                int x0 = x;
                sb.setLength(0);
                while (x < buffer.cols && buffer.fg[y][x] == f0 && buffer.bg[y][x] == b0 && buffer.attrs[y][x] == a0) {
                    sb.append(buffer.chars[y][x]); x++;
                }
                int bgCol = b0 < 0 ? BG : PALETTE[b0 & 0x0F];
                int fgCol = PALETTE[f0 & 0x0F];
                if ((a0 & TerminalBuffer.ATTR_INVERSE) != 0) {
                    int t = fgCol; fgCol = bgCol; bgCol = t;
                }
                if (bgCol != BG) {
                    bgPaint.setColor(bgCol);
                    canvas.drawRect(padX + x0 * cellW, fy - baseY, padX + x * cellW, fy - baseY + cellH, bgPaint);
                }
                paint.setColor(fgCol);
                paint.setFakeBoldText((a0 & TerminalBuffer.ATTR_BOLD) != 0);
                paint.setUnderlineText((a0 & TerminalBuffer.ATTR_UNDERLINE) != 0);
                canvas.drawText(sb.toString(), padX + x0 * cellW, fy, paint);
            }
        }
        paint.setFakeBoldText(false);
        paint.setUnderlineText(false);

        if (buffer.cursorVisible) {
            float cx = padX + buffer.cursorX * cellW;
            float cy = padY + buffer.cursorY * cellH;
            paint.setColor(CURSOR);
            paint.setAlpha(120);
            canvas.drawRect(cx, cy, cx + cellW, cy + cellH, paint);
            paint.setAlpha(255);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (e.getAction() == MotionEvent.ACTION_DOWN) {
            requestFocus();
            showKeyboard();
            return true;
        }
        return super.onTouchEvent(e);
    }

    public void showKeyboard() {
        InputMethodManager imm = (InputMethodManager) getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) {
            imm.restartInput(this);
            imm.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT);
        }
    }

    @Override
    public boolean onCheckIsTextEditor() { return true; }

    @Override
    public InputConnection onCreateInputConnection(EditorInfo outAttrs) {
        // Обычный текст, чтобы Gboard слал символы через commitText/setComposingText
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                | InputType.TYPE_TEXT_FLAG_MULTI_LINE;
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_FULLSCREEN
                | EditorInfo.IME_FLAG_NO_EXTRACT_UI
                | EditorInfo.IME_ACTION_NONE;

        // Важно: false — чтобы BaseInputConnection не дублировал текст через KeyEvents
        return new BaseInputConnection(this, false) {
            @Override
            public boolean setComposingText(CharSequence text, int newCursorPosition) {
                // Отправляем символы по мере набора (мгновенное эхо)
                if (host != null && text.length() > 0) {
                    // Отправляем только новые символы (последний введённый)
                    String s = text.toString();
                    if (s.length() > 0) {
                        host.onInput(s.substring(s.length() - 1));
                    }
                }
                return true;
            }

            @Override
            public boolean commitText(CharSequence text, int newCursorPosition) {
                // Финальная отправка (пробел, Enter)
                // Ничего не делаем, т.к. символы уже отправлены в setComposingText
                return true;
            }

            @Override
            public boolean setComposingRegion(int start, int end) { return true; }

            @Override
            public boolean finishComposingText() { return true; }

            @Override
            public boolean sendKeyEvent(KeyEvent event) {
                if (event.getAction() == KeyEvent.ACTION_DOWN && host != null) {
                    host.onKey(event.getKeyCode(), event.getMetaState());
                }
                return true;
            }

            @Override
            public boolean deleteSurroundingText(int before, int after) {
                if (host != null) {
                    for (int i = 0; i < before; i++) host.onKey(KeyEvent.KEYCODE_DEL, 0);
                }
                return true;
            }
        };
    }
}
