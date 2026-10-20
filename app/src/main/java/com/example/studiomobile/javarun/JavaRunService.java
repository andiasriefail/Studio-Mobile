package com.example.studiomobile.javarun;

import android.app.Service;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.Process;
import android.os.RemoteException;
import dalvik.system.DexClassLoader;
import java.io.ByteArrayInputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;

/**
 * Runs one compiled Java program in its own process (see android:process in the manifest), so that System.exit(), infinite loops and static state
 * cannot touch the editor, and Stop simply kills the process. Output travels back to {@link JavaFileRunner} as messages.
 */
public final class JavaRunService extends Service {
    static final int MSG_RUN = 1, MSG_STARTED = 2, MSG_OUT = 3, MSG_DONE = 4;
    static final String KEY_DEX = "dex", KEY_CLASSES = "classes", KEY_ARGS = "args", KEY_TEXT = "text";

    private boolean started;
    private final Messenger messenger = new Messenger(new Handler(Looper.getMainLooper(), message -> {
        if (message.what == MSG_RUN && message.replyTo != null && !started) { started = true; start(message.getData(), message.replyTo); }
        return true;
    }));

    @Override public IBinder onBind(Intent intent) { return messenger.getBinder(); }

    private static void send(Messenger to, int what, int arg1, String text) {
        try {
            Message message = Message.obtain(null, what, arg1, 0);
            if (text != null) { Bundle data = new Bundle(); data.putString(KEY_TEXT, text); message.setData(data); }
            to.send(message);
        } catch (RemoteException gone) { /* the editor is gone; nothing left to tell */ }
    }

    /** System.out / System.err: every write goes straight to the editor. */
    private static PrintStream forward(Messenger reply) {
        return new PrintStream(new OutputStream() {
            @Override public void write(int b) { write(new byte[] {(byte) b}, 0, 1); }
            @Override public void write(byte[] bytes, int offset, int length) { if (length > 0) send(reply, MSG_OUT, 0, new String(bytes, offset, length, StandardCharsets.UTF_8)); }
        }, true);
    }

    private void start(Bundle request, Messenger reply) {
        send(reply, MSG_STARTED, Process.myPid(), null);
        final String[] dex = request.getStringArray(KEY_DEX), classes = request.getStringArray(KEY_CLASSES), args = request.getStringArray(KEY_ARGS);
        ThreadGroup group = new ThreadGroup("studio-run") {
            @Override public void uncaughtException(Thread thread, Throwable error) {   // like the JVM: another thread dying does not end the program
                System.err.print("Exception in thread \"" + thread.getName() + "\" ");
                error.printStackTrace();
            }
        };
        Thread worker = new Thread(group, () -> {
            int exitCode;
            PrintStream out = forward(reply);
            System.setOut(out); System.setErr(out);
            System.setIn(new ByteArrayInputStream(new byte[0]));
            try {
                for (String file : dex) new java.io.File(file).setReadOnly();   // Android 14+ refuses to load writable dex files
                ClassLoader loader = new DexClassLoader(String.join(java.io.File.pathSeparator, dex), null, null, ClassLoader.getSystemClassLoader().getParent());
                Class<?> type = JavaEntryPoint.locate(loader, new ArrayList<>(Arrays.asList(classes)));
                exitCode = JavaEntryPoint.run(type, args == null ? new String[0] : args);
                waitForOtherThreads(group, Thread.currentThread());
            } catch (Throwable failure) {
                System.err.println(failure instanceof IllegalStateException ? failure.getMessage() : "Could not start the program: " + failure);
                exitCode = 1;
            }
            out.flush();
            send(reply, MSG_DONE, exitCode, null);
            new Handler(Looper.getMainLooper()).postDelayed(() -> Process.killProcess(Process.myPid()), 300);   // the next run starts in a clean process
        }, "main");
        worker.start();
    }

    /** A JVM only ends when its non-daemon threads have ended; wait for the ones the program started. */
    private static void waitForOtherThreads(ThreadGroup group, Thread self) throws InterruptedException {
        while (true) {
            Thread[] threads = new Thread[group.activeCount() + 8];
            int count = group.enumerate(threads);
            Thread pending = null;
            for (int i = 0; i < count; i++) if (threads[i] != self && !threads[i].isDaemon() && threads[i].isAlive()) { pending = threads[i]; break; }
            if (pending == null) return;
            pending.join();
        }
    }
}
