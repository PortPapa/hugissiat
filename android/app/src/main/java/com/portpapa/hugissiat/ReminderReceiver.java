package com.portpapa.hugissiat;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

import org.json.JSONObject;

import java.util.Iterator;

/**
 * 다녀온 뒤 알림. 예약 목록은 SharedPreferences에 두고,
 * 폰을 재부팅하거나 앱을 업데이트하면 남은 알림을 다시 예약한다.
 */
public class ReminderReceiver extends BroadcastReceiver {
    static final String ACTION = "com.portpapa.hugissiat.REMIND";
    private static final String PREF = "reminders";
    private static final String CHANNEL = "visit";

    private static PendingIntent pi(Context c, String id, String title, String body, int flags) {
        Intent i = new Intent(c, ReminderReceiver.class).setAction(ACTION)
                .putExtra("id", id).putExtra("title", title).putExtra("body", body);
        return PendingIntent.getBroadcast(c, id.hashCode(), i, flags | PendingIntent.FLAG_IMMUTABLE);
    }

    static void schedule(Context c, String id, long at, String title, String body) {
        AlarmManager am = c.getSystemService(AlarmManager.class);
        if (am == null) return;
        long when = Math.max(at, System.currentTimeMillis() + 5000);
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, when, pi(c, id, title, body, PendingIntent.FLAG_UPDATE_CURRENT));
    }

    static void cancel(Context c, String id) {
        AlarmManager am = c.getSystemService(AlarmManager.class);
        PendingIntent p = pi(c, id, "", "", PendingIntent.FLAG_NO_CREATE);
        if (am != null && p != null) am.cancel(p);
    }

    private static JSONObject all(Context c) {
        try { return new JSONObject(c.getSharedPreferences(PREF, 0).getString("all", "{}")); }
        catch (Exception e) { return new JSONObject(); }
    }

    private static void put(Context c, JSONObject o) {
        c.getSharedPreferences(PREF, 0).edit().putString("all", o.toString()).apply();
    }

    static void save(Context c, String id, long at, String title, String body) {
        try {
            JSONObject o = all(c);
            o.put(id, new JSONObject().put("at", at).put("title", title).put("body", body));
            put(c, o);
        } catch (Exception ignored) {}
    }

    static void remove(Context c, String id) {
        JSONObject o = all(c);
        o.remove(id);
        put(c, o);
    }

    @Override
    public void onReceive(Context c, Intent intent) {
        String a = intent.getAction();
        if (Intent.ACTION_BOOT_COMPLETED.equals(a) || Intent.ACTION_MY_PACKAGE_REPLACED.equals(a)) {
            JSONObject o = all(c);
            Iterator<String> it = o.keys();
            while (it.hasNext()) {
                String id = it.next();
                JSONObject r = o.optJSONObject(id);
                if (r != null) schedule(c, id, r.optLong("at"), r.optString("title"), r.optString("body"));
            }
            return;
        }
        if (!ACTION.equals(a)) return;
        String id = intent.getStringExtra("id");
        if (id == null) return;
        remove(c, id);
        notifyPlan(c, id, intent.getStringExtra("title"), intent.getStringExtra("body"));
    }

    /** 알림을 누르면 그 가게 질문 카드로 바로 들어간다 */
    static void notifyPlan(Context c, String id, String title, String body) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (nm == null) return;
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, "다녀온 뒤 알림", NotificationManager.IMPORTANCE_DEFAULT));
        Intent open = new Intent(c, MainActivity.class).putExtra("plan", id)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent tap = PendingIntent.getActivity(c, id.hashCode(), open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification n = new Notification.Builder(c, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat)
                .setColor(0xFF6F8E6D)
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(new Notification.BigTextStyle().bigText(body))
                .setAutoCancel(true)
                .setContentIntent(tap)
                .build();
        try { nm.notify(id.hashCode(), n); } catch (SecurityException ignored) {}
    }
}
