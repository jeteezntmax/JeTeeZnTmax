package com.jeteezntmax.toolbox;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Handler;
import android.view.Choreographer;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;

/**
 * 迷你监视器悬浮窗的后台服务。
 *
 * 用 TYPE_APPLICATION_OVERLAY 盖在所有应用上面（就像状态条那样），
 * 每 2 秒采一次数据推给 MonitorView。
 *
 * 现在是对着 API 34 的 android.jar 编译的，所以 NotificationChannel
 * 这些可以直接写。manifest 里 targetSdk 仍然保持 30 —— 这样就不用被
 * Android 14 的 foregroundServiceType 和 Android 13 的通知运行时权限折腾。
 */
public class MonitorService extends Service {

    public static final String ACTION_STOP = "com.jeteezntmax.toolbox.STOP_MONITOR";
    private static final int NOTI_ID = 0x4A54;
    private static final String CH_ID = "jeteez_monitor";
    private static final int PERIOD_MS = 2000;
    private static final String PREF = "monitor";

    private WindowManager wm;
    private MonitorView view;
    private WindowManager.LayoutParams lp;
    private Handler ui;
    private Thread worker;
    private volatile boolean running;
    private final java.util.concurrent.atomic.AtomicInteger frameN =
            new java.util.concurrent.atomic.AtomicInteger();
    private long fpsBase = 0;
    private double fps = -1;
    private final String suBin = "su";

    public static boolean canOverlay(Context c) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try { return Settings.canDrawOverlays(c); } catch (Exception e) { return false; }
        }
        return true;
    }

    public static void start(Context c) {
        Intent i = new Intent(c, MonitorService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) c.startForegroundService(i);
        else c.startService(i);
    }

    @Override
    public IBinder onBind(Intent i) { return null; }

    @Override
    public void onCreate() {
        super.onCreate();
        ui = new Handler(Looper.getMainLooper());
        try { startForeground(NOTI_ID, buildNotification()); } catch (Exception ignored) { }
        if (!canOverlay(this)) { stopSelf(); return; }
        addOverlay();
        startFpsCounter();
        startLoop();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        if (lp != null) {
            getSharedPreferences(PREF, MODE_PRIVATE).edit()
                    .putInt("mx", lp.x).putInt("my", lp.y).apply();
        }
        running = false;
        if (worker != null) { worker.interrupt(); worker = null; }
        if (view != null && wm != null) {
            try { wm.removeView(view); } catch (Exception ignored) { }
        }
        view = null;
        try { stopForeground(true); } catch (Exception ignored) { }
        super.onDestroy();
    }

    /* ---------- 通知 ---------- */
    private Notification buildNotification() {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && nm != null) {
            NotificationChannel ch = new NotificationChannel(
                    CH_ID, "迷你监视器", NotificationManager.IMPORTANCE_MIN);
            ch.setShowBadge(false);
            ch.setSound(null, null);
            ch.enableVibration(false);
            nm.createNotificationChannel(ch);
        }

        Intent stop = new Intent(this, MonitorService.class);
        stop.setAction(ACTION_STOP);
        int fl = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) fl |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent pi = PendingIntent.getService(this, 1, stop, fl);

        Notification.Builder b = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                ? new Notification.Builder(this, CH_ID)
                : new Notification.Builder(this);
        b.setContentTitle("迷你监视器运行中")
         .setContentText("点「停止」关闭悬浮窗（长按悬浮窗也可以）")
         .setSmallIcon(android.R.drawable.stat_notify_sync)
         .setShowWhen(false)
         .setOngoing(true);
        b.addAction(new Notification.Action.Builder(null, "停止", pi).build());
        return b.build();
    }

    /* ---------- 悬浮窗 ---------- */
    private void addOverlay() {
        wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        view = new MonitorView(this);
        view.setDragHost(new MonitorView.DragHost() {
            private long lastSave = 0;
            public void onDrag(int dx, int dy) {
                lp.x += dx;
                // 不限制位置了 —— 有些人就喜欢塞在挖孔和上屏的夹角。
                // 拖进状态栏那条之后就收不到触摸了，但 WebUI 里有
                //「重置悬浮窗位置」可以救回来。
                lp.y += dy;
                if (lp.y < 0) lp.y = 0;
                if (lp.x < 0) lp.x = 0;
                try { wm.updateViewLayout(view, lp); } catch (Exception ignored) { }
                long now = System.currentTimeMillis();
                if (now - lastSave > 600) {          // 别每移动一像素就写一次盘
                    lastSave = now;
                    getSharedPreferences(PREF, MODE_PRIVATE).edit()
                            .putInt("mx", lp.x).putInt("my", lp.y).apply();
                }
            }
        });
        // 双击关闭 —— 原来是长按，但长按会和"按住拖动"抢手势，所以换了
        view.setOnTouchListener(new View.OnTouchListener() {
            private long lastUp = 0;
            public boolean onTouch(View v, android.view.MotionEvent e) {
                if (e.getActionMasked() == android.view.MotionEvent.ACTION_UP) {
                    long now = System.currentTimeMillis();
                    if (now - lastUp < 300) { stopSelf(); return true; }
                    lastUp = now;
                }
                return false;      // 交给 onTouchEvent 去处理拖动
            }
        });

        int type = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;

        lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL   // 不挡住别处的触摸
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        // ⚠ 很多机器是【居中挖孔】摄像头 —— 放正中间正好被镜头挡掉。
        //   所以默认靠左上，用户拖到哪就记到哪。
        SharedPreferences sp = getSharedPreferences(PREF, MODE_PRIVATE);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.x = sp.getInt("mx", dp(2));
        // 默认放到【状态栏下面】——
        //   屏幕最顶上那条的触摸会被 TYPE_NOTIFICATION_SHADE(2040) 吃掉，
        //   而我们的悬浮窗是 2038，比它低一层，放 y=0 会收不到手指（拖不动）。
        lp.y = Math.max(statusBarH(), sp.getInt("my", statusBarH() + dp(1)));

        try { wm.addView(view, lp); }
        catch (Exception e) { stopSelf(); }
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    /** 状态栏高度（拿不到就给个 28dp 的估计值） */
    private int statusBarH() {
        try {
            int id = getResources().getIdentifier("status_bar_height", "dimen", "android");
            if (id > 0) {
                int h = getResources().getDimensionPixelSize(id);
                if (h > 0) return h;
            }
        } catch (Exception ignored) { }
        return dp(28);
    }

    /* ---------- 帧率：用 Choreographer 数帧 ----------
       思路参考了 Scene 的做法（它 dex 里只有 Choreographer，没有 SurfaceFlinger
       也没有 dumpsys）—— 但完全是自己写的：
         · 不用 root，不用 su，不卡
         · 每一帧回调一次，按秒统计
       注意：数的是【屏幕的刷新回调率】，开了可变刷新率(VVR)之后
       它正好跟着画面走；但静止画面时系统会降到 1~10Hz，这是正常的。
    */
    private void startFpsCounter() {
        try {
            Choreographer.getInstance().postFrameCallback(new Choreographer.FrameCallback() {
                public void doFrame(long nanos) {
                    frameN.incrementAndGet();
                    long now = System.currentTimeMillis();
                    if (fpsBase == 0) fpsBase = now;
                    long dt = now - fpsBase;
                    if (dt >= 1000) {
                        fps = frameN.get() * 1000.0 / dt;
                        frameN.set(0);
                        fpsBase = now;
                        if (view != null) view.setFps(fps);
                    }
                    Choreographer.getInstance().postFrameCallback(this);
                }
            });
        } catch (Throwable ignored) { }
    }

    /* ---------- 采集循环 ---------- */
    private void startLoop() {
        running = true;
        worker = new Thread(new Runnable() {
            public void run() {
                while (running) {
                    final MonitorView v = view;
                    if (v != null) {
                        try {
                            v.stats().sample();            // CPU/功耗/温度走 su；帧率是 Choreographer 数的
                            final double f = fps;
                            final String c = v.stats().cfg;
                            ui.post(new Runnable() {
                                public void run() {
                                    if (view != null) {
                                        view.applyConfig(c);
                                        view.setFps(f);
                                        view.pushSample();
                                    }
                                }
                            });
                        } catch (Throwable ignored) { }
                    }
                    try { Thread.sleep(PERIOD_MS); } catch (InterruptedException e) { return; }
                }
            }
        });
        worker.start();
    }
}
