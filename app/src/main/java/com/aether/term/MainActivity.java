package com.aether.term;

import android.app.Activity;
import android.os.Bundle;
import android.util.Log;
import android.view.KeyEvent;
import java.io.*;
import java.nio.charset.StandardCharsets;

public class MainActivity extends Activity implements TerminalView.Host {
    private static final String TAG = "Aether";
    private TerminalView view;
    private AnsiParser parser;
    private Process shell;
    private OutputStream shellIn;
    private volatile boolean started = false;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        view = new TerminalView(this);
        view.setHost(this);
        setContentView(view);
        view.requestFocus();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus && !started) { started = true; view.post(this::startShell); }
    }

    private void startShell() {
        TerminalBuffer buf = view.getBuffer();
        if (buf == null) { view.postDelayed(this::startShell, 50); return; }
        parser = new AnsiParser(buf);

        try {
            ProcessBuilder pb = new ProcessBuilder("/system/bin/sh");
            pb.redirectErrorStream(true);
            pb.environment().put("TERM", "xterm-256color");
            pb.environment().put("PATH", "/system/bin:/system/xbin");
            pb.environment().put("HOME", getFilesDir().getAbsolutePath());
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

            sendBytes("export PS1='aether$ '\n".getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            Log.e(TAG, "startShell", e);
        }
    }

    @Override public void onInput(String text) { sendBytes(text.getBytes(StandardCharsets.UTF_8)); }

    @Override
    public void onKey(int keyCode, int metaState) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_ENTER: sendBytes(new byte[]{'\n'}); break;
            case KeyEvent.KEYCODE_DEL: sendBytes(new byte[]{127}); break;
            case KeyEvent.KEYCODE_TAB: sendBytes(new byte[]{9}); break;
            case KeyEvent.KEYCODE_ESCAPE: sendBytes(new byte[]{27}); break;
            case KeyEvent.KEYCODE_DPAD_UP: sendBytes(new byte[]{27,'[','A'}); break;
            case KeyEvent.KEYCODE_DPAD_DOWN: sendBytes(new byte[]{27,'[','B'}); break;
            case KeyEvent.KEYCODE_DPAD_RIGHT: sendBytes(new byte[]{27,'[','C'}); break;
            case KeyEvent.KEYCODE_DPAD_LEFT: sendBytes(new byte[]{27,'[','D'}); break;
        }
    }

    @Override public void onResize(int rows, int cols) {
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
