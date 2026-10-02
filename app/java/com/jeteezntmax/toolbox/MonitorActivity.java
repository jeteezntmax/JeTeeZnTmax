package com.jeteezntmax.toolbox;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.widget.Toast;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 悬浮窗的入口。
 *
 * 关键点：App 本来就有 root —— 那「显示在其他应用上层」这个权限
 * 根本不用用户手点，直接 `appops set` 就行。
 * 只有 root 也失败的时候，才退回让用户去设置页。
 */
public class MonitorActivity extends Activity {

    private static final int REQ = 0x4A55;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final AtomicBoolean acted = new AtomicBoolean(false);
    private Toast tip;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        // 重置位置：把存下来的坐标清掉，服务起来就会回到默认位置
        if (getIntent() != null && getIntent().getBooleanExtra("reset", false)) {
            getSharedPreferences("monitor", MODE_PRIVATE).edit().clear().apply();
            Toast.makeText(this, "悬浮窗位置已重置", Toast.LENGTH_SHORT).show();
        }
        if (MonitorService.canOverlay(this)) { go(); return; }

        // ① 先用 root 自己把权限开了（这一步通常就成功）
        if (Build.VERSION.SDK_INT >= 23) {
            tip = Toast.makeText(this, "正在申请悬浮窗权限…", Toast.LENGTH_SHORT);
            tip.show();
            new Thread(new Runnable() {
                public void run() {
                    grantByRoot();
                    ui.post(new Runnable() {
                        public void run() {
                            if (MonitorService.canOverlay(MonitorActivity.this)) go();
                            else ui.postDelayed(new Runnable() {
                                public void run() {
                                    if (!MonitorService.canOverlay(MonitorActivity.this)) askUser();
                                }
                            }, 600);
                        }
                    });
                }
            }).start();
            // 兜底：8 秒还没回（比如 root 授权弹窗卡住了）就直接问用户
            ui.postDelayed(new Runnable() {
                public void run() {
                    if (!acted.get() && !MonitorService.canOverlay(MonitorActivity.this)) askUser();
                }
            }, 8000);
        } else {
            go();
        }
    }

    /** 用 root 直接开权限，不用用户动手 */
    private void grantByRoot() {
        String pkg = getPackageName();
        String[] cmds = {
                "appops set " + pkg + " SYSTEM_ALERT_WINDOW allow",
                "appops set " + pkg + " POST_NOTIFICATION allow",
                "cmd appops set " + pkg + " SYSTEM_ALERT_WINDOW allow",
        };
        for (String c : cmds) {
            Process p = null;
            try {
                p = new ProcessBuilder("su", "-c", c).redirectErrorStream(true).start();
                p.waitFor();
            } catch (Exception ignored) {
            } finally {
                if (p != null) try { p.destroy(); } catch (Exception ignored) { }
            }
            if (MonitorService.canOverlay(this)) return;
        }
    }

    private void askUser() {
        if (!acted.compareAndSet(false, true)) return;
        if (tip != null) tip.cancel();
        Toast.makeText(this, "root 没开成功，需要你手动给「显示在其他应用上层」权限", Toast.LENGTH_LONG).show();
        try {
            startActivityForResult(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName())), REQ);
        } catch (Exception e) {
            try {
                startActivityForResult(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION), REQ);
            } catch (Exception e2) {
                Toast.makeText(this, "打不开权限页，请手动去：设置 → 应用 → 显示在其他应用上层",
                        Toast.LENGTH_LONG).show();
                finish();
            }
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        if (req == REQ) {
            if (MonitorService.canOverlay(this)) go();
            else {
                Toast.makeText(this, "没拿到权限，悬浮窗开不了", Toast.LENGTH_SHORT).show();
                finish();
            }
            return;
        }
        super.onActivityResult(req, res, data);
    }

    private void go() {
        if (!acted.compareAndSet(false, true)) return;
        if (tip != null) tip.cancel();
        try {
            MonitorService.start(this);
            Toast.makeText(this, "迷你监视器已开启（长按悬浮窗关闭）", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, "启动失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
        finish();
    }
}
