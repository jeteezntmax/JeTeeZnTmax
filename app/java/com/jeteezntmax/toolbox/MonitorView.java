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

    private float density;
    private int measuredW = 0;

    public MonitorView(Context c) {
        super(c);
        density = c.getResources().getDisplayMetrics().density;

        pBg.setColor(0xC9101418);
        pBg.setStyle(Paint.Style.FILL);

        pLab.setColor(0xFF6E7A88);
        pLab.setTextSize(sp(7));
        pLab.setFakeBoldText(false);

        pVal.setTextSize(sp(10));
        pVal.setFakeBoldText(true);

        pDiv.setColor(0x33FFFFFF);
        pDiv.setStrokeWidth(dp(1));
    }

    private float dp(float v) { return v * density; }
    private float sp(float v) { return v * getResources().getDisplayMetrics().scaledDensity; }

    public SysStats stats() { return st; }

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
        return String.format("%.1f°C", st.tempC);
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
        float pad = dp(11), gap = dp(9), divW = dp(5);
        float w = pad * 2;
        for (int i = 0; i < it.length; i++) {
            w += pLab.measureText(it[i][0]) + dp(3) + pVal.measureText(it[i][1]);
            if (i < it.length - 1) w += gap * 2 + divW;
        }
        return w;
    }

    @Override
    protected void onMeasure(int wSpec, int hSpec) {
        int maxW = getResources().getDisplayMetrics().widthPixels - (int) dp(8);
        int w = (int) Math.min(layoutWidth(), maxW);
        int h = (int) dp(26);
        measuredW = w;
        setMeasuredDimension(resolveSize(w, wSpec), resolveSize(h, hSpec));
    }

    @Override
    protected void onDraw(Canvas cv) {
        float W = getWidth(), H = getHeight();
        cv.drawRoundRect(new RectF(0, 0, W, H), H / 2f, H / 2f, pBg);

        String[][] it = items();
        float pad = dp(11), gap = dp(9), divW = dp(5);
        float baseline = H / 2f + pVal.getTextSize() / 2.6f;
        float x = pad;

        for (int i = 0; i < it.length; i++) {
            pLab.setTextAlign(Paint.Align.LEFT);
            pVal.setTextAlign(Paint.Align.LEFT);
            cv.drawText(it[i][0], x, baseline, pLab);
            x += pLab.measureText(it[i][0]) + dp(3);
            pVal.setColor(colorOf(i));
            cv.drawText(it[i][1], x, baseline, pVal);
            x += pVal.measureText(it[i][1]);
            if (i < it.length - 1) {
                x += gap;
                cv.drawLine(x + divW / 2, H * 0.28f, x + divW / 2, H * 0.72f, pDiv);
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
