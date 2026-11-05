package com.portpapa.chehumdanmemo;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.widget.RemoteViews;

import android.view.View;

import org.json.JSONArray;
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

    private static final int[][] ROWS = {
            {R.id.r1, R.id.r1_day, R.id.r1_name, R.id.r1_sub},
            {R.id.r2, R.id.r2_day, R.id.r2_name, R.id.r2_sub},
            {R.id.r3, R.id.r3_day, R.id.r3_name, R.id.r3_sub}};

    /** 갈 곳 일정: 방문 날짜가 정해진 곳 최대 3개, 그 아래 미정 */
    static RemoteViews nextPlan(Context c) {
        RemoteViews v = new RemoteViews(c.getPackageName(), R.layout.widget_plans);
        JSONObject d = data(c);
        JSONArray fx = d.optJSONArray("fixed");
        JSONArray tb = d.optJSONArray("tbd");
        int n = fx == null ? 0 : Math.min(3, fx.length());
        v.setTextViewText(R.id.w_title, "갈 곳 " + d.optInt("plans"));
        for (int i = 0; i < 3; i++) {
            int[] r = ROWS[i];
            if (i < n) {
                JSONObject p = fx.optJSONObject(i);
                v.setViewVisibility(r[0], View.VISIBLE);
                v.setTextViewText(r[1], p.optString("day"));
                v.setTextViewText(r[2], p.optString("name"));
                v.setTextViewText(r[3], p.optString("sub"));
                v.setOnClickPendingIntent(r[0], open(c, "planmenu:" + p.optString("id"), 30 + i));
            } else {
                v.setViewVisibility(r[0], View.GONE);
            }
        }
        v.setViewVisibility(R.id.w_empty, n == 0 ? View.VISIBLE : View.GONE);
        if (tb != null && tb.length() > 0) {
            StringBuilder sb = new StringBuilder("미정 " + tb.length() + " · ");
            for (int i = 0; i < tb.length(); i++) { if (i > 0) sb.append(", "); sb.append(tb.optString(i)); }
            v.setViewVisibility(R.id.w_tbd, View.VISIBLE);
            v.setTextViewText(R.id.w_tbd, sb.toString());
        } else {
            v.setViewVisibility(R.id.w_tbd, View.GONE);
        }
        v.setOnClickPendingIntent(R.id.w_btn1, open(c, "addplan", 13));
        v.setOnClickPendingIntent(R.id.w_btn2, open(c, "memo:", 12));
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
