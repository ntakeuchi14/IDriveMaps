package com.idrive.mapscontrol;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;

/**
 * 起動時・アプリ更新時にユーザー補助サービスを ON にし直す。
 * 機種によっては起動完了後にシステムが OFF に戻すことがあるので、30秒後と2分後にも再確認する。
 */
public class BootReceiver extends BroadcastReceiver {
    static final String ACTION_RECHECK = "com.idrive.mapscontrol.RECHECK";

    @Override
    public void onReceive(Context c, Intent intent) {
        String a = intent.getAction();
        AccessibilityKeeper.ensureEnabled(c);
        if (!ACTION_RECHECK.equals(a)) {
            schedule(c, 30_000, 1);
            schedule(c, 120_000, 2);
        }
    }

    private static void schedule(Context c, long delayMs, int requestCode) {
        Intent i = new Intent(c, BootReceiver.class).setAction(ACTION_RECHECK);
        PendingIntent pi = PendingIntent.getBroadcast(c, requestCode, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am != null) {
            am.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + delayMs, pi);
        }
    }
}
