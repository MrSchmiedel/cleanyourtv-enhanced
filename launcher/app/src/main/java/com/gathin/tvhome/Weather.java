package com.gathin.tvhome;

import android.app.Activity;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.View;
import android.widget.TextView;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Enhanced fork: the outside temperature and a condition glyph in a pill left of the
 * Inputs button, from Open-Meteo (no account, no key) for coordinates baked into config.xml. Read every 30
 * minutes while Home is on screen (5 minutes after a failure), never in the background; the last reading is
 * kept so the pill is filled at once on the next start. The only network call besides the shelf's posters.
 */
final class Weather {
    private static final String TAG = "Weather";
    private static final long REFRESH_MS = 30 * 60_000L, RETRY_MS = 5 * 60_000L, STALE_MS = 10 * 60_000L;
    private final TextView pill;
    private final SharedPreferences prefs;
    private final String url;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Runnable tick = this::fetch;
    private boolean resumed;
    private long lastOk = -1;

    Weather(Activity activity, TextView pill, String latitude, String longitude, String unit) {
        this.pill = pill;
        // No pill background: the temperature and glyph sit plainly on the wallpaper, left of the Settings pill.
        pill.setBackground(null);
        pill.setPadding(0, pill.getPaddingTop(), Math.round(6 * activity.getResources().getDisplayMetrics().density), pill.getPaddingBottom());
        prefs = activity.getSharedPreferences("weather", 0);
        url = "https://api.open-meteo.com/v1/forecast?latitude=" + latitude + "&longitude=" + longitude
                + "&current=temperature_2m,weather_code,is_day&temperature_unit=" + unit + "&timezone=auto";
        if (prefs.contains("temp")) apply(prefs.getInt("temp", 0), prefs.getInt("code", 3), prefs.getBoolean("day", true));
    }

    void onResume() {
        resumed = true;
        long age = lastOk < 0 ? Long.MAX_VALUE : SystemClock.elapsedRealtime() - lastOk;
        if (age > STALE_MS) fetch();
        else schedule(REFRESH_MS - age);
    }

    void onPause() {
        resumed = false;
        main.removeCallbacks(tick);
    }

    private void schedule(long delay) {
        main.removeCallbacks(tick);
        if (resumed) main.postDelayed(tick, Math.max(1_000L, delay));
    }

    private void fetch() {
        main.removeCallbacks(tick);
        io.execute(() -> {
            try {
                JSONObject current = new JSONObject(get(url)).getJSONObject("current");
                int temp = (int) Math.round(current.getDouble("temperature_2m"));
                int code = current.getInt("weather_code");
                boolean day = current.optInt("is_day", 1) == 1;
                main.post(() -> {
                    apply(temp, code, day);
                    prefs.edit().putInt("temp", temp).putInt("code", code).putBoolean("day", day).apply();
                    lastOk = SystemClock.elapsedRealtime();
                    schedule(REFRESH_MS);
                });
            } catch (Exception e) {
                Log.w(TAG, "weather failed: " + e);
                main.post(() -> schedule(RETRY_MS));
            }
        });
    }

    private void apply(int temp, int code, boolean day) {
        pill.setText(temp + "\u00b0");
        pill.setCompoundDrawablesRelativeWithIntrinsicBounds(icon(code, day), 0, 0, 0);
        pill.setVisibility(View.VISIBLE);
    }

    /** WMO weather codes, as Open-Meteo reports them. */
    static int icon(int code, boolean day) {
        if (code == 0) return day ? R.drawable.ic_w_sun : R.drawable.ic_w_moon;
        if (code <= 2) return day ? R.drawable.ic_w_partly : R.drawable.ic_w_cloud;
        if (code == 3) return R.drawable.ic_w_cloud;
        if (code == 45 || code == 48) return R.drawable.ic_w_fog;
        if (code >= 51 && code <= 67 || code >= 80 && code <= 82) return R.drawable.ic_w_rain;
        if (code >= 71 && code <= 77 || code == 85 || code == 86) return R.drawable.ic_w_snow;
        if (code >= 95) return R.drawable.ic_w_thunder;
        return R.drawable.ic_w_cloud;
    }

    private static String get(String url) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(8000);
        conn.setReadTimeout(8000);
        conn.setRequestProperty("User-Agent", "cleanyourtv-launcher");
        try (BufferedReader in = new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"))) {
            StringBuilder b = new StringBuilder();
            String line;
            while ((line = in.readLine()) != null) b.append(line);
            return b.toString();
        } finally {
            conn.disconnect();
        }
    }
}
