package com.gathin.tvhome;

import android.content.Context;
import android.os.Build;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Keeps the last fatal exception on disk so the next launch can show it full screen. Without this a
 * crash is just a black window on a TV with no log reader, and nobody can say what happened.
 */
final class CrashLog {

    private static final String TAG = "TvHome.Crash";
    private static final String FILE = "last-crash.txt";

    private CrashLog() {
    }

    static void install(final Context context) {
        final Context app = context.getApplicationContext();
        final Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            save(app, thread, error);
            if (previous != null) previous.uncaughtException(thread, error);
        });
    }

    private static File file(Context context) {
        return new File(context.getFilesDir(), FILE);
    }

    static boolean has(Context context) {
        File f = file(context);
        return f.isFile() && f.length() > 0;
    }

    static String read(Context context) {
        File f = file(context);
        if (!f.isFile()) return "";
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] bytes = new byte[(int) f.length()];
            int read = 0;
            while (read < bytes.length) {
                int n = in.read(bytes, read, bytes.length - read);
                if (n < 0) break;
                read += n;
            }
            return new String(bytes, 0, read, "UTF-8");
        } catch (Exception e) {
            return "读取崩溃日志失败：" + e;
        }
    }

    static void clear(Context context) {
        //noinspection ResultOfMethodCallIgnored
        file(context).delete();
    }

    private static void save(Context context, Thread thread, Throwable error) {
        try {
            StringWriter trace = new StringWriter();
            error.printStackTrace(new PrintWriter(trace));
            String when = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date());
            String text = "时间: " + when + "\n"
                    + "线程: " + thread.getName() + "\n"
                    + "机型: " + Build.MANUFACTURER + " " + Build.MODEL + "\n"
                    + "系统: Android " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")\n"
                    + "版本: " + Build.DISPLAY + "\n"
                    + "应用: " + context.getPackageName() + "\n\n"
                    + trace;
            try (FileOutputStream out = new FileOutputStream(file(context))) {
                out.write(text.getBytes("UTF-8"));
            }
        } catch (Throwable ignored) {
            Log.w(TAG, "could not save crash", ignored);
        }
    }
}
