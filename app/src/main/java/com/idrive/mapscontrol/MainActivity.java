package com.idrive.mapscontrol;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/** 設定・状態確認・テスト用の画面 */
public class MainActivity extends Activity {

    private final Handler h = new Handler(Looper.getMainLooper());
    private TextView statusView, logView;
    private EditText tagEdit, pushEdit;
    private CheckBox enabledCb, scaleCb, rootCb, debugCb;
    private LinearLayout col;

    private final Runnable refresher = new Runnable() {
        @Override public void run() {
            refresh();
            h.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        ScrollView sv = new ScrollView(this);
        col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        col.setPadding(pad, pad, pad, pad);
        sv.addView(col);
        setContentView(sv);

        header("状態");
        statusView = text("", 15);

        button("① ユーザー補助の設定を開く（「iDrive Maps コントロール」をON）",
                v -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        text("② ログ読み取り権限（PCから1回だけ実行）:\nadb shell pm grant " + getPackageName()
                + " android.permission.READ_LOGS", 13).setTextIsSelectable(true);
        button("②' root化済みならここで権限付与", v -> grantByRoot());

        header("設定");
        enabledCb = check("有効にする", Config.enabled(this));
        text("監視するログタグ", 13);
        tagEdit = edit(Config.tag(this), false);
        text("押し込み時にタップするボタンの文字（1行に1つ・上から優先）", 13);
        pushEdit = edit(Config.pushTexts(this), true);
        scaleCb = check("座標を画面サイズに合わせて自動調整（1280×480基準）", Config.scale(this));
        rootCb = check("root(su)でログを読む", Config.useRoot(this));
        debugCb = check("デバッグログを表示（受信した全コマンド）", Config.debug(this));
        LinearLayout row = new LinearLayout(this);
        row.addView(mkButton("保存", v -> save()));
        row.addView(mkButton("初期値に戻す", v -> resetDefaults()));
        col.addView(row);

        header("テスト（ボタンを押してから5秒以内にGoogleマップへ切替）");
        LinearLayout r1 = new LinearLayout(this);
        LinearLayout r2 = new LinearLayout(this);
        for (int code = 1; code <= 7; code++) {
            final int c = code;
            (code <= 4 ? r1 : r2).addView(mkButton(MapsControlService.name(c), v -> test(c)));
        }
        col.addView(r1);
        col.addView(r2);

        header("ログ");
        button("ログをクリア", v -> { AppLog.clear(); refresh(); });
        logView = text("", 12);
        logView.setTypeface(Typeface.MONOSPACE);
        logView.setTextIsSelectable(true);
    }

    @Override protected void onResume() { super.onResume(); h.post(refresher); }
    @Override protected void onPause() { super.onPause(); h.removeCallbacks(refresher); }

    private void refresh() {
        boolean svc = MapsControlService.instance != null;
        boolean perm = checkSelfPermission(android.Manifest.permission.READ_LOGS)
                == PackageManager.PERMISSION_GRANTED;
        statusView.setText(
                "ユーザー補助サービス: " + (svc ? "✅ 動作中" : "❌ OFF（①でONにしてください）") + "\n"
              + "READ_LOGS 権限: " + (perm ? "✅ あり" : (Config.useRoot(this) ? "— (root使用)" : "❌ なし（②を実行）")) + "\n"
              + "監視: " + (svc ? MapsControlService.status : "停止中"));
        logView.setText(AppLog.dump());
    }

    private void save() {
        Config.save(this, tagEdit.getText().toString().trim(), pushEdit.getText().toString(),
                enabledCb.isChecked(), scaleCb.isChecked(), rootCb.isChecked(), debugCb.isChecked());
        MapsControlService s = MapsControlService.instance;
        if (s != null) s.restartMonitor();
        Toast.makeText(this, "保存しました", Toast.LENGTH_SHORT).show();
    }

    private void resetDefaults() {
        tagEdit.setText(Config.DEF_TAG);
        pushEdit.setText(Config.DEF_PUSH_TEXTS);
        enabledCb.setChecked(true);
        scaleCb.setChecked(true);
        save();
    }

    private void test(int code) {
        MapsControlService s = MapsControlService.instance;
        if (s == null) {
            Toast.makeText(this, "先にユーザー補助サービスをONにしてください", Toast.LENGTH_LONG).show();
            return;
        }
        Toast.makeText(this, "5秒後に「" + MapsControlService.name(code) + "」を実行します", Toast.LENGTH_SHORT).show();
        h.postDelayed(() -> {
            MapsControlService s2 = MapsControlService.instance;
            if (s2 != null) s2.onIDrive(code, false);
        }, 5000);
    }

    private void grantByRoot() {
        new Thread(() -> {
            String msg;
            try {
                Process p = Runtime.getRuntime().exec(new String[]{"su", "-c",
                        "pm grant " + getPackageName() + " android.permission.READ_LOGS"});
                int rc = p.waitFor();
                msg = rc == 0 ? "権限を付与しました。アプリを再起動してください" : "失敗しました (code " + rc + ")";
            } catch (Exception e) {
                msg = "su を実行できません: " + e.getMessage();
            }
            final String m = msg;
            h.post(() -> {
                Toast.makeText(this, m, Toast.LENGTH_LONG).show();
                AppLog.add(m);
            });
        }).start();
    }

    // ---- UI helpers

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    private void header(String s) {
        TextView t = text(s, 17);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(0, dp(18), 0, dp(6));
    }

    private TextView text(String s, int sp) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setPadding(0, dp(4), 0, dp(4));
        col.addView(t);
        return t;
    }

    private EditText edit(String s, boolean multi) {
        EditText e = new EditText(this);
        e.setText(s);
        if (multi) {
            e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
            e.setMinLines(4);
        } else {
            e.setSingleLine(true);
        }
        col.addView(e);
        return e;
    }

    private CheckBox check(String s, boolean v) {
        CheckBox c = new CheckBox(this);
        c.setText(s);
        c.setChecked(v);
        col.addView(c);
        return c;
    }

    private Button button(String s, View.OnClickListener l) {
        Button b = mkButton(s, l);
        b.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        col.addView(b);
        return b;
    }

    private Button mkButton(String s, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(s);
        b.setAllCaps(false);
        b.setOnClickListener(l);
        return b;
    }
}
