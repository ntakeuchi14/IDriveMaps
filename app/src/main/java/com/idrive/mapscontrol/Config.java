package com.idrive.mapscontrol;

import android.content.Context;
import android.content.SharedPreferences;

/** 設定値 (SharedPreferences)。初期値は元の Tasker プロジェクト Maps-V4 と同じ。 */
public final class Config {
    private static final String PREFS = "settings";

    public static final String MAPS_PKG = "com.google.android.apps.maps";

    /** Tasker の Logcat Entry の Component (= ログタグ) */
    public static final String DEF_TAG = "KswMcuListener";

    /** 「押し込み」でタップするボタンの文字列 (上から順に探し、最初に見つかった1つを押す) */
    public static final String DEF_PUSH_TEXTS =
            "Start\n開始\nナビ開始\nCommute\n通勤\nRe-center\n現在地に戻る\n再センタリング\n中心に戻す\nPendeln\nZentrieren";

    /** Tasker の座標を作った画面サイズ (KSW 1280x480)。実画面サイズに合わせて自動で拡大縮小する */
    public static final int BASE_W = 1280;
    public static final int BASE_H = 480;

    private Config() {}

    private static SharedPreferences p(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static String tag(Context c) { return p(c).getString("tag", DEF_TAG); }
    public static String pushTexts(Context c) { return p(c).getString("push", DEF_PUSH_TEXTS); }
    public static boolean enabled(Context c) { return p(c).getBoolean("enabled", true); }
    public static boolean scale(Context c) { return p(c).getBoolean("scale", true); }
    public static boolean useRoot(Context c) { return p(c).getBoolean("root", false); }
    public static boolean debug(Context c) { return p(c).getBoolean("debug", false); }

    public static void save(Context c, String tag, String push, boolean enabled,
                            boolean scale, boolean root, boolean debug) {
        p(c).edit()
                .putString("tag", tag)
                .putString("push", push)
                .putBoolean("enabled", enabled)
                .putBoolean("scale", scale)
                .putBoolean("root", root)
                .putBoolean("debug", debug)
                .apply();
    }
}
