package com.jeteezntmax.toolbox;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.InputStreamReader;

/**
 * 迷你监视器的数据源。
 *
 * CPU / GPU / 功耗 / 温度 全部直接读 sysfs —— App 有 root，不用走 shell，
 * 每 2 秒刷一次几乎不耗什么。
 *
 * 只有【帧率】必须走 dumpsys SurfaceFlinger，所以只有它要 su。
 * 而且路径五花八门（高通/MTK/三星/麒麟都不一样），所以每个都带一串候选，
 * 读不到就显示 "--"，绝不崩。
 */
public class SysStats {

    public int cores = 0;
    public long[] cpuCur = new long[16];
    public long[] cpuMax = new long[16];
    public long gpuCur = -1, gpuMax = -1;
    public double powerW = -1;       // 瓦
    public double tempC = -1;        // 摄氏度
    public String gpuName = "--";
    public String tempName = "--";
    public double fps = -1;

    /* ---------- 基础 ---------- */
    static String read(String path) {
        try {
            File f = new File(path);
            if (!f.exists() || !f.canRead()) return null;
            BufferedReader r = new BufferedReader(new FileReader(f), 64);
            String s = r.readLine();
            r.close();
            return s == null ? null : s.trim();
        } catch (Exception e) { return null; }
    }
    static long readLong(String path, long def) {
        String s = read(path);
        if (s == null) return def;
        try {
            // 有些节点是 "123456\n" 有些带单位，只取前面的数字
            int i = 0;
            if (i < s.length() && (s.charAt(i) == '-' || s.charAt(i) == '+')) i++;
            int j = i;
            while (j < s.length() && Character.isDigit(s.charAt(j))) j++;
            if (j == i) return def;
            return Long.parseLong(s.substring(i, j));
        } catch (Exception e) { return def; }
    }
    static String firstReadable(String[] paths) {
        for (String p : paths) { String s = read(p); if (s != null && s.length() > 0) return s; }
        return null;
    }
    static String firstPath(String[] paths) {
        for (String p : paths) { File f = new File(p); if (f.exists() && f.canRead()) return p; }
        return null;
    }

    /* ---------- 核心数 ---------- */
    private void detectCores() {
        String on = read("/sys/devices/system/cpu/online");
        int n = 0;
        if (on != null) {
            // 形如 "0-7" 或 "0-3,5-7"
            for (String part : on.split(",")) {
                part = part.trim();
                int d = part.indexOf('-');
                if (d > 0) {
                    try { n += Integer.parseInt(part.substring(d + 1)) - Integer.parseInt(part.substring(0, d)) + 1; }
                    catch (Exception ignored) { }
                } else if (part.length() > 0) n++;
            }
        }
        if (n <= 0) {
            for (int i = 0; i < 16; i++)
                if (new File("/sys/devices/system/cpu/cpu" + i).exists()) n = i + 1;
        }
        if (n <= 0) n = 8;
        if (n > 16) n = 16;
        cores = n;
    }

    /* ---------- CPU ---------- */
    private void readCpu() {
        for (int i = 0; i < cores; i++) {
            String base = "/sys/devices/system/cpu/cpu" + i + "/cpufreq/";
            long cur = readLong(base + "scaling_cur_freq", -1);
            if (cur < 0) cur = readLong(base + "cpuinfo_cur_freq", -1);
            if (cur < 0) cur = readLong(base + "scaling_max_freq", -1);
            long max = readLong(base + "cpuinfo_max_freq", -1);
            if (max < 0) max = readLong(base + "scaling_max_freq", -1);
            cpuCur[i] = cur < 0 ? 0 : cur;
            cpuMax[i] = max < 0 ? 1 : max;
        }
    }

    /* ---------- GPU ---------- */
    private static final String[] GPU_CUR = {
            "/sys/class/kgsl/kgsl-3d0/gpuclk",                  // 高通
            "/sys/class/kgsl/kgsl-3d0/devfreq/cur_freq",        // 高通(devfreq)
            "/sys/class/kgsl/kgsl-3d0/gpu_available_frequencies",
            "/sys/kernel/gpu/gpu_clock",                        // 部分 MTK
            "/proc/gpufreq/gpufreq_opp_freq",                   // MTK
            "/sys/class/devfreq/gpufreq/cur_freq",
            "/sys/kernel/debug/clk/gpu/clk_rate",
            "/sys/class/misc/mali0/device/clock",               // Mali
            "/sys/devices/platform/soc/*/kgsl/kgsl-3d0/gpuclk"
    };
    private static final String[] GPU_MAX = {
            "/sys/class/kgsl/kgsl-3d0/max_gpuclk",
            "/sys/class/kgsl/kgsl-3d0/devfreq/max_freq",
            "/sys/class/kgsl/kgsl-3d0/gpu_available_frequencies",
            "/sys/class/devfreq/gpufreq/max_freq"
    };
    private void readGpu() {
        String p = firstPath(GPU_CUR);
        if (p == null) { gpuCur = -1; gpuName = "--"; return; }
        gpuName = p.contains("kgsl") ? "Adreno" : (p.contains("gpufreq") || p.contains("gpu/") ? "GPU" : "GPU");
        String s = read(p);
        if (s == null) { gpuCur = -1; return; }
        try {
            if (p.endsWith("gpu_available_frequencies")) {
                // 形如 "800000000 700000000 500000000" 或 "800000 700000"
                String[] t = s.trim().split("\\s+");
                gpuMax = Long.parseLong(t[0]);
                gpuCur = Long.parseLong(t[0]);
            } else if (p.endsWith("gpufreq_opp_freq")) {
                // "1000000" 或 "0, 1000000, 0, 0"
                String[] t = s.replaceAll("[^0-9,]", "").split(",");
                long best = 0;
                for (String x : t) { if (x.length() > 0) { long v = Long.parseLong(x); if (v > best) best = v; } }
                gpuCur = best;
            } else {
                gpuCur = Long.parseLong(s.replaceAll("[^0-9]", ""));
            }
        } catch (Exception e) { gpuCur = -1; }
        String mp = firstPath(GPU_MAX);
        if (mp != null && gpuMax < 0) {
            try { gpuMax = Long.parseLong(read(mp).replaceAll("[^0-9]", "")); } catch (Exception ignored) { }
        }
        if (gpuMax <= 0) gpuMax = gpuCur;
    }

    /* ---------- 功耗 ---------- */
    private void readPower() {
        String[] curPaths = {
                "/sys/class/power_supply/battery/current_now",
                "/sys/class/power_supply/bms/current_now",
                "/sys/class/power_supply/maxfg/current_now",
                "/sys/class/power_supply/battery/current_avg"
        };
        String[] volPaths = {
                "/sys/class/power_supply/battery/voltage_now",
                "/sys/class/power_supply/bms/voltage_now",
                "/sys/class/power_supply/maxfg/voltage_now",
                "/sys/class/power_supply/battery/voltage_avg"
        };
        String cp = firstPath(curPaths), vp = firstPath(volPaths);
        if (cp == null || vp == null) { powerW = -1; return; }
        long ua = readLong(cp, 0);
        long uv = readLong(vp, 0);
        if (uv <= 0) { powerW = -1; return; }
        // 有的节点单位是 mA / mV，值特别小的时候换算一下
        double a = Math.abs(ua) > 20000 ? Math.abs(ua) / 1e6 : Math.abs(ua) / 1e3;
        double v = uv > 100000 ? uv / 1e6 : uv / 1e3;
        powerW = a * v;
        if (powerW > 200) powerW = -1;      // 明显不合理就当读失败
    }

    /* ---------- 温度 ---------- */
    private void readTemp() {
        // 优先找 CPU / GPU / 皮肤的 zone
        String[] prefer = {"cpu", "gpu", "soc", "skin", "ap", "tsens", "battery"};
        String zoneRoot = "/sys/class/thermal/";
        long best = -1; String bestName = "--";
        for (int i = 0; i < 60; i++) {
            File dir = new File(zoneRoot + "thermal_zone" + i);
            if (!dir.exists()) continue;
            String type = read(zoneRoot + "thermal_zone" + i + "/type");
            long t = readLong(zoneRoot + "thermal_zone" + i + "/temp", -1);
            if (type == null || t <= 0) continue;
            double c = t > 1000 ? t / 1000.0 : t;
            if (c < -40 || c > 150) continue;
            String lt = type.toLowerCase();
            for (int k = 0; k < prefer.length; k++) {
                if (lt.contains(prefer[k])) {
                    // 越靠前的越优先
                    if (best < 0 || k < 3) { best = (long)(c * 1000); bestName = type; }
                    break;
                }
            }
            if (best < 0 && c > 0) { best = (long)(c * 1000); bestName = type; }
        }
        if (best > 0) { tempC = best / 1000.0; tempName = bestName; }
        else tempC = -1;
    }

    /* ---------- 帧率（唯一要走 su 的） ---------- */
    private static final String FPS_SH =
            "L=$(dumpsys SurfaceFlinger --list 2>/dev/null | grep -E 'SurfaceView|Activity|#0' | tail -1); " +
            "[ -z \"$L\" ] && L=$(dumpsys SurfaceFlinger --list 2>/dev/null | tail -1); " +
            "if [ -z \"$L\" ]; then echo NA; else " +
            "dumpsys SurfaceFlinger --latency \"$L\" 2>/dev/null | tail -n +2 | " +
            "awk '$2!=0 && $2!=9223372036854775807 {t[n++]=$2} " +
            "END{ if(n<2){print \"NA\"; exit} " +
            "s=(n>60)?n-60:0; d=(t[n-1]-t[s])/1000000000.0; " +
            "if(d>0) printf \"%.1f\\n\",(n-1-s)/d; else print \"NA\" }'; fi";

    private void readFps(String suBin) {
        Process p = null;
        try {
            p = new ProcessBuilder(suBin, "-c", FPS_SH).redirectErrorStream(true).start();
            BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()), 256);
            String line;
            String last = null;
            while ((line = r.readLine()) != null) if (line.trim().length() > 0) last = line.trim();
            p.waitFor();
            if (last == null || last.equals("NA")) { fps = -1; return; }
            fps = Double.parseDouble(last);
            if (fps < 0 || fps > 240) fps = -1;
        } catch (Exception e) {
            fps = -1;
        } finally {
            if (p != null) try { p.destroy(); } catch (Exception ignored) { }
        }
    }

    /* ---------- 一次全采 ---------- */
    public void sample(String suBin) {
        if (cores <= 0) detectCores();
        readCpu();
        readGpu();
        readPower();
        readTemp();
        if (suBin != null) readFps(suBin);
    }
}
