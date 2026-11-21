package com.reloop.pv;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.AudioManager;
import android.os.Build;
import android.os.Bundle;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.print.PrintAttributes;
import android.print.PrintManager;
import android.provider.Settings;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

public class MainActivity extends Activity {
    static final UUID SPP = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");
    WebView web;
    BluetoothAdapter ad;
    final Object wl = new Object();
    volatile BluetoothSocket sock;
    volatile OutputStream out;
    volatile int gen;
    volatile boolean af;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().setStatusBarColor(0xFF060D17);
        getWindow().setNavigationBarColor(0xFF060D17);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setVolumeControlStream(AudioManager.STREAM_MUSIC);
        web = new WebView(this);
        web.setBackgroundColor(0xFF060D17);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        web.setWebChromeClient(new WebChromeClient());
        web.addJavascriptInterface(new Bridge(), "Android");
        setContentView(web);
        web.loadUrl("file:///android_asset/index.html");
    }

    @Override
    protected void onDestroy() {
        close();
        if (web != null) { web.destroy(); web = null; }
        super.onDestroy();
    }

    void js(final String c) {
        runOnUiThread(() -> { if (web != null && !isDestroyed()) web.evaluateJavascript(c, null); });
    }

    void st(String s, String m) {
        js("window.onBtState&&window.onBtState(" + JSONObject.quote(s) + "," + JSONObject.quote(m == null ? "" : m) + ")");
    }

    void rx(String l) {
        js("window.onArduinoPacket&&window.onArduinoPacket(" + JSONObject.quote(l) + ")");
    }

    String[] need() {
        return Build.VERSION.SDK_INT >= 31
                ? new String[]{Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN}
                : new String[]{Manifest.permission.BLUETOOTH, Manifest.permission.BLUETOOTH_ADMIN};
    }

    boolean granted() {
        for (String p : need()) if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) return false;
        return true;
    }

    void pick() {
        ad = BluetoothAdapter.getDefaultAdapter();
        if (ad == null) { st("ERROR", "Bluetooth not supported on this device"); return; }
        if (!granted()) { requestPermissions(need(), 7); return; }
        if (!ad.isEnabled()) {
            try { startActivityForResult(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE), 8); }
            catch (Exception e) { st("ERROR", "Turn Bluetooth on"); }
            return;
        }
        list();
    }

    @Override
    public void onRequestPermissionsResult(int rc, String[] p, int[] r) {
        super.onRequestPermissionsResult(rc, p, r);
        if (rc != 7) return;
        if (granted()) pick();
        else st("ERROR", "Bluetooth permission denied. Allow Nearby devices in app settings.");
    }

    @Override
    protected void onActivityResult(int rq, int rs, Intent d) {
        super.onActivityResult(rq, rs, d);
        if (rq == 8) { if (rs == RESULT_OK) list(); else st("ERROR", "Bluetooth is off"); }
    }

    int hc(BluetoothDevice d) {
        try {
            String n = d.getName();
            return n != null && n.toUpperCase().replace("-", "").contains("HC05") ? 1 : 0;
        } catch (SecurityException e) { return 0; }
    }

    String nm(BluetoothDevice d) {
        try { String n = d.getName(); return n == null ? d.getAddress() : n; }
        catch (SecurityException e) { return d.getAddress(); }
    }

    void list() {
        final List<BluetoothDevice> devs = new ArrayList<>();
        try { devs.addAll(ad.getBondedDevices()); }
        catch (SecurityException e) { st("ERROR", "Bluetooth permission denied"); return; }
        Collections.sort(devs, (x, y) -> hc(y) - hc(x));
        AlertDialog.Builder bl = new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert).setTitle("Paired devices");
        if (devs.isEmpty()) {
            bl.setMessage("No paired devices. Pair HC-05 first in Bluetooth settings (PIN 1234 or 0000).")
              .setPositiveButton("Bluetooth settings", (x, i) -> startActivity(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS)))
              .setNegativeButton("Cancel", null);
        } else {
            String[] n = new String[devs.size()];
            for (int i = 0; i < n.length; i++) {
                BluetoothDevice v = devs.get(i);
                n[i] = (hc(v) > 0 ? "\u2605 " : "") + nm(v) + "\n" + v.getAddress();
            }
            bl.setItems(n, (x, i) -> open(devs.get(i))).setNegativeButton("Cancel", null);
        }
        bl.show();
    }

    void shut() {
        try { if (sock != null) sock.close(); } catch (Exception e) { }
        sock = null;
        out = null;
    }

    void close() {
        synchronized (wl) { gen++; shut(); }
    }

    void drop(int g, String s, String m) {
        synchronized (wl) { if (g != gen) return; shut(); }
        st(s, m);
    }

    boolean write(String s) {
        OutputStream o = out;
        if (o == null) return false;
        try {
            synchronized (o) { o.write(s.getBytes("UTF-8")); o.flush(); }
            return true;
        } catch (Exception e) {
            drop(gen, "DISCONNECTED", "Write failed");
            return false;
        }
    }

    void open(final BluetoothDevice dev) {
        close();
        final int g = ++gen;
        st("CONNECTING", nm(dev));
        new Thread(() -> {
            BluetoothSocket s = null;
            boolean ok = false;
            try {
                try { ad.cancelDiscovery(); } catch (Exception e) { }
                try {
                    s = dev.createRfcommSocketToServiceRecord(SPP);
                    s.connect();
                } catch (Exception e1) {
                    try { if (s != null) s.close(); } catch (Exception x) { }
                    s = (BluetoothSocket) dev.getClass().getMethod("createRfcommSocket", int.class).invoke(dev, 1);
                    s.connect();
                }
                synchronized (wl) {
                    if (g != gen) { try { s.close(); } catch (Exception x) { } return; }
                    sock = s;
                    out = s.getOutputStream();
                }
                ok = true;
                st("CONNECTED", nm(dev));
                write("R");
                BufferedReader r = new BufferedReader(new InputStreamReader(s.getInputStream(), "UTF-8"));
                String l;
                while ((l = r.readLine()) != null) {
                    l = l.trim();
                    if (!l.isEmpty()) rx(l);
                }
                drop(g, "DISCONNECTED", "Link closed");
            } catch (Exception e) {
                try { if (s != null) s.close(); } catch (Exception x) { }
                String m = e.getMessage() == null ? "Connection failed" : e.getMessage();
                drop(g, ok ? "DISCONNECTED" : "ERROR", ok ? "Link lost" : "Connect failed: " + m + ". Is HC-05 powered and paired?");
            }
        }, "bt-reader").start();
    }

    class Bridge {
        @JavascriptInterface
        public void connect() { runOnUiThread(MainActivity.this::pick); }

        @JavascriptInterface
        public void disconnect() { close(); st("DISCONNECTED", ""); }

        @JavascriptInterface
        public boolean send(String s) { return write(s); }

        @SuppressWarnings("deprecation")
        @JavascriptInterface
        public void vibrate(String p) {
            try {
                String[] a = p.split(",");
                long[] t = new long[a.length];
                for (int i = 0; i < a.length; i++) t[i] = Long.parseLong(a[i].trim());
                Vibrator v = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
                if (v == null || !v.hasVibrator()) return;
                if (Build.VERSION.SDK_INT >= 26) v.vibrate(VibrationEffect.createWaveform(t, -1));
                else v.vibrate(t, -1);
            } catch (Exception e) { }
        }

        @SuppressWarnings("deprecation")
        @JavascriptInterface
        public void focus(boolean on) {
            AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
            if (am == null) return;
            if (on && !af) af = am.requestAudioFocus(null, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
            else if (!on && af) { am.abandonAudioFocus(null); af = false; }
        }

        @JavascriptInterface
        public void print() {
            runOnUiThread(() -> {
                try {
                    PrintManager pm = (PrintManager) getSystemService(Context.PRINT_SERVICE);
                    pm.print("ReLoop-PV Report", web.createPrintDocumentAdapter("ReLoop-PV-Report"), new PrintAttributes.Builder().build());
                } catch (Exception e) { st("ERROR", "Print unavailable"); }
            });
        }
    }
}
