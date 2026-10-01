package com.stxaviers.app;

import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Environment;
import android.os.StatFs;
import android.util.DisplayMetrics;

import org.json.JSONObject;

import java.util.Locale;

/**
 * DEVICE SPECS (v1.1.8, owner spec 2026-09-30) — attached to every bug
 * report so developers know exactly what the reporter's phone is.
 *
 * Two forms:
 *  - collect()  → device.json: a structured snapshot (model, Android,
 *                 screen, RAM, storage, battery, network, app version…).
 *  - summary()  → one human line for bug.txt ("Device: …").
 *
 * PRIVACY: only hardware/app facts — no identifiers (no Android ID, no
 * IMEI, no serial), no location, no account data beyond what the report
 * itself already carries.
 */
public final class DeviceInfo {

    private DeviceInfo() {}

    public static JSONObject collect(Context c) {
        JSONObject o = new JSONObject();
        try {
            o.put("manufacturer", safe(Build.MANUFACTURER));
            o.put("model", safe(Build.MODEL));
            o.put("device", safe(Build.DEVICE));

            o.put("android", Build.VERSION.RELEASE == null
                    ? "?" : Build.VERSION.RELEASE);
            o.put("apiLevel", Build.VERSION.SDK_INT);
            o.put("securityPatch", Build.VERSION.SECURITY_PATCH == null
                    ? "?" : Build.VERSION.SECURITY_PATCH);

            // screen
            try {
                DisplayMetrics dm = c.getResources().getDisplayMetrics();
                o.put("screen", dm.widthPixels + "x" + dm.heightPixels
                        + " @" + Math.round(dm.density * 160) + "dpi");
                double inches = Math.sqrt(
                        Math.pow(dm.widthPixels / dm.xdpi, 2)
                                + Math.pow(dm.heightPixels / dm.ydpi, 2));
                o.put("screenInches", Math.round(inches * 10) / 10.0);
            } catch (Throwable ignored) {}

            // memory
            try {
                ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
                ActivityManager am = (ActivityManager)
                        c.getSystemService(Context.ACTIVITY_SERVICE);
                if (am != null) am.getMemoryInfo(mi);
                o.put("ramTotalMB", mi.totalMem / (1024 * 1024));
                o.put("ramAvailMB", mi.availMem / (1024 * 1024));
                o.put("lowMemory", mi.lowMemory);
            } catch (Throwable ignored) {}

            // storage (app data partition + shared storage)
            try {
                StatFs s = new StatFs(c.getFilesDir().getAbsolutePath());
                o.put("storageAvailGB", round1(s.getAvailableBytes() / 1e9));
                o.put("storageTotalGB", round1(s.getTotalBytes() / 1e9));
                StatFs ext = new StatFs(
                        Environment.getExternalStorageDirectory().getAbsolutePath());
                o.put("externalAvailGB", round1(ext.getAvailableBytes() / 1e9));
                o.put("externalTotalGB", round1(ext.getTotalBytes() / 1e9));
            } catch (Throwable ignored) {}

            // battery
            try {
                IntentFilter ifilter = new IntentFilter(
                        Intent.ACTION_BATTERY_CHANGED);
                Intent b = c.registerReceiver(null, ifilter);
                if (b != null) {
                    int level = b.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
                    int scale = b.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
                    if (level >= 0 && scale > 0)
                        o.put("batteryPct", Math.round(level * 100f / scale));
                    int status = b.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
                    o.put("charging", status == BatteryManager.BATTERY_STATUS_CHARGING
                            || status == BatteryManager.BATTERY_STATUS_FULL);
                }
            } catch (Throwable ignored) {}

            // network
            try {
                ConnectivityManager cm = (ConnectivityManager)
                        c.getSystemService(Context.CONNECTIVITY_SERVICE);
                NetworkInfo ni = cm == null ? null : cm.getActiveNetworkInfo();
                o.put("network", ni != null && ni.isConnected()
                        ? ni.getTypeName().toLowerCase(Locale.ROOT) : "offline");
            } catch (Throwable ignored) {}

            o.put("locale", Locale.getDefault().toString());
            o.put("timezone", java.util.TimeZone.getDefault().getID());

            // this app
            try {
                PackageManager pm = c.getPackageManager();
                String pn = c.getPackageName();
                o.put("appVersion", pm.getPackageInfo(pn, 0).versionName);
                o.put("appCode", pm.getPackageInfo(pn, 0).versionCode);
            } catch (Throwable ignored) {}

            // logging state (context for the dev reading the report)
            o.put("appLogging", XLog.enabled());
        } catch (Throwable ignored) {}
        return o;
    }

    /** One human line, e.g.
     *  "Samsung SM-A536E · Android 14 · 1080x2340 · 6.1" · 78% battery
     *   (charging) · wifi · XavierDrive 1.1.8 (21)". */
    public static String summary(Context c) {
        try {
            JSONObject o = collect(c);
            StringBuilder sb = new StringBuilder();
            String model = (o.optString("manufacturer", "") + " "
                    + o.optString("model", "")).trim();
            if (!model.isEmpty()) sb.append(model);
            if (o.has("android")) sb.append(" · Android ").append(o.optString("android"));
            if (o.has("screen")) sb.append(" · ").append(o.optString("screen"));
            if (o.has("screenInches"))
                sb.append(" (").append(o.optDouble("screenInches", 0)).append("\")");
            if (o.has("batteryPct")) {
                sb.append(" · ").append(o.optInt("batteryPct")).append("% battery");
                if (o.optBoolean("charging", false)) sb.append(" (charging)");
            }
            if (o.has("network")) sb.append(" · ").append(o.optString("network"));
            if (o.has("appVersion")) {
                sb.append(" · XavierDrive ").append(o.optString("appVersion"));
                if (o.has("appCode")) sb.append(" (").append(o.optInt("appCode")).append(")");
            }
            return sb.toString();
        } catch (Throwable t) {
            return "";
        }
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }

    private static double round1(double v) {
        return Math.round(v * 10) / 10.0;
    }
}
