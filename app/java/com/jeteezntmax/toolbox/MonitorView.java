package com.jeteezntmax.toolbox;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

/**
 * 迷你监视器 —— 屏幕正上方居中的一条小状态条。
 *
 * 只显示四项：
 *     CPU 利用率 │ 帧率 │ 功耗 │ 温度
 *
 * 上一版画了 8 条核心曲线 + GPU，结果宽度算错，
 * 后面几项被推到屏幕外面根本看不见。现在按文字实际宽度量，
 * 内容多少就多宽，不会跑出去。
 */
public class MonitorView extends View {

    private final SysStats st = new SysStats();

    private final Paint pBg = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pLab = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pVal = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pDiv = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pBd  = new Paint(Paint.ANTI_ALIAS_FLAG);

    private float density;
    private int measuredW = 0;
    private float baseLab = 6.5f, baseVal = 9.5f;
    private int userFg = 0, userBg = 0;      // 0 = 用默认
    private String cfgRaw = "";

    public MonitorView(Context c) {
        super(c);
        density = c.getResources().getDisplayMetrics().density;

        // 底色接近纯黑、几乎不透明 —— 浅色壁纸上也看得清
        pBg.setColor(0xF2070A0F);
        pBg.setStyle(Paint.Style.FILL);

        // 一圈亮边，让它从任何背景上"浮"出来
        pBd.setStyle(Paint.Style.STROKE);
        pBd.setColor(0x59FFFFFF);
        pBd.setStrokeWidth(dp(1));

        // 标签提亮（原来是 0x6E7A88，太暗）
        pLab.setColor(0xFFC8D2DE);
        pLab.setTextSize(sp(6.5f));
        pLab.setFakeBoldText(true);

        pVal.setTextSize(sp(9.5f));
        pVal.setFakeBoldText(true);
        pVal.setShadowLayer(dp(2), 0, dp(0.5f), 0xCC000000);

        pDiv.setColor(0x44FFFFFF);
        pDiv.setStrokeWidth(dp(1));
    }

    private float dp(float v) { return v * density; }
    private float sp(float v) { return v * getResources().getDisplayMetrics().scaledDensity; }

    public SysStats stats() { return st; }

    /** 帧率不走 sysfs，由 Choreographer 数出来直接塞进来 */
    public void setFps(double v) { st.fps = v; }

    /** 用户自定义：fg=#RRGGBB;bg=#RRGGBB;size=1.0 */
    public void applyConfig(String s) {
        if (s == null) s = "";
        if (s.equals(cfgRaw)) return;
        cfgRaw = s;
        float size = 1f;
        int fg = 0, bg = 0;
        for (String kv : s.split(";")) {
            int e = kv.indexOf('=');
            if (e <= 0) continue;
            String k = kv.substring(0, e).trim().toLowerCase();
            String v = kv.substring(e + 1).trim();
            try {
                if (k.equals("size")) { size = Float.parseFloat(v); }
                else if (k.equals("fg")) { fg = parseColor(v); }
                else if (k.equals("bg")) { bg = parseColor(v); }
            } catch (Exception ignored) { }
        }
        if (size < 0.6f) size = 0.6f;
        if (size > 2.0f) size = 2.0f;
        baseLab = 6.5f * size;
        baseVal = 9.5f * size;
        userFg = fg;
        userBg = bg;

        if (userBg != 0) pBg.setColor(userBg);
        else pBg.setColor(0xF2070A0F);
        if (userFg != 0) {
            pVal.setColor(userFg);          // 有自定义就所有值统一用它
            pLab.setColor(withAlpha(userFg, 0.72f));
        } else {
            pLab.setColor(0xFFC8D2DE);
        }
        requestLayout();
        invalidate();
    }

    private static int parseColor(String v) {
        v = v.trim();
        if (v.startsWith("#")) v = v.substring(1);
        if (v.length() == 6) return 0xFF000000 | (int) Long.parseLong(v, 16);
        if (v.length() == 8) return (int) Long.parseLong(v, 16);
        return 0;
    }
    private static int withAlpha(int c, float a) {
        return ((int) (a * 255) << 24) | (c & 0x00FFFFFF);
    }

    /** 后台线程采完样后调 */
    public void pushSample() { invalidate(); }

    /* ---------- 四个项目 ---------- */
    private String[][] items() {
        return new String[][] {
            {"CPU",  cpuText()},
            {"FPS",  fpsText()},
            {"功耗", powerText()},
            {"温度", tempText()},
        };
    }

    private String cpuText() {
        if (st.cpuUsage < 0) return "--%";
        return String.format("%.0f%%", st.cpuUsage);
    }
    private String fpsText() {
        if (st.fps < 0) return "--";
        return String.format("%.0f", st.fps);
    }
    private String powerText() {
        if (st.powerW < 0) return "--";
        return String.format("%.2fW", st.powerW);
    }
    private String tempText() {
        if (st.tempC < 0) return "--";
        return String.format("%.1f°", st.tempC);
    }

    private int colorOf(int idx) {
        switch (idx) {
            case 0:
                if (st.cpuUsage < 0) return 0xFF5A636E;
                if (st.cpuUsage >= 85) return 0xFFFF5A5A;
                if (st.cpuUsage >= 60) return 0xFFFFA23F;
                return 0xFF5BE38A;
            case 1:
                if (st.fps < 0) return 0xFF5A636E;
                if (st.fps < 30) return 0xFFFF5A5A;
                if (st.fps < 50) return 0xFFFFA23F;
                return 0xFF5BE38A;
            case 2:
                return st.powerW < 0 ? 0xFF5A636E : 0xFF8FC7FF;
            case 3:
                if (st.tempC < 0) return 0xFF5A636E;
                if (st.tempC >= 65) return 0xFFFF5A5A;
                if (st.tempC >= 50) return 0xFFFFA23F;
                return 0xFFFFD24B;
        }
        return 0xFFE8ECF2;
    }

    /* ---------- 尺寸：按文字实际宽度算 ---------- */
    private float layoutWidth() {
        String[][] it = items();
        float pad = dp(7), gap = dp(5), divW = dp(3);
        float w = pad * 2;
        for (int i = 0; i < it.length; i++) {
            w += pLab.measureText(it[i][0]) + dp(2) + pVal.measureText(it[i][1]);
            if (i < it.length - 1) w += gap * 2 + divW;
        }
        return w;
    }

    @Override
    protected void onMeasure(int wSpec, int hSpec) {
        // 屏幕宽度留 6dp 边距，另外给左右各留一点（免得贴边）
        int maxW = getResources().getDisplayMetrics().widthPixels - (int) dp(8);

        pLab.setTextSize(sp(baseLab));
        pVal.setTextSize(sp(baseVal));
        float natural = layoutWidth();

        // 放不下就整体按比例缩字号 —— 绝不裁切（上一版"温度穿模"就是裁出来的）
        if (natural > maxW) {
            float k = maxW / natural;
            if (k < 0.55f) k = 0.55f;
            pLab.setTextSize(sp(baseLab) * k);
            pVal.setTextSize(sp(baseVal) * k);
            natural = layoutWidth();
        }
        int w = (int) Math.ceil(natural);
        if (w > maxW) w = maxW;
        int h = (int) dp(23);
        measuredW = w;
        setMeasuredDimension(resolveSize(w, wSpec), resolveSize(h, hSpec));
    }

    @Override
    protected void onDraw(Canvas cv) {
        float W = getWidth(), H = getHeight();
        float r = H / 2f;
        // 外圈一层更暗的描边当"阴影"，勉强算是浮起来
        RectF shadow = new RectF(dp(1), dp(1), W - dp(1), H - dp(1));
        cv.drawRoundRect(shadow, r, r, pBg);
        cv.drawRoundRect(shadow, r, r, pBd);

        String[][] it = items();
        float pad = dp(7), gap = dp(5), divW = dp(3);
        float baseline = H / 2f + pVal.getTextSize() / 2.6f;
        float x = pad;

        for (int i = 0; i < it.length; i++) {
            pLab.setTextAlign(Paint.Align.LEFT);
            pVal.setTextAlign(Paint.Align.LEFT);
            cv.drawText(it[i][0], x, baseline, pLab);
            x += pLab.measureText(it[i][0]) + dp(2);
            pVal.setColor(userFg != 0 ? userFg : colorOf(i));   // 自定义优先
            cv.drawText(it[i][1], x, baseline, pVal);
            x += pVal.measureText(it[i][1]);
            if (i < it.length - 1) {
                x += gap;
                cv.drawLine(x + divW / 2, H * 0.26f, x + divW / 2, H * 0.74f, pDiv);
                x += divW + gap;
            }
        }
    }

    /* ---------- 拖动 ---------- */
    public interface DragHost { void onDrag(int dx, int dy); }
    private DragHost host;
    private float downX, downY;

    public void setDragHost(DragHost h) { host = h; }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = e.getRawX(); downY = e.getRawY();
                return true;
            case MotionEvent.ACTION_MOVE:
                if (host != null) {
                    host.onDrag((int) (e.getRawX() - downX), (int) (e.getRawY() - downY));
                    downX = e.getRawX(); downY = e.getRawY();
                }
                return true;
            case MotionEvent.ACTION_UP:
                return true;
        }
        return super.onTouchEvent(e);
    }
}
