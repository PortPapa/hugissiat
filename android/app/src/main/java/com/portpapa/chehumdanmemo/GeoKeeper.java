package com.portpapa.chehumdanmemo;

import android.Manifest;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;

import com.google.android.gms.location.Geofence;
import com.google.android.gms.location.GeofencingClient;
import com.google.android.gms.location.GeofencingRequest;
import com.google.android.gms.location.LocationServices;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 가게 나오면 알림: 방문일에 가게 반경 150m 지오펜스를 걸고,
 * 10분 넘게 머문 뒤 나가면 GeoReceiver가 알림을 띄운다. 위치는 폰 안에서만 쓴다.
 */
final class GeoKeeper {
    static final String PREF = "geo";
    static final float RADIUS = 150f;
    static final long MIN_STAY = 10 * 60 * 1000L;

    private GeoKeeper() {}

    static SharedPreferences prefs(Context c) { return c.getSharedPreferences(PREF, 0); }

    static boolean enabled(Context c) { return prefs(c).getBoolean("on", false); }

    static boolean hasPermission(Context c) {
        if (c.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return false;
        return c.checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    static PendingIntent pi(Context c) {
        Intent i = new Intent(c, GeoReceiver.class).setAction(GeoReceiver.ACTION);
        return PendingIntent.getBroadcast(c, 7701, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);
    }

    static void save(Context c, String json) {
        prefs(c).edit().putString("fences", json == null ? "[]" : json).apply();
    }

    static JSONObject find(Context c, String id) {
        try {
            JSONArray a = new JSONArray(prefs(c).getString("fences", "[]"));
            for (int i = 0; i < a.length(); i++) if (id.equals(a.getJSONObject(i).optString("id"))) return a.getJSONObject(i);
        } catch (Exception ignored) {}
        return null;
    }

    static void register(Context c) {
        GeofencingClient gc = LocationServices.getGeofencingClient(c);
        try { gc.removeGeofences(pi(c)); } catch (Exception ignored) {}
        if (!enabled(c) || !hasPermission(c)) return;
        List<Geofence> list = new ArrayList<>();
        long now = System.currentTimeMillis();
        try {
            JSONArray a = new JSONArray(prefs(c).getString("fences", "[]"));
            for (int i = 0; i < a.length() && list.size() < 90; i++) {
                JSONObject f = a.getJSONObject(i);
                long to = f.optLong("to");
                if (to <= now) continue;
                list.add(new Geofence.Builder()
                        .setRequestId(f.getString("id"))
                        .setCircularRegion(f.getDouble("lat"), f.getDouble("lng"), RADIUS)
                        .setExpirationDuration(to - now)
                        .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_ENTER | Geofence.GEOFENCE_TRANSITION_EXIT)
                        .build());
            }
        } catch (Exception ignored) {}
        if (list.isEmpty()) return;
        GeofencingRequest req = new GeofencingRequest.Builder()
                .setInitialTrigger(GeofencingRequest.INITIAL_TRIGGER_ENTER)
                .addGeofences(list).build();
        try { gc.addGeofences(req, pi(c)); } catch (SecurityException ignored) {}
    }
}
