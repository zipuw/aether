package com.aether.term;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.io.*;
import java.nio.charset.StandardCharsets;

public class MainActivity extends Activity implements TerminalView.Host {
    private static final String TAG = "Aether";
    private TerminalView view;
    private AnsiParser parser;
    private Process shell;
    private OutputStream shellIn;
    private volatile boolean started = false;

    private TextView logView;
    private ScrollView logScroll;
    private StringBuilder logBuf = new StringBuilder();

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.parseColor("#0A0E14"));

        // Терминал сверху
        view = new TerminalView(this);
        view.setHost(this);
        LinearLayout.LayoutParams termParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        root.addView(view, termParams);

        // Лог снизу — фиксированной высоты, прокручиваемый
        logScroll = new ScrollView(this);
        logScroll.setBackgroundColor(Color.parseColor("#1A0F0F"));
        logView = new TextView(this);
        logView.setTextColor(Color.parseColor("#FFB86C"));
        logView.setTypeface(Typeface.MONOSPACE);
        logView.setTextSize(10);
        logView.setPadding(16, 8, 16, 8);
        logView.setText("лог событий ввода:\n");
        logScroll.addView(logView);
        LinearLayout.LayoutParams logParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 260);
        root.addView(logScroll, logParams);

        setContentView(root);
        view.requestFocus();
    }

    private void log(String msg) {
        logBuf.append(msg).append('\n');
        // держим только последние 30 строк
        String[] lines = logBuf.toString().split("\n");
        if (lines.length > 30) {
            StringBuilder nb = new StringBuilder();
            for (int i = lines.length - 30; i < lines.length; i++) nb.append(lines[i]).append('\n');
            logBuf = nb;
        }
        final String text = "лог событий ввода:\n" + logBuf;
        logView.post(() -> {
            logView.setText(text);
            logScroll.post(() -> logScroll.fullScroll(ScrollView.FOCUS_DOWN));
        });
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
        log("onInput: \"" + text.replace("\n", "\\n") + "\"");
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            echoChar(c);
            sendBytes(String.valueOf(c).getBytes(StandardCharsets.UTF_8));
        }
    }

    @Override
    public void onKey(int keyCode, int metaState) {
        log("onKey: code=" + keyCode);
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

    public void logInput(String msg) {
        log(msg);
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
