package com.idrive.mapscontrol;

import android.Manifest;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.pm.PackageManager;
import android.provider.Settings;

/**
 * ユーザー補助サービスを自分で ON にし直す。
 * 事前に adb で WRITE_SECURE_SETTINGS を付与しておく必要がある:
 *   adb shell pm grant com.idrive.mapscontrol android.permission.WRITE_SECURE_SETTINGS
 */
public final class AccessibilityKeeper {
    private AccessibilityKeeper() {}

    public static boolean canWrite(Context c) {
        return c.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS)
                == PackageManager.PERMISSION_GRANTED;
    }

    private static String fullName(Context c) {
        return new ComponentName(c, MapsControlService.class).flattenToString();
    }

    private static boolean isOurs(Context c, String entry) {
        ComponentName cn = ComponentName.unflattenFromString(entry.trim());
        return cn != null && cn.getPackageName().equals(c.getPackageName())
                && cn.getClassName().equals(MapsControlService.class.getName());
    }

    /** 設定上 ON になっているか */
    public static boolean isListed(Context c) {
        String list = Settings.Secure.getString(c.getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (list == null) return false;
        for (String e : list.split(":")) if (isOurs(c, e)) return true;
        return false;
    }

    /**
     * OFF なら ON にする。ON 表示なのにサービスが動いていない場合は一度外して付け直す (再接続させる)。
     * @return 何かしたらその内容、何もしなければ null
     */
    public static String ensureEnabled(Context c) {
        if (!canWrite(c)) return null;
        boolean listed = isListed(c);
        boolean running = MapsControlService.instance != null;
        if (listed && running) return null;
        try {
            ContentResolver cr = c.getContentResolver();
            String list = Settings.Secure.getString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            StringBuilder others = new StringBuilder();
            if (list != null) {
                for (String e : list.split(":")) {
                    if (e.trim().isEmpty() || isOurs(c, e)) continue;
                    if (others.length() > 0) others.append(':');
                    others.append(e.trim());
                }
            }
            if (listed) {
                // 一度外してから付け直すとシステムが再接続する
                Settings.Secure.putString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, others.toString());
            }
            String newList = others.length() > 0 ? others + ":" + fullName(c) : fullName(c);
            Settings.Secure.putString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, newList);
            Settings.Secure.putInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 1);
            String msg = listed ? "ユーザー補助サービスを再接続しました" : "ユーザー補助サービスを自動で ON にしました";
            AppLog.add(msg);
            return msg;
        } catch (Exception e) {
            AppLog.add("⚠ ユーザー補助の自動 ON に失敗: " + e.getMessage());
            return null;
        }
    }
}
