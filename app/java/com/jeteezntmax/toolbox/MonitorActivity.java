package com.jeteezntmax.toolbox;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Toast;

/**
 * 悬浮窗的入口。
 *
 * WebUI / 桌面图标 都通过它启动：
 *   没有悬浮窗权限 → 跳设置页让用户开
 *   有             → 直接起 MonitorService，然后自己关掉
 *
 * 做成 Activity 而不是直接 startService，是因为
 * Android 10+ 从后台起前台服务限制很多，
 * 而 shell 里 `am start -n .../.MonitorActivity` 一定成功。
 */
public class MonitorActivity extends Activity {

    private static final int REQ = 0x4A55;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        if (MonitorService.canOverlay(this)) {
            go();
        } else if (Build.VERSION.SDK_INT >= 23) {
            Toast.makeText(this, "需要「显示在其他应用上层」权限", Toast.LENGTH_LONG).show();
            try {
                Intent i = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + getPackageName()));
                startActivityForResult(i, REQ);
            } catch (Exception e) {
                try {
                    startActivityForResult(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION), REQ);
                } catch (Exception e2) {
                    Toast.makeText(this, "打不开权限页，请手动去：设置 → 应用 → 显示在其他应用上层",
                            Toast.LENGTH_LONG).show();
                    finish();
                }
            }
        } else {
            go();
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
        try {
            MonitorService.start(this);
            Toast.makeText(this, "迷你监视器已开启（长按悬浮窗关闭）", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, "启动失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
        finish();
    }
}
