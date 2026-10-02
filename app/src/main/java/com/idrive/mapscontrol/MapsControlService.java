package com.idrive.mapscontrol;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Path;
import android.graphics.Point;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayDeque;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 常駐サービス本体。Tasker の「Maps-V4」プロジェクトと同じことを行う:
 *   1. logcat の KswMcuListener を監視し、iDrive の操作 (cmdType:A1 data:17-xx-01) を検出
 *   2. Google マップが表示中なら、対応するスワイプ / タップ / ピンチを実行
 *
 *   data 17-01-01 上   → Swipe Up    (500,200)→(500,300)
 *   data 17-02-01 下   → Swipe Down  (500,200)→(500,100)
 *   data 17-03-01 右   → Swipe Right (500,200)→(600,200)
 *   data 17-04-01 左   → Swipe Left  (500,200)→(400,200)
 *   data 17-05-01 押す → 「開始/Start」「通勤」「現在地に戻る」等のボタンをタップ
 *   data 17-06-01 左回し → ズームアウト (2本指タップ ×2)
 *   data 17-07-01 右回し → ズームイン   (600,240 をダブルタップ)
 */
public class MapsControlService extends AccessibilityService {

    private static final Pattern MCU = Pattern.compile("cmdType:A1\\s*-\\s*data:17-0([1-7])-01");
    private static final int MAX_QUEUE = 4;

    static volatile MapsControlService instance;
    static volatile String status = "停止中";

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ArrayDeque<GestureDescription> queue = new ArrayDeque<>();
    private boolean busy = false;

    private volatile boolean running = false;
    private volatile Process logcat;
    private Thread reader;

    // ---------------------------------------------------------------- lifecycle

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        startMonitor();
        AppLog.add("サービス開始");
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) { /* 画面判定は実行時に行う */ }

    @Override
    public void onInterrupt() {}

    @Override
    public void onDestroy() {
        stopMonitor();
        instance = null;
        status = "停止中";
        AppLog.add("サービス停止");
        super.onDestroy();
    }

    /** 設定変更時に MainActivity から呼ぶ */
    void restartMonitor() {
        stopMonitor();
        startMonitor();
    }

    // ---------------------------------------------------------------- logcat 監視

    private void startMonitor() {
        running = true;
        reader = new Thread(this::monitorLoop, "logcat-reader");
        reader.start();
    }

    private void stopMonitor() {
        running = false;
        Process p = logcat;
        if (p != null) p.destroy();
        if (reader != null) reader.interrupt();
    }

    private void monitorLoop() {
        while (running) {
            Context c = this;
            String tag = Config.tag(c).trim();
            boolean root = Config.useRoot(c);
            boolean hasPerm = checkSelfPermission(android.Manifest.permission.READ_LOGS)
                    == PackageManager.PERMISSION_GRANTED;
            if (!hasPerm && !root) {
                status = "⚠ READ_LOGS 権限がありません (adbで付与するか root を使用)";
            } else {
                status = "監視中 (" + (root ? "root" : "READ_LOGS") + ", tag=" + tag + ")";
            }
            String cmd = "logcat -v brief -T 1 -s " + tag;
            try {
                Process p = root
                        ? Runtime.getRuntime().exec(new String[]{"su", "-c", cmd})
                        : Runtime.getRuntime().exec(new String[]{"logcat", "-v", "brief", "-T", "1", "-s", tag});
                logcat = p;
                BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()));
                String line;
                while (running && (line = br.readLine()) != null) {
                    handleLine(line);
                }
                p.destroy();
            } catch (Exception e) {
                if (running) {
                    status = "⚠ logcat 起動失敗: " + e.getMessage();
                    AppLog.add(status);
                }
            }
            if (running) {
                try { Thread.sleep(2000); } catch (InterruptedException ignored) { }
            }
        }
    }

    private void handleLine(String line) {
        if (Config.debug(this) && line.contains("cmdType")) AppLog.add("LOG " + line);
        Matcher m = MCU.matcher(line);
        if (!m.find()) return;
        final int code = m.group(1).charAt(0) - '0';
        main.post(() -> onIDrive(code, false));
    }

    // ---------------------------------------------------------------- 動作

    /** @param force true ならマップ表示判定を省略 (テスト用) */
    void onIDrive(int code, boolean force) {
        if (!Config.enabled(this)) return;
        if (!force && !mapsOnScreen()) {
            if (Config.debug(this)) AppLog.add("iDrive " + name(code) + " (マップ非表示のため無視)");
            return;
        }
        AppLog.add("iDrive " + name(code));
        switch (code) {
            case 1: swipe(500, 200, 500, 300); break;   // 上
            case 2: swipe(500, 200, 500, 100); break;   // 下
            case 3: swipe(500, 200, 600, 200); break;   // 右
            case 4: swipe(500, 200, 400, 200); break;   // 左
            case 5: push(); break;                      // 押し込み
            case 6: zoomOut(); break;                   // 左回し
            case 7: zoomIn(); break;                    // 右回し
        }
    }

    static String name(int code) {
        switch (code) {
            case 1: return "上";
            case 2: return "下";
            case 3: return "右";
            case 4: return "左";
            case 5: return "押し込み";
            case 6: return "ズームアウト";
            case 7: return "ズームイン";
            default: return "?";
        }
    }

    /** Google マップが前面 (または分割画面で表示中) か */
    private boolean mapsOnScreen() {
        return findMapsRoot() != null;
    }

    private AccessibilityNodeInfo findMapsRoot() {
        AccessibilityNodeInfo r = getRootInActiveWindow();
        if (r != null && Config.MAPS_PKG.contentEquals(nz(r.getPackageName()))) return r;
        try {
            for (AccessibilityWindowInfo w : getWindows()) {
                if (w.getType() != AccessibilityWindowInfo.TYPE_APPLICATION) continue;
                AccessibilityNodeInfo wr = w.getRoot();
                if (wr != null && Config.MAPS_PKG.contentEquals(nz(wr.getPackageName()))) return wr;
            }
        } catch (Exception ignored) { }
        return null;
    }

    private static CharSequence nz(CharSequence s) { return s == null ? "" : s; }

    // ---- 座標変換 (1280x480 基準 → 実画面)

    private float sx = 1f, sy = 1f;

    private void updateScale() {
        if (!Config.scale(this)) { sx = sy = 1f; return; }
        WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        Point size = new Point();
        wm.getDefaultDisplay().getRealSize(size);
        sx = size.x / (float) Config.BASE_W;
        sy = size.y / (float) Config.BASE_H;
    }

    private float X(float x) { return x * sx; }
    private float Y(float y) { return y * sy; }

    // ---- ジェスチャー

    private void swipe(float x1, float y1, float x2, float y2) {
        updateScale();
        Path p = new Path();
        p.moveTo(X(x1), Y(y1));
        p.lineTo(X(x2), Y(y2));
        enqueue(new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(p, 0, 220))
                .build());
    }

    /** Zoom In Tap: (600,240) をダブルタップ */
    private void zoomIn() {
        updateScale();
        GestureDescription.Builder b = new GestureDescription.Builder();
        b.addStroke(new GestureDescription.StrokeDescription(point(600, 240), 0, 30));
        b.addStroke(new GestureDescription.StrokeDescription(point(600, 240), 110, 30));
        enqueue(b.build());
    }

    /** Zoom Out Tap: (640,240) を中心に間隔200の2本指タップ ×2 */
    private void zoomOut() {
        updateScale();
        for (int i = 0; i < 2; i++) {
            GestureDescription.Builder b = new GestureDescription.Builder();
            b.addStroke(new GestureDescription.StrokeDescription(point(540, 240), 0, 50));
            b.addStroke(new GestureDescription.StrokeDescription(point(740, 240), 0, 50));
            enqueue(b.build());
        }
    }

    private Path point(float x, float y) {
        Path p = new Path();
        p.moveTo(X(x), Y(y));
        return p;
    }

    private void tapAbsolute(float x, float y) {
        Path p = new Path();
        p.moveTo(x, y);
        enqueue(new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(p, 0, 40))
                .build());
    }

    private void enqueue(GestureDescription g) {
        if (queue.size() >= MAX_QUEUE) queue.pollFirst();  // ノブを速く回したとき遅延が溜まらないように
        queue.addLast(g);
        if (!busy) next();
    }

    private void next() {
        GestureDescription g = queue.pollFirst();
        if (g == null) { busy = false; return; }
        busy = true;
        GestureResultCallback cb = new GestureResultCallback() {
            @Override public void onCompleted(GestureDescription d) { main.postDelayed(MapsControlService.this::next, 40); }
            @Override public void onCancelled(GestureDescription d) { main.postDelayed(MapsControlService.this::next, 40); }
        };
        if (!dispatchGesture(g, cb, main)) {
            AppLog.add("⚠ ジェスチャー実行失敗");
            busy = false;
            next();
        }
    }

    // ---- 押し込み: 文字でボタンを探してタップ (Tasker の Tap Method: Text 相当)

    private void push() {
        AccessibilityNodeInfo root = findMapsRoot();
        if (root == null) root = getRootInActiveWindow();
        if (root == null) return;

        String[] texts = Config.pushTexts(this).split("[\\n,]");
        // 1回目: 完全一致, 2回目: 部分一致
        for (int pass = 0; pass < 2; pass++) {
            for (String t : texts) {
                t = t.trim();
                if (t.isEmpty()) continue;
                List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByText(t);
                if (nodes == null) continue;
                for (AccessibilityNodeInfo n : nodes) {
                    if (!n.isVisibleToUser()) continue;
                    if (!matches(n, t, pass == 0)) continue;
                    if (click(n)) {
                        AppLog.add("  → 「" + t + "」をタップ");
                        return;
                    }
                }
            }
        }
        AppLog.add("  → タップ対象が見つかりません。画面上のボタン: " + listLabels(root));
    }

    private static boolean matches(AccessibilityNodeInfo n, String t, boolean exact) {
        String a = n.getText() == null ? "" : n.getText().toString().trim();
        String b = n.getContentDescription() == null ? "" : n.getContentDescription().toString().trim();
        if (exact) return a.equalsIgnoreCase(t) || b.equalsIgnoreCase(t);
        String tl = t.toLowerCase();
        return a.toLowerCase().contains(tl) || b.toLowerCase().contains(tl);
    }

    private boolean click(AccessibilityNodeInfo n) {
        AccessibilityNodeInfo c = n;
        for (int i = 0; c != null && i < 6; i++) {
            if (c.isClickable() && c.isEnabled()) {
                if (c.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true;
                break;
            }
            c = c.getParent();
        }
        // クリックできない場合は表示位置の中心をタップ
        Rect r = new Rect();
        n.getBoundsInScreen(r);
        if (r.width() <= 0 || r.height() <= 0) return false;
        tapAbsolute(r.exactCenterX(), r.exactCenterY());
        return true;
    }

    /** 見つからなかったときの手掛かり用に、押せる要素の文字を列挙 */
    private static String listLabels(AccessibilityNodeInfo root) {
        StringBuilder sb = new StringBuilder();
        ArrayDeque<AccessibilityNodeInfo> st = new ArrayDeque<>();
        st.push(root);
        int count = 0, visited = 0;
        while (!st.isEmpty() && count < 25 && visited < 600) {
            AccessibilityNodeInfo n = st.pop();
            visited++;
            if (n == null) continue;
            if (n.isVisibleToUser() && n.isClickable()) {
                CharSequence s = n.getText() != null && n.getText().length() > 0 ? n.getText() : n.getContentDescription();
                if (s != null && s.length() > 0 && s.length() < 40) {
                    sb.append('「').append(s).append("」");
                    count++;
                }
            }
            for (int i = n.getChildCount() - 1; i >= 0; i--) st.push(n.getChild(i));
        }
        return sb.length() == 0 ? "(なし)" : sb.toString();
    }
}
