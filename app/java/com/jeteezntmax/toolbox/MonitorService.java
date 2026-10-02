package com.jeteezntmax.toolbox;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Handler;
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

    private WindowManager wm;
    private MonitorView view;
    private WindowManager.LayoutParams lp;
    private Handler ui;
    private Thread worker;
    private volatile boolean running;
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
            public void onDrag(int dx, int dy) {
                lp.x += dx;
                lp.y += dy;
                try { wm.updateViewLayout(view, lp); } catch (Exception ignored) { }
            }
        });
        view.setOnLongClickListener(new View.OnLongClickListener() {
            public boolean onLongClick(View v) { stopSelf(); return true; }
        });

        int type = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;

        lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.x = dp(6);
        lp.y = dp(2);            // 贴着屏幕最上方，状态栏那一层

        try { wm.addView(view, lp); }
        catch (Exception e) { stopSelf(); }
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
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
                            v.stats().sample(suBin);
                            ui.post(new Runnable() {
                                public void run() { if (view != null) view.pushSample(); }
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
