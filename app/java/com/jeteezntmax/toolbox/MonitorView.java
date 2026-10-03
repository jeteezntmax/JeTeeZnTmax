package com.jeteezntmax.toolbox;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.Calendar;

/**
 * 迷你监视器 —— 屏幕上一条窄条，浮在所有应用上面。
 *
 * 显示项（v3.1.0 起可以在 WebUI 里自己勾）：
 *     CPU · GPU · FPS · 内存 · 电池 · 功耗 · 温度 · 时间
 * 每一项都能单独指定颜色；不指定就按负载自动变色。
 *
 * 配置来自 /data/adb/ksu_toolbox/monitor.conf（WebUI 写）：
 *     fg=#e8ecf2            全局文字色（没单独设色的项用它；不设就自动）
 *     bg=#070a0f            背景色
 *     size=1.0              字号倍率（0.6~2.0）
 *     show=cpu,fps,pow,temp 显示哪些项
 *     tf=hm                 时间格式：hm = 时:分，hms = 时:分:秒
 *     cpu=#5be38a           单项颜色（auto = 跟随负载自动）
 */
public class MonitorView extends View {

    private final SysStats st = new SysStats();

    private final Paint pBg = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pLab = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pVal = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pDiv = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pBd  = new Paint(Paint.ANTI_ALIAS_FLAG);

    private float density;
    private float baseLab = 6.5f, baseVal = 9.5f;
    private int userFg = 0, userBg = 0;      // 0 = 用默认
    private String cfgRaw = "";

    /* ---------- 显示项定义 ---------- */
    private static final String[] KEYS   = {"cpu", "gpu", "fps", "ram", "bat", "pow", "temp", "time"};
    private static final String[] LABELS = {"CPU", "GPU", "FPS", "内存", "电池", "功耗", "温度", "时间"};
    /* 出厂默认：还是原来那四项，不吓人 */
    private static final boolean[] SHOW_DEF = {true, false, true, false, false, true, true, false};

    private final boolean[] show = new boolean[KEYS.length];
    private final int[] custom = new int[KEYS.length];   // 0 = 自动
    private String timeFmt = "hm";

    public MonitorView(Context c) {
        super(c);
        density = c.getResources().getDisplayMetrics().density;
        System.arraycopy(SHOW_DEF, 0, show, 0, SHOW_DEF.length);

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

    /** 每秒调一次：只为了让「时间」那一项秒数会跳 */
    public void tick() {
        if (show[7]) invalidate();
    }

    /* ---------- 配置解析 ---------- */
    public void applyConfig(String s) {
        if (s == null) s = "";
        if (s.equals(cfgRaw)) return;
        cfgRaw = s;
        float size = 1f;
        int fg = 0, bg = 0;
        boolean[] nshow = new boolean[KEYS.length];
        int[] ncustom = new int[KEYS.length];
        String tf = "hm";
        for (String kv : s.split(";")) {
            int e = kv.indexOf('=');
            if (e <= 0) continue;
            String k = kv.substring(0, e).trim().toLowerCase();
            String v = kv.substring(e + 1).trim();
            try {
                if (k.equals("size")) { size = Float.parseFloat(v); }
                else if (k.equals("fg")) { fg = parseColor(v); }
                else if (k.equals("bg")) { bg = parseColor(v); }
                else if (k.equals("tf")) { if (v.equals("hms") || v.equals("hm")) tf = v; }
                else if (k.equals("show")) {
                    for (String p : v.split(",")) {
                        int idx = indexOf(p.trim().toLowerCase());
                        if (idx >= 0) nshow[idx] = true;
                    }
                } else {
                    int idx = indexOf(k);
                    if (idx >= 0 && !v.equalsIgnoreCase("auto") && !v.isEmpty()) ncustom[idx] = parseColor(v);
                }
            } catch (Exception ignored) { }
        }
        if (size < 0.6f) size = 0.6f;
        if (size > 2.0f) size = 2.0f;
        baseLab = 6.5f * size;
        baseVal = 9.5f * size;
        userFg = fg;
        userBg = bg;
        timeFmt = tf;

        // 一项都没勾（或者 show= 后面全是错名字）就退回默认 ——
        // 否则悬浮窗会缩成一条 0 宽的细缝，看着像坏了
        boolean any = false;
        for (boolean b : nshow) if (b) any = true;
        if (!any) System.arraycopy(SHOW_DEF, 0, nshow, 0, SHOW_DEF.length);
        System.arraycopy(nshow, 0, show, 0, nshow.length);
        System.arraycopy(ncustom, 0, custom, 0, ncustom.length);

        if (userBg != 0) pBg.setColor(userBg);
        else pBg.setColor(0xF2070A0F);
        if (userFg != 0) pLab.setColor(withAlpha(userFg, 0.72f));
        else pLab.setColor(0xFFC8D2DE);

        requestLayout();
        invalidate();
    }

    private static int indexOf(String key) {
        for (int i = 0; i < KEYS.length; i++) if (KEYS[i].equals(key)) return i;
        return -1;
    }

    private static int parseColor(String v) {
        v = v.trim();
        if (v.startsWith("#")) v = v.substring(1);
        try {
            if (v.length() == 6) return 0xFF000000 | (int) Long.parseLong(v, 16);
            if (v.length() == 8) return (int) Long.parseLong(v, 16);
        } catch (Exception ignored) { }
        return 0;
    }
    private static int withAlpha(int c, float a) {
        return ((int) (a * 255) << 24) | (c & 0x00FFFFFF);
    }

    /** 后台线程采完样后调 */
    public void pushSample() { invalidate(); }

    /* ---------- 组装要画的项 ---------- */
    private static final class Item {
        String label, value;
        int key;
        Item(String l, String v, int k) { label = l; value = v; key = k; }
    }

    private ArrayList<Item> items() {
        ArrayList<Item> out = new ArrayList<Item>(8);
        for (int i = 0; i < KEYS.length; i++) {
            if (!show[i]) continue;
            String v = valueOf(i);
            if (v == null) continue;
            out.add(new Item(LABELS[i], v, i));
        }
        if (out.isEmpty()) out.add(new Item(LABELS[0], cpuText(), 0));   // 兜底，永不空
        return out;
    }

    private String valueOf(int i) {
        switch (i) {
            case 0: return cpuText();
            case 1: return gpuText();
            case 2: return fpsText();
            case 3: return ramText();
            case 4: return batText();
            case 5: return powerText();
            case 6: return tempText();
            case 7: return timeText();
        }
        return null;
    }

    private String cpuText() {
        if (st.cpuUsage < 0) return "--%";
        return String.format("%.0f%%", st.cpuUsage);
    }
    private String gpuText() {
        if (st.gpuPct >= 0) return String.format("%.0f%%", st.gpuPct);
        if (st.gpuMhz > 0) return String.format("%.0fM", st.gpuMhz);
        return "--";
    }
    private String fpsText() {
        if (st.fps < 0) return "--";
        return String.format("%.0f", st.fps);
    }
    private String ramText() {
        if (st.ramPct < 0) return "--%";
        return String.format("%.0f%%", st.ramPct);
    }
    private String batText() {
        if (st.batPct < 0) return "--%";
        return String.format("%.0f%%", st.batPct);
    }
    private String powerText() {
        if (st.powerW < 0) return "--";
        return String.format("%.2fW", st.powerW);
    }
    private String tempText() {
        if (st.tempC < 0) return "--";
        return String.format("%.1f°", st.tempC);
    }
    private String timeText() {
        Calendar c = Calendar.getInstance();
        int h = c.get(Calendar.HOUR_OF_DAY), m = c.get(Calendar.MINUTE), s = c.get(Calendar.SECOND);
        if ("hms".equals(timeFmt)) return String.format("%02d:%02d:%02d", h, m, s);
        return String.format("%02d:%02d", h, m);
    }

    /* ---------- 颜色：自定义 > 全局 fg > 按负载自动 ---------- */
    private int colorOf(Item it) {
        if (custom[it.key] != 0) return custom[it.key];
        if (userFg != 0) return userFg;
        switch (it.key) {
            case 0:  return loadColor(st.cpuUsage, 60, 85);
            case 1:  return loadColor(st.gpuPct, 60, 85);
            case 2:  return fpsColor(st.fps);
            case 3:  return loadColor(st.ramPct, 70, 85);
            case 4:  return batColor(st.batPct);
            case 5:  return st.powerW < 0 ? 0xFF5A636E : 0xFF8FC7FF;
            case 6:  return tempColor(st.tempC);
            case 7:  return 0xFFE8ECF2;
        }
        return 0xFFE8ECF2;
    }
    private static int loadColor(double v, double warn, double bad) {
        if (v < 0) return 0xFF5A636E;
        if (v >= bad) return 0xFFFF5A5A;
        if (v >= warn) return 0xFFFFA23F;
        return 0xFF5BE38A;
    }
    private static int fpsColor(double v) {
        if (v < 0) return 0xFF5A636E;
        if (v < 30) return 0xFFFF5A5A;
        if (v < 50) return 0xFFFFA23F;
        return 0xFF5BE38A;
    }
    private static int batColor(double v) {
        if (v < 0) return 0xFF5A636E;
        if (v <= 15) return 0xFFFF5A5A;
        if (v <= 30) return 0xFFFFA23F;
        if (v >= 90) return 0xFF30D158;
        return 0xFFE8ECF2;
    }
    /** 温度是反着来的：越高越红（跟旧版观感一致，正常温度是黄的） */
    private static int tempColor(double v) {
        if (v < 0) return 0xFF5A636E;
        if (v >= 65) return 0xFFFF5A5A;
        if (v >= 50) return 0xFFFFA23F;
        return 0xFFFFD24B;
    }

    /* ---------- 尺寸：按文字实际宽度算 ---------- */
    private float layoutWidth(ArrayList<Item> it) {
        float pad = dp(7), gap = dp(5), divW = dp(3);
        float w = pad * 2;
        for (int i = 0; i < it.size(); i++) {
            w += pLab.measureText(it.get(i).label) + dp(2) + pVal.measureText(it.get(i).value);
            if (i < it.size() - 1) w += gap * 2 + divW;
        }
        return w;
    }

    @Override
    protected void onMeasure(int wSpec, int hSpec) {
        // 屏幕宽度留 8dp 边距
        int maxW = getResources().getDisplayMetrics().widthPixels - (int) dp(8);

        pLab.setTextSize(sp(baseLab));
        pVal.setTextSize(sp(baseVal));
        ArrayList<Item> it = items();
        float natural = layoutWidth(it);

        // 放不下就整体按比例缩字号 —— 绝不裁切
        if (natural > maxW) {
            float k = maxW / natural;
            if (k < 0.45f) k = 0.45f;          // 项多了可以缩得比原来更狠
            pLab.setTextSize(sp(baseLab) * k);
            pVal.setTextSize(sp(baseVal) * k);
            natural = layoutWidth(it);
        }
        int w = (int) Math.ceil(natural);
        if (w > maxW) w = maxW;
        int h = (int) dp(23);
        setMeasuredDimension(resolveSize(w, wSpec), resolveSize(h, hSpec));
    }

    @Override
    protected void onDraw(Canvas cv) {
        float W = getWidth(), H = getHeight();
        float r = H / 2f;
        RectF shadow = new RectF(dp(1), dp(1), W - dp(1), H - dp(1));
        cv.drawRoundRect(shadow, r, r, pBg);
        cv.drawRoundRect(shadow, r, r, pBd);

        ArrayList<Item> it = items();
        float pad = dp(7), gap = dp(5), divW = dp(3);
        float baseline = H / 2f + pVal.getTextSize() / 2.6f;
        float x = pad;

        for (int i = 0; i < it.size(); i++) {
            Item o = it.get(i);
            pLab.setTextAlign(Paint.Align.LEFT);
            pVal.setTextAlign(Paint.Align.LEFT);
            cv.drawText(o.label, x, baseline, pLab);
            x += pLab.measureText(o.label) + dp(2);
            pVal.setColor(colorOf(o));
            cv.drawText(o.value, x, baseline, pVal);
            x += pVal.measureText(o.value);
            if (i < it.size() - 1) {
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
