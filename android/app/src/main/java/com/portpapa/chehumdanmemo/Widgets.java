package com.portpapa.chehumdanmemo;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.widget.RemoteViews;

import org.json.JSONObject;

/** 홈 화면 위젯 2종: 다음 갈 곳 / 바로가기. 데이터는 앱 화면이 syncWidget으로 넣어준다. */
public final class Widgets {
    static final String PREF = "widget";

    private Widgets() {}

    static JSONObject data(Context c) {
        try { return new JSONObject(c.getSharedPreferences(PREF, 0).getString("data", "{}")); }
        catch (Exception e) { return new JSONObject(); }
    }

    static PendingIntent open(Context c, String action, int code) {
        Intent i = new Intent(c, MainActivity.class).putExtra("action", action)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return PendingIntent.getActivity(c, code, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    static RemoteViews nextPlan(Context c) {
        RemoteViews v = new RemoteViews(c.getPackageName(), R.layout.widget_next);
        JSONObject d = data(c);
        JSONObject nx = d.optJSONObject("next");
        if (nx != null) {
            String id = nx.optString("id");
            v.setTextViewText(R.id.w_title, nx.optString("name"));
            v.setTextViewText(R.id.w_sub, nx.optString("label"));
            v.setTextViewText(R.id.w_btn1, "메모");
            v.setTextViewText(R.id.w_btn2, "다녀옴");
            v.setOnClickPendingIntent(R.id.w_btn1, open(c, "memo:" + id, 11));
            v.setOnClickPendingIntent(R.id.w_btn2, open(c, "plan:" + id, 12));
        } else {
            v.setTextViewText(R.id.w_title, "갈 곳 없음");
            v.setTextViewText(R.id.w_sub, "후기씨앗");
            v.setTextViewText(R.id.w_btn1, "추가");
            v.setTextViewText(R.id.w_btn2, "열기");
            v.setOnClickPendingIntent(R.id.w_btn1, open(c, "addplan", 13));
            v.setOnClickPendingIntent(R.id.w_btn2, open(c, "start", 14));
        }
        v.setOnClickPendingIntent(R.id.w_root, open(c, "start", 15));
        return v;
    }

    static RemoteViews quick(Context c) {
        RemoteViews v = new RemoteViews(c.getPackageName(), R.layout.widget_next);
        JSONObject d = data(c);
        v.setTextViewText(R.id.w_title, "후기씨앗");
        v.setTextViewText(R.id.w_sub, "갈 곳 " + d.optInt("plans") + " · 작성 중 " + d.optInt("drafts"));
        v.setTextViewText(R.id.w_btn1, "링크로 시작");
        v.setTextViewText(R.id.w_btn2, "현장 메모");
        v.setOnClickPendingIntent(R.id.w_btn1, open(c, "start", 21));
        v.setOnClickPendingIntent(R.id.w_btn2, open(c, "memo:", 22));
        v.setOnClickPendingIntent(R.id.w_root, open(c, "start", 23));
        return v;
    }

    static void updateAll(Context c) {
        AppWidgetManager m = AppWidgetManager.getInstance(c);
        int[] a = m.getAppWidgetIds(new ComponentName(c, NextPlanWidget.class));
        for (int id : a) m.updateAppWidget(id, nextPlan(c));
        int[] b = m.getAppWidgetIds(new ComponentName(c, QuickWidget.class));
        for (int id : b) m.updateAppWidget(id, quick(c));
    }
}
