package com.jeteezntmax.toolbox;

import java.io.BufferedReader;
import java.io.InputStreamReader;

/**
 * 迷你监视器的数据源。
 *
 * 分工：
 *   · CPU 利用率 / 电池温度 / 电流 / 电压 —— 一次 `su -c` 捞回来
 *     （App 跑在 u0_aXXX，不是 root，SELinux 不让它直接读 power_supply/*）
 *   · 帧率 —— 由 MonitorService 用 Choreographer 数帧，不在这里
 */
public class SysStats {

    public int cores = 8;
    public double cpuUsage = -1;     // 0~100
    public double fps = -1;          // 由外面塞进来
    public double powerW = -1;
    public double tempC = -1;
    public String cfg = "";          // 用户在 WebUI 里设的配色/字号

    /* ---------- 一次性采集脚本（不含帧率） ---------- */
    private static final String SCRIPT =
        "S=/sys/class/power_supply; " +
        "echo STAT $(head -n1 /proc/stat); " +
        "T=$(cat $S/battery/temp 2>/dev/null); [ -z \"$T\" ] && T=$(cat $S/bms/temp 2>/dev/null); " +
        "echo TEMP $T; " +
        "C=$(cat $S/battery/current_now 2>/dev/null); [ -z \"$C\" ] && C=$(cat $S/bms/current_now 2>/dev/null); " +
        "echo CUR $C; " +
        "V=$(cat $S/battery/voltage_now 2>/dev/null); [ -z \"$V\" ] && V=$(cat $S/bms/voltage_now 2>/dev/null); " +
        "echo VOLT $V; " +
        "echo CFG $(cat /data/adb/ksu_toolbox/monitor.conf 2>/dev/null | tr \"\\n\" \";\")";

    /* ---------- CPU 利用率：两次差值 ---------- */
    private long prevTotal = -1, prevIdle = -1;

    private void parseStat(String line) {
        String[] f = line.trim().split("\\s+");
        if (f.length < 6) return;
        long total = 0, idle = 0;
        try {
            for (int i = 1; i < f.length && i < 9; i++) total += Long.parseLong(f[i]);
            idle = Long.parseLong(f[4]) + Long.parseLong(f[5]);   // idle + iowait
        } catch (Exception e) { return; }
        if (prevTotal > 0 && total > prevTotal) {
            long dt = total - prevTotal, di = idle - prevIdle;
            if (dt > 0) {
                double u = (dt - di) * 100.0 / dt;
                if (u < 0) u = 0;
                if (u > 100) u = 100;
                cpuUsage = u;
            }
        }
        prevTotal = total;
        prevIdle = idle;
    }

    /* ---------- 一次采完 ---------- */
    private long curUA = Long.MIN_VALUE, voltUV = Long.MIN_VALUE;

    public void sample() {
        Process p = null;
        try {
            p = new ProcessBuilder(new String[]{"su", "-c", SCRIPT})
                    .redirectErrorStream(true).start();

            final Process fp = p;
            Thread et = new Thread(new Runnable() {
                public void run() {
                    try {
                        BufferedReader r = new BufferedReader(new InputStreamReader(fp.getErrorStream()), 256);
                        while (r.readLine() != null) { /* 排空 stderr，免得管道堵住 */ }
                    } catch (Exception ignored) { }
                }
            });
            et.start();

            BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()), 512);
            String line;
            long t0 = System.currentTimeMillis();
            while ((line = r.readLine()) != null) {
                line = line.trim();
                if (line.startsWith("STAT ")) parseStat(line.substring(5));
                else if (line.startsWith("TEMP ")) {
                    double c = parseTemp(line.substring(5).trim());
                    if (c >= 0) tempC = c;
                }
                else if (line.startsWith("CUR ")) curUA = parseLong(line.substring(4).trim(), Long.MIN_VALUE);
                else if (line.startsWith("VOLT ")) voltUV = parseLong(line.substring(5).trim(), Long.MIN_VALUE);
                else if (line.startsWith("CFG ")) cfg = line.substring(4).trim();
                if (System.currentTimeMillis() - t0 > 6000) break;
            }
            p.waitFor();
            et.join(400);
            computePower();
        } catch (Exception e) {
            // 读不到就保持上一次的值，不清零
        } finally {
            if (p != null) try { p.destroy(); } catch (Exception ignored) { }
        }
    }

    private void computePower() {
        if (curUA == Long.MIN_VALUE || voltUV == Long.MIN_VALUE) return;
        double a = Math.abs(curUA) > 20000 ? Math.abs(curUA) / 1e6 : Math.abs(curUA) / 1e3;
        double v = voltUV > 100000 ? voltUV / 1e6 : voltUV / 1e3;
        double w = a * v;
        if (w >= 0 && w < 200) powerW = w;
    }

    private static double parseTemp(String s) {
        long v = parseLong(s, Long.MIN_VALUE);
        if (v == Long.MIN_VALUE) return -1;
        double c = v > 100 ? v / 10.0 : v;      // 常见是十分之一度
        return (c > -20 && c < 120) ? c : -1;
    }
    private static long parseLong(String s, long def) {
        if (s == null) return def;
        try {
            int i = 0, n = s.length();
            while (i < n && !Character.isDigit(s.charAt(i)) && s.charAt(i) != '-') i++;
            int j = i;
            if (j < n && s.charAt(j) == '-') j++;
            while (j < n && Character.isDigit(s.charAt(j))) j++;
            if (j == i) return def;
            return Long.parseLong(s.substring(i, j));
        } catch (Exception e) { return def; }
    }
}
