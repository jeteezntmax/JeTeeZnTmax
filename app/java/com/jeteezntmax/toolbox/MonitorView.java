package com.jeteezntmax.toolbox;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.os.Build;
import android.util.DisplayMetrics;
import android.view.MotionEvent;
import android.view.View;

/**
 * 迷你监视器悬浮窗的绘制。
 *
 * 从左到右：CPU（8 个核心的火花线 + 各自当前频率）│ GPU 频率 │ 帧率 │ 功耗 │ 温度
 * 每 2 秒推一次新样本，火花线是最近 24 个点。
 *
 * 整个条子可拖动，位置记在 WindowManager 的 params 里。
 */
public class MonitorView extends View {

    private static final int HIST = 24;         // 火花线保留多少个点

    private final SysStats st = new SysStats();
    private final double[][] hist = new double[16][HIST];
    private final long[] last = new long[16];
    private int histN = 0;
    private int histAt = 0;

    private final Paint pBg = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pTxt = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pDim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pLine = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();

    private float density;
    private int[] coreColor = new int[16];

    public MonitorView(Context c) {
        super(c);
        density = c.getResources().getDisplayMetrics().density;
        setLayerType(LAYER_TYPE_HARDWARE, null);

        pBg.setColor(0xD9101418);
        pBg.setStyle(Paint.Style.FILL);

        pTxt.setColor(0xFFE8ECF2);
        pTxt.setTextSize(sp(8));
        pTxt.setFakeBoldText(true);

        pDim.setColor(0xFF7C8794);
        pDim.setTextSize(sp(6.5f));

        pLine.setStyle(Paint.Style.STROKE);
        pLine.setStrokeWidth(dp(1.2f));
        pLine.setStrokeCap(Paint.Cap.ROUND);
        pLine.setStrokeJoin(Paint.Join.ROUND);

        pFill.setStyle(Paint.Style.FILL);

        int[] base = {0xFF3FD1FF, 0xFF4FA8FF, 0xFF7C8BFF, 0xFFA97BFF,
                      0xFFE070C8, 0xFFFF8A6B, 0xFFFFB84F, 0xFFF2D24B};
        for (int i = 0; i < 16; i++) coreColor[i] = base[i % base.length];
    }

    private float dp(float v) { return v * density; }
    private float sp(float v) { return v * getResources().getDisplayMetrics().scaledDensity; }

    /** 后台线程采完样后调这个（要 post 到主线程再 invalidate） */
    public void pushSample() {
        last[0] = 0;
        int n = st.cores > 0 ? st.cores : 8;
        for (int i = 0; i < n && i < 16; i++) last[i] = st.cpuCur[i];
        for (int i = 0; i < n && i < 16; i++) {
            double v = st.cpuMax[i] > 0 ? (double) st.cpuCur[i] / st.cpuMax[i] : 0;
            if (v < 0) v = 0;
            if (v > 1) v = 1;
            hist[i][histAt] = v;
        }
        histAt = (histAt + 1) % HIST;
        if (histN < HIST) histN++;
        invalidate();
    }

    public SysStats stats() { return st; }

    /* ---------- 尺寸 ---------- */
    private float wCpu(float coreW) { return coreW * (st.cores > 0 ? st.cores : 8); }

    @Override
    protected void onMeasure(int wSpec, int hSpec) {
        DisplayMetrics dm = getResources().getDisplayMetrics();
        int n = st.cores > 0 ? st.cores : 8;
        float coreW = dp(16);
        float want = dp(8) * 2 + wCpu(coreW) + dp(6) + dp(48) + dp(6) + dp(42)
                   + dp(6) + dp(54) + dp(6) + dp(52);
        int maxW = dm.widthPixels - (int) dp(12);
        int w = (int) Math.min(want, maxW);
        int h = (int) dp(46);
        setMeasuredDimension(resolveSize(w, wSpec), resolveSize(h, hSpec));
    }

    @Override
    protected void onDraw(Canvas cv) {
        float W = getWidth(), H = getHeight();
        float pad = dp(8);
        RectF bg = new RectF(0, 0, W, H);
        cv.drawRoundRect(bg, dp(14), dp(14), pBg);

        int n = st.cores > 0 ? st.cores : 8;
        float coreW = dp(16);
        // 宽度不够就把每核压缩一点
        float need = pad * 2 + wCpu(coreW) + dp(6) + dp(48) + dp(6) + dp(42) + dp(6) + dp(54) + dp(6) + dp(52);
        if (need > W) coreW = Math.max(dp(9), coreW * (W - (need - wCpu(coreW))) / wCpu(coreW));

        float x = pad;
        float chartTop = dp(7);
        float chartH = dp(21);
        float labelY = H - dp(6);

        /* ---- CPU 8 个核心 ---- */
        float cw = coreW;
        for (int i = 0; i < n && i < 16; i++) {
            float cx = x + i * cw;
            float cwIn = cw - dp(2.5f);
            if (cwIn < dp(3)) cwIn = dp(3);

            // 火花线
            double mx = 0;
            for (int k = 0; k < histN; k++) {
                double v = hist[i][(histAt - histN + k + HIST * 2) % HIST];
                if (v > mx) mx = v;
            }
            if (histN >= 2) {
                path.reset();
                for (int k = 0; k < histN; k++) {
                    double v = hist[i][(histAt - histN + k + HIST * 2) % HIST];
                    float px = cx + (histN <= 1 ? 0 : (float) k / (histN - 1) * cwIn);
                    float py = chartTop + chartH - (float) v * chartH;
                    if (k == 0) path.moveTo(px, py); else path.lineTo(px, py);
                }
                int col = coreColor[i];
                if (mx > 0.85) col = 0xFFFF5A5A;
                else if (mx > 0.6) col = 0xFFFFA23F;
                pLine.setColor(col);
                cv.drawPath(path, pLine);
            }
            // 当前频率
            float ghz = st.cpuCur[i] / 1000000f;
            String s = st.cpuCur[i] <= 0 ? "--" : (ghz >= 1 ? String.format("%.1f", ghz) : "0");
            pDim.setTextSize(sp(cw < dp(13) ? 5.5f : 6.5f));
            pDim.setColor(st.cpuCur[i] <= 0 ? 0xFF5A636E : 0xFF9AA5B2);
            pDim.setTextAlign(Paint.Align.CENTER);
            cv.drawText(s, cx + cwIn / 2, labelY, pDim);
        }
        x += n * cw + dp(6);

        /* ---- 后面的文字段 ---- */
        pTxt.setTextAlign(Paint.Align.LEFT);

        x = section(cv, x, H, "GPU", gpuText(), dp(48));
        x = section(cv, x, H, "FPS", fpsText(), dp(42));
        x = section(cv, x, H, "功耗", powerText(), dp(54));
        section(cv, x, H, "温度", tempText(), dp(52));
    }

    private float section(Canvas cv, float x, float H, String label, String value, float w) {
        pDim.setTextSize(sp(6.5f));
        pDim.setColor(0xFF6E7A88);
        pDim.setTextAlign(Paint.Align.LEFT);
        cv.drawText(label, x, dp(13), pDim);
        pTxt.setTextSize(sp(9));
        pTxt.setColor(colorFor(label, value));
        cv.drawText(value, x, H - dp(7), pTxt);
        return x + w;
    }

    private int colorFor(String label, String v) {
        if (v.equals("--")) return 0xFF5A636E;
        if (label.equals("温度")) {
            double c = st.tempC;
            if (c >= 65) return 0xFFFF5A5A;
            if (c >= 50) return 0xFFFFA23F;
            return 0xFFFFD24B;
        }
        if (label.equals("FPS")) {
            if (st.fps < 0) return 0xFF5A636E;
            if (st.fps < 30) return 0xFFFF5A5A;
            if (st.fps < 50) return 0xFFFFA23F;
            return 0xFF5BE38A;
        }
        return 0xFFE8ECF2;
    }

    private String gpuText() {
        if (st.gpuCur < 0) return "--";
        long v = st.gpuCur;
        if (v >= 1000000000L) return String.format("%.1fG", v / 1e9);
        if (v >= 1000000L) return String.format("%dM", v / 1000000);
        if (v >= 1000L) return String.format("%dM", v / 1000);
        return String.valueOf(v);
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
