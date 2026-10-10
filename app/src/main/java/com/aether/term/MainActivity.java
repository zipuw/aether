package com.aether.term;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.io.*;
import java.nio.charset.StandardCharsets;

public class MainActivity extends Activity implements TerminalView.Host {
    private static final String TAG = "Aether";
    private TerminalView view;
    private EditText hiddenInput;
    private AnsiParser parser;
    private Process shell;
    private OutputStream shellIn;
    private volatile boolean started = false;
    private String prevText = "";

    private TextView logView;
    private ScrollView logScroll;
    private StringBuilder logBuf = new StringBuilder();

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.parseColor("#0A0E14"));

        view = new TerminalView(this);
        view.setHost(this);
        root.addView(view, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // Скрытый EditText под Canvas — сюда попадает весь ввод IME
        hiddenInput = new EditText(this);
        hiddenInput.setBackground(null);
        hiddenInput.setTextColor(Color.TRANSPARENT);
        hiddenInput.setCursorVisible(false);
        hiddenInput.setAlpha(0.01f);
        hiddenInput.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        hiddenInput.setImeOptions(EditorInfo.IME_FLAG_NO_FULLSCREEN
                | EditorInfo.IME_FLAG_NO_EXTRACT_UI
                | EditorInfo.IME_ACTION_NONE);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(1, 1);
        lp.gravity = Gravity.TOP | Gravity.LEFT;
        root.addView(hiddenInput, lp);

        // Полоска лога снизу
        logScroll = new ScrollView(this);
        logScroll.setBackgroundColor(Color.parseColor("#1A0F0F"));
        logView = new TextView(this);
        logView.setTextColor(Color.parseColor("#FFB86C"));
        logView.setTypeface(Typeface.MONOSPACE);
        logView.setTextSize(9);
        logView.setPadding(12, 4, 12, 4);
        logView.setText("лог\n");
        logScroll.addView(logView);
        FrameLayout.LayoutParams lp2 = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 180);
        lp2.gravity = Gravity.BOTTOM;
        root.addView(logScroll, lp2);

        setContentView(root);

        // TextWatcher — единственный источник ввода
        hiddenInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override
            public void afterTextChanged(Editable s) {
                String cur = s.toString();
                if (cur.equals(prevText)) return;

                if (cur.length() > prevText.length()) {
                    String added = cur.substring(prevText.length());
                    log("+ \"" + added.replace("\n", "\\n") + "\"");
                    onInput(added);
                } else if (cur.length() < prevText.length()) {
                    int removed = prevText.length() - cur.length();
                    log("- x" + removed);
                    for (int i = 0; i < removed; i++) onKey(KeyEvent.KEYCODE_DEL, 0);
                }
                prevText = cur;

                if (cur.length() > 40) {
                    hiddenInput.post(() -> {
                        hiddenInput.setText("");
                        prevText = "";
                    });
                }
            }
        });
    }

    private void log(String msg) {
        logBuf.append(msg).append('\n');
        String[] lines = logBuf.toString().split("\n");
        if (lines.length > 10) {
            StringBuilder nb = new StringBuilder();
            for (int i = lines.length - 10; i < lines.length; i++) nb.append(lines[i]).append('\n');
            logBuf = nb;
        }
        final String text = "лог\n" + logBuf;
        logView.post(() -> {
            logView.setText(text);
            logScroll.post(() -> logScroll.fullScroll(ScrollView.FOCUS_DOWN));
        });
    }

    public void logInput(String msg) { log(msg); }

    @Override
    public void onTouch() {
        hiddenInput.requestFocus();
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.showSoftInput(hiddenInput, InputMethodManager.SHOW_IMPLICIT);
        log("touch → focus");
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus && !started) {
            started = true;
            view.postDelayed(this::startShell, 300);
        }
    }

    private void startShell() {
        TerminalBuffer buf = view.getBuffer();
        if (buf == null) { view.postDelayed(this::startShell, 50); return; }
        parser = new AnsiParser(buf);

        try {
            ProcessBuilder pb = new ProcessBuilder("/system/bin/sh", "-i");
            pb.redirectErrorStream(true);
            pb.environment().put("TERM", "xterm-256color");
            pb.environment().put("PATH", "/system/bin:/system/xbin");
            pb.environment().put("HOME", getFilesDir().getAbsolutePath());
            pb.environment().put("PS1", "aether$ ");
            pb.directory(getFilesDir());
            shell = pb.start();
            shellIn = shell.getOutputStream();

            final InputStream is = shell.getInputStream();
            Thread t = new Thread(() -> {
                byte[] bb = new byte[4096];
                try {
                    int n;
                    while ((n = is.read(bb)) > 0) {
                        synchronized (parser) { parser.feed(bb, n); }
                        view.postInvalidateOnAnimation();
                    }
                } catch (IOException e) { Log.e(TAG, "read", e); }
            }, "sh-reader");
            t.setDaemon(true);
            t.start();

            Thread.sleep(150);
            sendBytes("\n".getBytes(StandardCharsets.UTF_8));
            log("[boot] shell started");
        } catch (Exception e) {
            Log.e(TAG, "startShell", e);
            log("[boot] err: " + e.getMessage());
        }
    }

    @Override
    public void onInput(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            echoChar(c);
            if (c == '\n') sendBytes(new byte[]{'\n'});
            else sendBytes(String.valueOf(c).getBytes(StandardCharsets.UTF_8));
        }
    }

    @Override
    public void onKey(int keyCode, int metaState) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_ENTER:
                echoChar('\n');
                sendBytes(new byte[]{'\n'});
                break;
            case KeyEvent.KEYCODE_DEL:
                echoBackspace();
                sendBytes(new byte[]{0x7F});
                break;
            case KeyEvent.KEYCODE_TAB:
                echoChar(' ');
                sendBytes(new byte[]{9});
                break;
            case KeyEvent.KEYCODE_ESCAPE:
                sendBytes(new byte[]{27});
                break;
            case KeyEvent.KEYCODE_DPAD_UP: sendBytes(new byte[]{27,'[','A'}); break;
            case KeyEvent.KEYCODE_DPAD_DOWN: sendBytes(new byte[]{27,'[','B'}); break;
            case KeyEvent.KEYCODE_DPAD_RIGHT: sendBytes(new byte[]{27,'[','C'}); break;
            case KeyEvent.KEYCODE_DPAD_LEFT: sendBytes(new byte[]{27,'[','D'}); break;
        }
    }

    private void echoChar(char c) {
        TerminalBuffer buf = view.getBuffer();
        if (buf == null) return;
        if (c == '\n') {
            buf.cursorX = 0;
            buf.lineFeed();
        } else if (c == '\r') {
            buf.cursorX = 0;
        } else if (c >= 0x20) {
            buf.putChar(c, (byte)7, (byte)-1, (byte)0);
        }
        view.postInvalidateOnAnimation();
    }

    private void echoBackspace() {
        TerminalBuffer buf = view.getBuffer();
        if (buf == null) return;
        if (buf.cursorX > 0) {
            buf.cursorX--;
            buf.chars[buf.cursorY][buf.cursorX] = ' ';
            buf.fg[buf.cursorY][buf.cursorX] = 7;
            buf.bg[buf.cursorY][buf.cursorX] = -1;
            buf.attrs[buf.cursorY][buf.cursorX] = 0;
        }
        view.postInvalidateOnAnimation();
    }

    @Override
    public void onResize(int rows, int cols) {
        if (parser != null && view.getBuffer() != null)
            synchronized (parser) { parser.setBuffer(view.getBuffer()); }
    }

    private void sendBytes(byte[] b) {
        if (shellIn == null) return;
        try { shellIn.write(b); shellIn.flush(); }
        catch (IOException e) { Log.e(TAG, "write", e); }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (shell != null) shell.destroy();
    }
}
