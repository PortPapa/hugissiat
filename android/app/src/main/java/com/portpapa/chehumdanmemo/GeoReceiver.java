package com.portpapa.chehumdanmemo;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

import com.google.android.gms.location.Geofence;
import com.google.android.gms.location.GeofencingEvent;

import org.json.JSONObject;

/** 가게에 들어간 시각을 적어두고, 10분 넘게 있다가 나가면 알림 */
public class GeoReceiver extends BroadcastReceiver {
    static final String ACTION = "com.portpapa.chehumdanmemo.GEO";

    @Override
    public void onReceive(Context c, Intent intent) {
        GeofencingEvent ev = GeofencingEvent.fromIntent(intent);
        if (ev == null || ev.hasError() || ev.getTriggeringGeofences() == null) return;
        SharedPreferences p = GeoKeeper.prefs(c);
        long now = System.currentTimeMillis();
        int t = ev.getGeofenceTransition();
        for (Geofence g : ev.getTriggeringGeofences()) {
            String id = g.getRequestId();
            JSONObject f = GeoKeeper.find(c, id);
            if (f == null) continue;
            if (now < f.optLong("from") || now > f.optLong("to")) continue;
            if (t == Geofence.GEOFENCE_TRANSITION_ENTER) {
                if (p.getLong("in_" + id, 0) == 0) p.edit().putLong("in_" + id, now).apply();
            } else if (t == Geofence.GEOFENCE_TRANSITION_EXIT) {
                long in = p.getLong("in_" + id, 0);
                if (in == 0 || now - in < GeoKeeper.MIN_STAY || p.getBoolean("done_" + id, false)) continue;
                p.edit().putBoolean("done_" + id, true).putLong("out_" + id, now).apply();
                ReminderReceiver.cancel(c, id);
                ReminderReceiver.remove(c, id);
                String name = f.optString("name", "가게");
                ReminderReceiver.notifyPlan(c, id, name + " 다녀오셨어요?", "잊기 전에 30초만 남겨요. 누르면 바로 시작해요.");
            }
        }
    }
}
