package com.portpapa.chehumdanmemo;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.widget.RemoteViews;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.view.View;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.json.JSONArray;
import org.json.JSONObject;

/** 홈 화면 위젯 2종: 다음 갈 곳 / 바로가기. 데이터는 앱 화면이 syncWidget으로 넣어준다. */
public final class Widgets {
    static final String PREF = "widget";

    private Widgets() {}

    /* 위젯 글자는 앱 브랜드 글꼴(프리젠테이션)로 그림을 만들어 넣는다. RemoteViews는 글꼴 지정이 안 되기 때문 */
    private static Typeface tfBold, tfReg;

    static Typeface font(Context c, boolean bold) {
        try {
            if (bold) {
                if (tfBold == null) tfBold = Typeface.createFromAsset(c.getAssets(), "fonts/Freesentation-7Bold.ttf");
                return tfBold;
            }
            if (tfReg == null) tfReg = Typeface.createFromAsset(c.getAssets(), "fonts/Freesentation-4Regular.ttf");
            return tfReg;
        } catch (Exception e) {
            return Typeface.create(Typeface.DEFAULT, bold ? Typeface.BOLD : Typeface.NORMAL);
        }
    }

    static Bitmap text(Context c, String s, int color, boolean bold) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setTypeface(font(c, bold));
        p.setTextSize(64);
        p.setColor(color);
        Paint.FontMetrics fm = p.getFontMetrics();
        int w = Math.max(1, (int) Math.ceil(p.measureText(s)) + 4);
        int h = Math.max(1, (int) Math.ceil(fm.descent - fm.ascent));
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        new Canvas(b).drawText(s, 2, -fm.ascent, p);
        return b;
    }

    static JSONObject data(Context c) {
        try { return new JSONObject(c.getSharedPreferences(PREF, 0).getString("data", "{}")); }
        catch (Exception e) { return new JSONObject(); }
    }

    static PendingIntent open(Context c, String action, int code) {
        Intent i = new Intent(c, MainActivity.class).putExtra("action", action)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return PendingIntent.getActivity(c, code, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    /** 갈 곳 일정: 이번 달 캘린더에 방문 날짜를 표시하고, 미정은 개수만 */
    static RemoteViews nextPlan(Context c) {
        RemoteViews v = new RemoteViews(c.getPackageName(), R.layout.widget_plans);
        JSONObject d = data(c);
        JSONArray tb = d.optJSONArray("tbd");
        Calendar now = Calendar.getInstance();
        v.setImageViewBitmap(R.id.w_title, text(c, (now.get(Calendar.MONTH) + 1) + "월 갈 곳", 0xFF2E3A2D, true));
        int nt = tb == null ? 0 : tb.length();
        v.setViewVisibility(R.id.w_tbd, nt > 0 ? View.VISIBLE : View.GONE);
        v.setImageViewBitmap(R.id.w_tbd, text(c, "미정 " + nt, 0xFF9C5A70, true));
        v.setImageViewBitmap(R.id.w_cal, calendar(c, d, now));
        v.setOnClickPendingIntent(R.id.w_add, open(c, "addplan", 13));
        v.setOnClickPendingIntent(R.id.w_tbd, open(c, "tab:plans", 16));
        v.setOnClickPendingIntent(R.id.w_cal, open(c, "tab:plans", 17));
        v.setOnClickPendingIntent(R.id.w_root, open(c, "tab:plans", 15));
        return v;
    }

    private static int color(String s, int def) {
        try { return Color.parseColor(s); } catch (Exception e) { return def; }
    }

    /** 이번 달 캘린더 그림. 방문일은 플랫폼 색 동그라미, 오늘은 테두리 */
    static Bitmap calendar(Context c, JSONObject d, Calendar now) {
        int W = 700, H = 520;
        Bitmap bm = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(bm);
        int acc = color(d.optString("acc"), 0xFF6F8E6D);
        int year = now.get(Calendar.YEAR), month = now.get(Calendar.MONTH), today = now.get(Calendar.DAY_OF_MONTH);
        Map<Integer, List<Integer>> marks = new HashMap<>();
        JSONArray days = d.optJSONArray("days");
        String prefix = String.format(Locale.ROOT, "%04d-%02d-", year, month + 1);
        if (days != null) for (int i = 0; i < days.length(); i++) {
            JSONObject o = days.optJSONObject(i);
            if (o == null || !o.optString("d").startsWith(prefix)) continue;
            int day = Integer.parseInt(o.optString("d").substring(8, 10));
            marks.computeIfAbsent(day, k -> new ArrayList<>()).add(color(o.optString("c"), acc));
        }
        Calendar first = Calendar.getInstance();
        first.set(year, month, 1);
        int startDow = first.get(Calendar.DAY_OF_WEEK) - 1;
        int dim = first.getActualMaximum(Calendar.DAY_OF_MONTH);
        int rows = (startDow + dim + 6) / 7;
        float headH = 56, cw = W / 7f, ch = (H - headH) / rows;
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setTextAlign(Paint.Align.CENTER);
        p.setTypeface(font(c, true));
        String[] wd = {"일", "월", "화", "수", "목", "금", "토"};
        p.setTextSize(28);
        for (int i = 0; i < 7; i++) {
            p.setColor(i == 0 ? 0xFFD9534F : i == 6 ? 0xFF4A7BD0 : 0xFF8A857D);
            cv.drawText(wd[i], cw * i + cw / 2, 38, p);
        }
        float r = Math.min(cw, ch) * 0.36f;
        for (int day = 1; day <= dim; day++) {
            int idx = startDow + day - 1, col = idx % 7, row = idx / 7;
            float cx = cw * col + cw / 2, cy = headH + ch * row + ch / 2;
            List<Integer> m = marks.get(day);
            boolean past = day < today;
            if (m != null) {
                p.setStyle(Paint.Style.FILL);
                p.setColor(m.get(0));
                p.setAlpha(past ? 110 : 255);
                cv.drawCircle(cx, cy, r, p);
                p.setAlpha(255);
                if (m.size() > 1) {
                    p.setColor(0xFF1F1F1F);
                    p.setTextSize(20);
                    cv.drawText("+" + (m.size() - 1), cx + r * 0.95f, cy - r * 0.6f, p);
                }
            }
            if (day == today) {
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(4);
                p.setColor(acc);
                cv.drawCircle(cx, cy, r + (m != null ? 5 : 0), p);
                p.setStyle(Paint.Style.FILL);
            }
            p.setTextSize(30);
            if (m != null) p.setColor(0xFFFFFFFF);
            else p.setColor(past ? 0xFFBDB8B0 : (col == 0 ? 0xFFD9534F : col == 6 ? 0xFF4A7BD0 : 0xFF1F1F1F));
            cv.drawText(String.valueOf(day), cx, cy + 10, p);
        }
        return bm;
    }

    static RemoteViews quick(Context c) {
        RemoteViews v = new RemoteViews(c.getPackageName(), R.layout.widget_next);
        JSONObject d = data(c);
        v.setImageViewBitmap(R.id.w_title, text(c, "후기씨앗", 0xFF1F1F1F, true));
        v.setImageViewBitmap(R.id.w_sub, text(c, "갈 곳 " + d.optInt("plans") + " · 작성 중 " + d.optInt("drafts"), 0xFF6F6A62, false));
        v.setImageViewBitmap(R.id.w_btn1, text(c, "링크로 시작", 0xFF1F1F1F, true));
        v.setImageViewBitmap(R.id.w_btn2, text(c, "현장 메모", 0xFFFFFFFF, true));
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
