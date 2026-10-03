package com.jeteezntmax.toolbox;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;

/**
 * 刷新率档位面板 —— 点悬浮窗里的「FPS」时弹出来，浮在悬浮窗正下方。
 *
 * 一行小药丸，每个是一档刷新率，最后一颗是「恢复原值」：
 *     刷新率   60   90   120   144   恢复
 * 点一下就把结果回调出去（"" = 恢复），实际动作交给 MonitorService 去调 refresh.sh。
 * 当前锁定那档会高亮。
 */
public class RefreshPanelView extends View {

    public interface OnPick { void pick(String hz); }
    /** 点到面板外面了（靠窗口的 FLAG_WATCH_OUTSIDE_TOUCH 收到 ACTION_OUTSIDE） */
    public interface OnOutside { void outside(); }

    private final Paint pBg = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pTx = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pLab = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pOn = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pOnTx = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pBd = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final ArrayList<String> items = new ArrayList<String>();   // 显示文字
    private final ArrayList<String> values = new ArrayList<String>();  // 回调值（""=恢复）
    private final ArrayList<RectF> rects = new ArrayList<RectF>();
    private final ArrayList<Integer> rows = new ArrayList<Integer>();

    private final float density, textSize;
    private String activeHz = "";
    private OnPick cb;
    private OnOutside outsideCb;
    private float pad, gap, chipH;

    public RefreshPanelView(Context c, String ratesCsv, String lockHz) {
        super(c);
        density = c.getResources().getDisplayMetrics().density;
        textSize = sp(10.5f);
        pad = dp(8); gap = dp(5); chipH = dp(22);

        pBg.setColor(0xF2070A0F);
        pBg.setStyle(Paint.Style.FILL);
        pBd.setColor(0x59FFFFFF);
        pBd.setStyle(Paint.Style.STROKE);
        pBd.setStrokeWidth(dp(1));
        pLab.setColor(0xFFC8D2DE);
        pLab.setTextSize(sp(9f));
        pLab.setFakeBoldText(true);
        pTx.setColor(0xFFE8ECF2);
        pTx.setTextSize(textSize);
        pTx.setFakeBoldText(true);
        pOn.setColor(0x330A84FF);          // 当前档的底
        pOnTx.setColor(0xFF6FB4FF);
        pOnTx.setTextSize(textSize);
        pOnTx.setFakeBoldText(true);

        activeHz = lockHz == null ? "" : lockHz.trim();
        String csv = ratesCsv == null ? "" : ratesCsv.trim();
        if (!csv.isEmpty()) {
            for (String hz : csv.split(",")) {
                hz = hz.trim();
                if (hz.isEmpty()) continue;
                items.add(hz);
                values.add(hz);
            }
        }
        if (items.isEmpty()) {                 // 没扫到档位就给一套通用的
            String[] def = {"60", "90", "120", "144"};
            for (String hz : def) { items.add(hz); values.add(hz); }
        }
        items.add("恢复");
        values.add("");
    }

    private float dp(float v) { return v * density; }
    private float sp(float v) { return v * getResources().getDisplayMetrics().scaledDensity; }

    public void setOnPick(OnPick c) { cb = c; }
    public void setOnOutside(OnOutside o) { outsideCb = o; }

    private float chipW(int i) {
        return pTx.measureText(items.get(i)) + dp(18);
    }

    /* ---------- 尺寸：按屏幕宽度自动换行 ---------- */
    @Override
    protected void onMeasure(int wSpec, int hSpec) {
        int maxW = getResources().getDisplayMetrics().widthPixels - (int) dp(8);
        // 先试着铺一行（前面留出"刷新率"标签的位置）
        float labelW = pLab.measureText("刷新率") + dp(6);
        float avail = maxW - pad * 2 - labelW;
        int used = 0;
        float w = pad * 2 + labelW;
        int row = 1;
        rows.clear();
        rects.clear();
        for (int i = 0; i < items.size(); i++) {
            float cw = chipW(i);
            if (used > 0 && w + cw > maxW - pad) { w = pad * 2; row++; used = 0; }
            rects.add(new RectF(w, 0, w + cw, chipH));
            rows.add(row);
            w += cw + gap;
            used++;
        }
        float needW = pad * 2 + labelW;
        for (int i = 0; i < rects.size(); i++) {
            RectF r = rects.get(i);
            if (rows.get(i) == 1) needW = Math.max(needW, r.right + pad);
        }
        int height = (int) (pad * 2 + chipH * row + gap * (row - 1));
        setMeasuredDimension(resolveSize((int) Math.min(needW, maxW), wSpec), resolveSize(height, hSpec));
    }

    @Override
    protected void onDraw(Canvas cv) {
        float W = getWidth(), H = getHeight(), r = dp(10);
        RectF box = new RectF(dp(0.5f), dp(0.5f), W - dp(0.5f), H - dp(0.5f));
        cv.drawRoundRect(box, r, r, pBg);
        cv.drawRoundRect(box, r, r, pBd);

        float labelW = pLab.measureText("刷新率") + dp(6);
        cv.drawText("刷新率", pad, pad + chipH / 2f + textSize / 2.8f, pLab);

        for (int i = 0; i < items.size(); i++) {
            RectF c = new RectF(rects.get(i));
            float yOff = (rows.get(i) - 1) * (chipH + gap);
            c.offset(0, pad + yOff);
            boolean on = !values.get(i).isEmpty() && values.get(i).equals(activeHz);
            if (on) {
                RectF pill = new RectF(c);
                cv.drawRoundRect(pill, chipH / 2f, chipH / 2f, pOn);
            }
            Paint tp = on ? pOnTx : pTx;
            float tw = tp.measureText(items.get(i));
            cv.drawText(items.get(i), c.centerX() - tw / 2f, c.centerY() + textSize / 2.8f, tp);
        }
    }

    /* ---------- 点击 ---------- */
    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (e.getActionMasked() == MotionEvent.ACTION_OUTSIDE) {
            if (outsideCb != null) outsideCb.outside();
            return true;
        }
        if (e.getActionMasked() == MotionEvent.ACTION_UP && cb != null) {
            float x = e.getX(), y = e.getY();
            for (int i = 0; i < rects.size(); i++) {
                RectF c = new RectF(rects.get(i));
                c.offset(0, pad + (rows.get(i) - 1) * (chipH + gap));
                if (y >= c.top - dp(4) && y <= c.bottom + dp(4) && x >= c.left - dp(3) && x <= c.right + dp(3)) {
                    String v = values.get(i);
                    if (!v.isEmpty()) activeHz = v;
                    cb.pick(v);
                    return true;
                }
            }
        }
        return true;
    }
}
