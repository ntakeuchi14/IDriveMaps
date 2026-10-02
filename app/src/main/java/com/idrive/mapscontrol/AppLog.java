package com.idrive.mapscontrol;

import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Locale;

/** 画面表示用の簡易ログ (メモリ上に直近200行) */
public final class AppLog {
    private static final int MAX = 200;
    private static final ArrayDeque<String> LINES = new ArrayDeque<>();
    private static final SimpleDateFormat FMT = new SimpleDateFormat("HH:mm:ss", Locale.US);

    private AppLog() {}

    public static synchronized void add(String msg) {
        LINES.addFirst(FMT.format(new Date()) + "  " + msg);
        while (LINES.size() > MAX) LINES.removeLast();
        android.util.Log.i("IDriveMaps", msg);
    }

    public static synchronized String dump() {
        StringBuilder sb = new StringBuilder();
        for (String s : LINES) sb.append(s).append('\n');
        return sb.toString();
    }

    public static synchronized void clear() { LINES.clear(); }
}
