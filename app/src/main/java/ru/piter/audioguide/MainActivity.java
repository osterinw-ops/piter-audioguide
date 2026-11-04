package ru.piter.audioguide;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.speech.tts.Voice;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONObject;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.WebResourceRequest;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity implements LocationListener {
    private WebView web;
    private TextToSpeech tts;
    private boolean ttsReady = false;
    private String lastUtterance = null;
    private LocationManager lm;
    private boolean gpsWanted = false;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private static final int REQ_LOC = 1;
    private SharedPreferences prefs;
    private float pitch = 1.0f;

    private void initTts(String engine) {
        if (tts != null) { try { tts.stop(); tts.shutdown(); } catch (Exception ignored) {} }
        ttsReady = false;
        TextToSpeech.OnInitListener l = status -> {
            if (status == TextToSpeech.SUCCESS) {
                int r = tts.setLanguage(new Locale("ru", "RU"));
                ttsReady = r != TextToSpeech.LANG_MISSING_DATA && r != TextToSpeech.LANG_NOT_SUPPORTED;
                if (!ttsReady) toast("В этом движке нет русского голоса. Выберите другой или установите русский язык в настройках синтеза речи.");
                String v = prefs.getString("voice", null);
                if (v != null && tts.getVoices() != null) for (Voice x : tts.getVoices()) if (x.getName().equals(v)) { tts.setVoice(x); break; }
                pitch = prefs.getFloat("pitch", 1.0f);
                tts.setPitch(pitch);
                tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                    @Override public void onStart(String id) {}
                    @Override public void onError(String id) {}
                    @Override public void onDone(String id) {
                        if (id.equals(lastUtterance)) js("window.onSpeechDone && onSpeechDone()");
                    }
                });
                js("window.onTtsReady && onTtsReady()");
            } else toast("Синтез речи недоступен");
        };
        tts = (engine == null) ? new TextToSpeech(this, l) : new TextToSpeech(this, l, engine);
    }


    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        web = new WebView(this);
        setContentView(web);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        web.setWebChromeClient(new WebChromeClient());
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                String u = r.getUrl().toString();
                if (u.startsWith("file:")) return false;
                openExternal(u);
                return true;
            }
        });
        web.addJavascriptInterface(new Bridge(), "Android");
        web.loadUrl("file:///android_asset/www/index.html");

        prefs = getSharedPreferences("tts", MODE_PRIVATE);
        initTts(prefs.getString("engine", null));
        lm = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
    }

    private void js(String code) { ui.post(() -> web.evaluateJavascript(code, null)); }
    private void toast(String t) { ui.post(() -> Toast.makeText(this, t, Toast.LENGTH_LONG).show()); }

    private void openExternal(String u) {
        try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(u))); }
        catch (Exception e) { toast("Не удалось открыть ссылку"); }
    }

    private void startGps() {
        gpsWanted = true;
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION}, REQ_LOC);
            return;
        }
        try {
            if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER))
                lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 2000, 3, this);
            if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER))
                lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 5000, 10, this);
            if (!lm.isProviderEnabled(LocationManager.GPS_PROVIDER))
                js("onGpsStatus('Включите геолокацию')");
            else js("onGpsStatus('Ищу спутники…')");
        } catch (SecurityException e) { js("onGpsStatus('Нет доступа к GPS')"); }
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] p, int[] g) {
        super.onRequestPermissionsResult(code, p, g);
        if (code == REQ_LOC) {
            if (g.length > 0 && g[0] == PackageManager.PERMISSION_GRANTED) startGps();
            else js("onGpsStatus('GPS не разрешён')");
        }
    }

    @Override public void onLocationChanged(Location l) {
        js("onLocation(" + l.getLatitude() + "," + l.getLongitude() + "," + l.getAccuracy() + ")");
    }
    @Override public void onStatusChanged(String p, int s, Bundle e) {}
    @Override public void onProviderEnabled(String p) {}
    @Override public void onProviderDisabled(String p) {}

    @Override protected void onResume() { super.onResume(); if (gpsWanted) startGps(); }
    @Override protected void onPause() { super.onPause(); try { lm.removeUpdates(this); } catch (Exception ignored) {} }
    @Override protected void onDestroy() { if (tts != null) { tts.stop(); tts.shutdown(); } super.onDestroy(); }
    @Override public void onBackPressed() { if (web.canGoBack()) web.goBack(); else super.onBackPressed(); }

    private List<String> chunks(String text) {
        List<String> out = new ArrayList<>();
        int max = Math.min(3500, TextToSpeech.getMaxSpeechInputLength() - 10);
        for (String para : text.split("\\n\\s*\\n")) {
            String p = para.trim();
            while (p.length() > max) {
                int cut = p.lastIndexOf(". ", max);
                if (cut < 100) cut = max;
                out.add(p.substring(0, cut + 1));
                p = p.substring(cut + 1).trim();
            }
            if (!p.isEmpty()) out.add(p);
        }
        return out;
    }

    class Bridge {
        @JavascriptInterface public void speak(String text, float rate) {
            if (!ttsReady) { toast("Голос ещё не готов или нет русского языка"); return; }
            tts.stop();
            tts.setSpeechRate(rate);
            List<String> parts = chunks(text);
            for (int i = 0; i < parts.size(); i++) {
                String id = "u" + System.nanoTime() + "_" + i;
                if (i == parts.size() - 1) lastUtterance = id;
                Bundle p = new Bundle();
                tts.speak(parts.get(i), TextToSpeech.QUEUE_ADD, p, id);
                if (i < parts.size() - 1) tts.playSilentUtterance(600, TextToSpeech.QUEUE_ADD, id + "s");
            }
        }
        @JavascriptInterface public void stop() { if (tts != null) tts.stop(); }
        @JavascriptInterface public String getEngines() {
            JSONArray a = new JSONArray();
            try { for (TextToSpeech.EngineInfo e : tts.getEngines()) { JSONObject o = new JSONObject(); o.put("name", e.name); o.put("label", e.label); a.put(o); }
                  } catch (Exception ignored) {}
            return a.toString();
        }
        @JavascriptInterface public String currentEngine() { try { return tts.getDefaultEngine(); } catch (Exception e) { return ""; } }
        @JavascriptInterface public String selectedEngine() { String e = prefs.getString("engine", null); return e == null ? "" : e; }
        @JavascriptInterface public void setEngine(String name) {
            prefs.edit().putString("engine", name).remove("voice").apply();
            ui.post(() -> initTts(name));
        }
        @JavascriptInterface public String getVoices() {
            JSONArray a = new JSONArray();
            try { for (Voice v : tts.getVoices()) {
                if (v.getLocale() == null || !"ru".equals(v.getLocale().getLanguage())) continue;
                if (v.getFeatures() != null && v.getFeatures().contains("notInstalled")) continue;
                JSONObject o = new JSONObject(); o.put("name", v.getName()); o.put("net", v.isNetworkConnectionRequired()); o.put("q", v.getQuality()); a.put(o); }
            } catch (Exception ignored) {}
            return a.toString();
        }
        @JavascriptInterface public String currentVoice() { try { return tts.getVoice() != null ? tts.getVoice().getName() : ""; } catch (Exception e) { return ""; } }
        @JavascriptInterface public void setVoice(String name) {
            try { for (Voice v : tts.getVoices()) if (v.getName().equals(name)) { tts.setVoice(v); prefs.edit().putString("voice", name).apply(); break; } } catch (Exception ignored) {}
        }
        @JavascriptInterface public void setPitch(float p) { pitch = p; prefs.edit().putFloat("pitch", p).apply(); if (tts != null) tts.setPitch(p); }
        @JavascriptInterface public void openTtsSettings() { ui.post(() -> { try { startActivity(new Intent("com.android.settings.TTS_SETTINGS")); } catch (Exception e) { toast("Не удалось открыть настройки"); } }); }
        @JavascriptInterface public void startGps() { ui.post(MainActivity.this::startGps); }
        @JavascriptInterface public void openUrl(String u) { ui.post(() -> openExternal(u)); }
        @JavascriptInterface public void keepScreenOn(boolean on) {
            ui.post(() -> {
                if (on) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            });
        }
        @JavascriptInterface public void vibrate() {
            Vibrator v = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
            if (v == null) return;
            if (Build.VERSION.SDK_INT >= 26) v.vibrate(VibrationEffect.createWaveform(new long[]{0, 250, 150, 250}, -1));
            else v.vibrate(400);
        }
    }
}
