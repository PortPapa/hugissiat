package com.portpapa.hugissiat;

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
import android.os.Bundle;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.json.JSONArray;
import org.json.JSONObject;

/** 홈 화면 위젯: 갈 곳 달력. 데이터는 앱 화면이 syncWidget으로 넣어준다. */
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

    /** 갈 곳 위젯: 앱 달력 탭과 같은 모양의 이번 달 달력 + 다음 일정 한 줄 */
    static RemoteViews nextPlan(Context c) {
        RemoteViews v = new RemoteViews(c.getPackageName(), R.layout.widget_plans);
        JSONObject d = data(c);
        JSONArray tb = d.optJSONArray("tbd");
        Calendar now = Calendar.getInstance();
        v.setImageViewBitmap(R.id.w_title, text(c, now.get(Calendar.YEAR) + "년 " + (now.get(Calendar.MONTH) + 1) + "월", 0xFF2E3A2D, true));
        int nt = tb == null ? 0 : tb.length();
        v.setViewVisibility(R.id.w_tbd, nt > 0 ? View.VISIBLE : View.GONE);
        v.setImageViewBitmap(R.id.w_tbd, text(c, "날짜 미정 " + nt, 0xFF9C5A70, true));
        v.setImageViewBitmap(R.id.w_cal, calendar(c, d, now));
        JSONArray fx = d.optJSONArray("fixed");
        JSONObject nx = fx != null && fx.length() > 0 ? fx.optJSONObject(0) : null;
        if (nx != null) {
            v.setViewVisibility(R.id.w_next, View.VISIBLE);
            v.setImageViewBitmap(R.id.w_next, nextLine(c, nx));
            v.setOnClickPendingIntent(R.id.w_next, open(c, "plan:" + nx.optString("id"), 18));
        } else v.setViewVisibility(R.id.w_next, View.GONE);
        v.setOnClickPendingIntent(R.id.w_add, open(c, "addplan", 13));
        v.setOnClickPendingIntent(R.id.w_tbd, open(c, "tab:plans", 16));
        v.setOnClickPendingIntent(R.id.w_cal, open(c, "tab:plans", 17));
        v.setOnClickPendingIntent(R.id.w_root, open(c, "tab:plans", 15));
        return v;
    }

    private static int color(String s, int def) {
        try { return Color.parseColor(s); } catch (Exception e) { return def; }
    }

    /** 다음 일정: [11/7(토)] 루미 네일 · 마감 D-1 */
    static Bitmap nextLine(Context c, JSONObject nx) {
        String day = nx.optString("day"), name = nx.optString("name"), sub = nx.optString("sub");
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setTextSize(40);
        Paint.FontMetrics fm = p.getFontMetrics();
        int H = 64; float base = H / 2f - (fm.ascent + fm.descent) / 2f;
        p.setTypeface(font(c, true)); float wDay = p.measureText(day);
        float wName = p.measureText(name);
        p.setTypeface(font(c, false)); String rest = sub.isEmpty() ? "" : "  " + sub; float wRest = p.measureText(rest);
        int W = (int) Math.min(1400, 28 + wDay + 28 + 18 + wName + wRest + 8);
        Bitmap b = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(b);
        Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG); bg.setColor(0xFFE6EEE3);
        cv.drawRoundRect(0, 4, 28 + wDay + 28, H - 4, 28, 28, bg);
        p.setTypeface(font(c, true)); p.setColor(0xFF4F6B4C); cv.drawText(day, 28, base, p);
        float x = 28 + wDay + 28 + 18;
        p.setColor(0xFF1F1F1F); cv.drawText(name, x, base, p); x += wName;
        p.setTypeface(font(c, false)); p.setColor(sub.contains("마감 D-1") || sub.contains("마감 오늘") || sub.contains("지남") ? 0xFFC0473A : 0xFF8A857D);
        cv.drawText(rest, x, base, p);
        return b;
    }

    /** 이번 달 달력: 흰 카드, 요일색(일 빨강·토 파랑), 오늘은 초록 테두리, 날짜 아래 점(방문=플랫폼색, 다녀옴=회색, 마감=빨간 테두리) */
    static Bitmap calendar(Context c, JSONObject d, Calendar now) {
        int acc = color(d.optString("acc"), 0xFF6F8E6D);
        if (Color.green(acc) < 60 && Color.red(acc) < 60) acc = 0xFF6F8E6D;
        int year = now.get(Calendar.YEAR), month = now.get(Calendar.MONTH), today = now.get(Calendar.DAY_OF_MONTH);
        Map<Integer, List<int[]>> marks = new HashMap<>();   // [type, color] type 0=방문 1=다녀옴 2=마감
        JSONArray days = d.optJSONArray("days");
        String prefix = String.format(Locale.ROOT, "%04d-%02d-", year, month + 1);
        if (days != null) for (int i = 0; i < days.length(); i++) {
            JSONObject o = days.optJSONObject(i);
            if (o == null || !o.optString("d").startsWith(prefix)) continue;
            int day = Integer.parseInt(o.optString("d").substring(8, 10));
            String k = o.optString("k", "v");
            int type = "due".equals(k) ? 2 : "done".equals(k) ? 1 : 0;
            marks.computeIfAbsent(day, x -> new ArrayList<>()).add(new int[]{type, color(o.optString("c"), acc)});
        }
        Calendar first = Calendar.getInstance();
        first.set(year, month, 1);
        int startDow = first.get(Calendar.DAY_OF_WEEK) - 1;
        int dim = first.getActualMaximum(Calendar.DAY_OF_MONTH);
        int rows = (startDow + dim + 6) / 7;
        int W = 760, pad = 22, headH = 66, rowH = 82;
        int H = pad + headH + rows * rowH + pad;
        Bitmap bm = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(bm);
        Paint card = new Paint(Paint.ANTI_ALIAS_FLAG);
        card.setColor(0xFFFFFFFF); cv.drawRoundRect(2, 2, W - 2, H - 2, 40, 40, card);
        card.setStyle(Paint.Style.STROKE); card.setStrokeWidth(2.5f); card.setColor(0xFFECE4D6); cv.drawRoundRect(2, 2, W - 2, H - 2, 40, 40, card);
        float cw = (W - pad * 2) / 7f;
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setTextAlign(Paint.Align.CENTER);
        String[] wd = {"일", "월", "화", "수", "목", "금", "토"};
        p.setTypeface(font(c, false)); p.setTextSize(27);
        for (int i = 0; i < 7; i++) {
            p.setColor(i == 0 ? 0xFFC9544F : i == 6 ? 0xFF4A72C4 : 0xFF8A857D);
            cv.drawText(wd[i], pad + cw * i + cw / 2, pad + 40, p);
        }
        for (int day = 1; day <= dim; day++) {
            int idx = startDow + day - 1, col = idx % 7, row = idx / 7;
            float cx = pad + cw * col + cw / 2, cy = pad + headH + rowH * row + 30;
            boolean isToday = day == today;
            if (isToday) {
                Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG); ring.setStyle(Paint.Style.STROKE); ring.setStrokeWidth(4.5f); ring.setColor(acc);
                cv.drawCircle(cx, cy - 1, 27, ring);
            }
            p.setTypeface(font(c, isToday)); p.setTextSize(33);
            p.setColor(col == 0 ? 0xFFC9544F : col == 6 ? 0xFF4A72C4 : 0xFF2B2B2B);
            cv.drawText(String.valueOf(day), cx, cy + 11, p);
            List<int[]> m = marks.get(day);
            if (m != null) {
                int n = Math.min(3, m.size());
                float gap = 17, sx = cx - gap * (n - 1) / 2f, dy = cy + 38;
                for (int j = 0; j < n; j++) {
                    int[] mk = m.get(j);
                    Paint dp = new Paint(Paint.ANTI_ALIAS_FLAG);
                    if (mk[0] == 2) { dp.setStyle(Paint.Style.STROKE); dp.setStrokeWidth(3.2f); dp.setColor(0xFFD9604A); cv.drawCircle(sx + gap * j, dy, 5.2f, dp); }
                    else { dp.setColor(mk[0] == 1 ? 0xFFB8B2A7 : mk[1]); cv.drawCircle(sx + gap * j, dy, 6.5f, dp); }
                }
            }
        }
        return bm;
    }

    /* ---------- 공용 그리기 ---------- */
    static final int INK = 0xFF2E3A2D, SUB = 0xFF8A857D, LINE = 0xFFECE4D6, SAGE = 0xFF6F8E6D, SAGE_D = 0xFF4F6B4C, SAGE_S = 0xFFE6EEE3, RED = 0xFFC0473A, ORANGE = 0xFFD08A1E;
    static final String[] WD = {"일", "월", "화", "수", "목", "금", "토"};

    static Paint pt(Context c, boolean bold, float size, int color) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG); p.setTypeface(font(c, bold)); p.setTextSize(size); p.setColor(color); return p;
    }
    static String fit(Paint p, String s, float max) {
        if (p.measureText(s) <= max) return s;
        while (s.length() > 1 && p.measureText(s + "…") > max) s = s.substring(0, s.length() - 1);
        return s + "…";
    }
    static void textV(Canvas cv, Paint p, String s, float x, float cy) {
        Paint.FontMetrics fm = p.getFontMetrics(); cv.drawText(s, x, cy - (fm.ascent + fm.descent) / 2, p);
    }
    static float chip(Canvas cv, Context c, String s, float x, float cy, float size, int fg, int bg) {
        Paint p = pt(c, true, size, fg); float w = p.measureText(s), h = size * 1.55f;
        Paint b = new Paint(Paint.ANTI_ALIAS_FLAG); b.setColor(bg);
        cv.drawRoundRect(x, cy - h / 2, x + w + size * 1.1f, cy + h / 2, h / 2, h / 2, b);
        textV(cv, p, s, x + size * .55f, cy);
        return w + size * 1.1f;
    }
    static void line(Canvas cv, float x1, float y, float x2) {
        Paint d = new Paint(Paint.ANTI_ALIAS_FLAG); d.setColor(LINE); d.setStrokeWidth(2); cv.drawLine(x1, y, x2, y, d);
    }
    static boolean tall(AppWidgetManager m, int id) {
        try { Bundle o = m.getAppWidgetOptions(id); return o.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT) >= 230; } catch (Exception e) { return false; }
    }
    static boolean hot(String s) { return s.contains("D-1") || s.contains("D-2") || s.contains("오늘") || s.contains("지남"); }

    /* ---------- ① 오늘 ---------- */
    static RemoteViews today(Context c) {
        RemoteViews v = new RemoteViews(c.getPackageName(), R.layout.widget_today);
        JSONObject d = data(c);
        JSONArray td = d.optJSONArray("today");
        JSONObject first = td != null && td.length() > 0 ? td.optJSONObject(0) : null;
        v.setImageViewBitmap(R.id.w_img, todayImg(c, d, td));
        v.setImageViewBitmap(R.id.w_btn1, text(c, "현장 메모", INK, true));
        if (first != null) {
            v.setImageViewBitmap(R.id.w_btn2, text(c, "다녀왔어요", 0xFFFFFFFF, true));
            v.setOnClickPendingIntent(R.id.w_btn1, open(c, "memo:" + first.optString("id"), 31));
            v.setOnClickPendingIntent(R.id.w_btn2, open(c, "visit:" + first.optString("id"), 32));
            v.setOnClickPendingIntent(R.id.w_img, open(c, "plan:" + first.optString("id"), 33));
        } else {
            v.setImageViewBitmap(R.id.w_btn2, text(c, "일정 추가", 0xFFFFFFFF, true));
            v.setOnClickPendingIntent(R.id.w_btn1, open(c, "memo:", 31));
            v.setOnClickPendingIntent(R.id.w_btn2, open(c, "addplan", 32));
            v.setOnClickPendingIntent(R.id.w_img, open(c, "tab:plans", 33));
        }
        v.setOnClickPendingIntent(R.id.w_root, open(c, "tab:home", 34));
        return v;
    }

    static Bitmap todayImg(Context c, JSONObject d, JSONArray td) {
        int W = 760, H = 230;
        Bitmap bm = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888); Canvas cv = new Canvas(bm);
        Calendar now = Calendar.getInstance();
        textV(cv, pt(c, true, 30, SAGE_D), "오늘 · " + (now.get(Calendar.MONTH) + 1) + "/" + now.get(Calendar.DAY_OF_MONTH) + " (" + WD[now.get(Calendar.DAY_OF_WEEK) - 1] + ")", 4, 26);
        int n = td == null ? 0 : td.length();
        if (n > 0) {
            JSONObject f = td.optJSONObject(0);
            float x = 4 + chip(cv, c, f.optString("time"), 4, 98, 32, SAGE_D, SAGE_S) + 16;
            Paint big = pt(c, true, 56, 0xFF1F1F1F);
            textV(cv, big, fit(big, f.optString("name"), W - x - (n > 1 ? 130 : 8)), x, 98);
            if (n > 1) chip(cv, c, "외 " + (n - 1) + "곳", W - 118, 98, 26, 0xFF9C5A70, 0xFFF6E2E9);
            String sub = f.optString("sub");
            if (!sub.isEmpty()) { Paint s = pt(c, false, 31, hot(sub) ? RED : SUB); textV(cv, s, fit(s, sub, W - 8), 4, 156); }
        } else {
            textV(cv, pt(c, true, 46, 0xFF1F1F1F), "오늘은 일정이 없어요", 4, 98);
        }
        JSONArray fx = d.optJSONArray("fixed");
        JSONObject nx = null;
        if (fx != null) for (int i = 0; i < fx.length() && nx == null; i++) {
            JSONObject o = fx.optJSONObject(i); if (o == null) continue;
            boolean same = false;
            if (td != null) for (int j = 0; j < td.length(); j++) if (o.optString("id").equals(td.optJSONObject(j).optString("id"))) same = true;
            if (!same) nx = o;
        }
        if (nx != null) { Paint s = pt(c, false, 28, SUB); textV(cv, s, fit(s, "다음  " + nx.optString("day") + "  " + nx.optString("name"), W - 8), 4, 208); }
        return bm;
    }

    /* ---------- ② 할 일 ---------- */
    static RemoteViews todo(Context c) {
        RemoteViews v = new RemoteViews(c.getPackageName(), R.layout.widget_card);
        JSONObject d = data(c);
        v.setImageViewBitmap(R.id.w_img, todoImg(c, d));
        v.setOnClickPendingIntent(R.id.w_img, open(c, "tab:todo", 41));
        v.setOnClickPendingIntent(R.id.w_root, open(c, "tab:todo", 42));
        return v;
    }

    static Bitmap todoImg(Context c, JSONObject d) {
        int W = 760, H = 330;
        Bitmap bm = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888); Canvas cv = new Canvas(bm);
        JSONObject t = d.optJSONObject("todo"); if (t == null) t = new JSONObject();
        textV(cv, pt(c, true, 32, INK), "할 일", 4, 26);
        String right = "올리기 " + t.optInt("post") + " · 쓰기 " + t.optInt("write");
        Paint r = pt(c, false, 26, SUB); textV(cv, r, right, W - 4 - r.measureText(right), 26);
        String[] lab = {"마감 지남", "3일 이내", "진행 중"};
        int[] val = {t.optInt("late"), t.optInt("soon"), t.optInt("prog")};
        int[] col = {RED, ORANGE, INK};
        float bw = (W - 8 - 24) / 3f;
        for (int i = 0; i < 3; i++) {
            float x = 4 + i * (bw + 12);
            Paint b = new Paint(Paint.ANTI_ALIAS_FLAG); b.setColor(0xFFFFFFFF); cv.drawRoundRect(x, 56, x + bw, 150, 22, 22, b);
            b.setStyle(Paint.Style.STROKE); b.setStrokeWidth(2.5f); b.setColor(LINE); cv.drawRoundRect(x, 56, x + bw, 150, 22, 22, b);
            Paint nv = pt(c, true, 44, val[i] == 0 && i < 2 ? 0xFFBDB8B0 : col[i]); nv.setTextAlign(Paint.Align.CENTER); textV(cv, nv, String.valueOf(val[i]), x + bw / 2, 92);
            Paint s = pt(c, false, 23, SUB); s.setTextAlign(Paint.Align.CENTER); textV(cv, s, lab[i], x + bw / 2, 128);
        }
        JSONArray l = t.optJSONArray("list");
        if (l == null || l.length() == 0) { textV(cv, pt(c, false, 30, SUB), "블로그에 올릴 글이 없어요", 4, 230); return bm; }
        for (int i = 0; i < Math.min(3, l.length()); i++) {
            JSONObject o = l.optJSONObject(i); float cy = 196 + i * 50;
            line(cv, 4, cy - 25, W - 4);
            Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG); dot.setColor("post".equals(o.optString("kind")) ? SAGE : 0xFFB8B2A7); cv.drawCircle(14, cy, 7, dot);
            String dd = o.optString("dd"); float cw = 0;
            if (!dd.isEmpty()) {
                cw = pt(c, true, 24, 0).measureText(dd) + 26;
                chip(cv, c, dd, W - 4 - cw, cy, 24, o.optBoolean("hot") ? RED : SUB, o.optBoolean("hot") ? 0xFFFBE3DF : 0xFFF0ECE4);
            }
            Paint nm = pt(c, true, 32, 0xFF1F1F1F);
            String label = o.optString("name") + ("write".equals(o.optString("kind")) ? "  · 기록 쓰는 중" : "");
            textV(cv, nm, fit(nm, label, W - 40 - cw - 12), 34, cy);
        }
        return bm;
    }

    /* ---------- ③ 일정: 기본은 이번 주, 세로로 늘리면 이번 달 달력 ---------- */
    static RemoteViews week(Context c) {
        RemoteViews v = new RemoteViews(c.getPackageName(), R.layout.widget_card);
        v.setImageViewBitmap(R.id.w_img, weekImg(c, data(c)));
        v.setOnClickPendingIntent(R.id.w_img, open(c, "tab:plans", 51));
        v.setOnClickPendingIntent(R.id.w_root, open(c, "tab:plans", 52));
        return v;
    }

    static Bitmap weekImg(Context c, JSONObject d) {
        int W = 760, H = 330;
        Bitmap bm = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888); Canvas cv = new Canvas(bm);
        int acc = color(d.optString("acc"), SAGE);
        Calendar now = Calendar.getInstance(); int dow = now.get(Calendar.DAY_OF_WEEK) - 1;
        JSONArray wk = d.optJSONArray("week"); if (wk == null) wk = new JSONArray();
        int visits = 0, dues = 0;
        for (int i = 0; i < wk.length(); i++) { if (wk.optJSONObject(i).optInt("due") == 1) dues++; else visits++; }
        textV(cv, pt(c, true, 32, INK), (now.get(Calendar.MONTH) + 1) + "월 " + d.optInt("wom", 1) + "째 주", 4, 26);
        String right = "방문 " + visits + " · 마감 " + dues;
        Paint r = pt(c, false, 26, SUB); textV(cv, r, right, W - 4 - r.measureText(right), 26);
        Calendar s = (Calendar) now.clone(); s.add(Calendar.DAY_OF_MONTH, -dow);
        float cw = (W - 8) / 7f;
        for (int i = 0; i < 7; i++) {
            float x = 4 + cw * i, cx = x + cw / 2; int day = s.get(Calendar.DAY_OF_MONTH);
            if (i == dow) { Paint b = new Paint(Paint.ANTI_ALIAS_FLAG); b.setColor(SAGE_S); cv.drawRoundRect(x + 6, 52, x + cw - 6, 156, 22, 22, b); }
            Paint wdp = pt(c, false, 23, i == 0 ? 0xFFC9544F : i == 6 ? 0xFF4A72C4 : SUB); wdp.setTextAlign(Paint.Align.CENTER); textV(cv, wdp, WD[i], cx, 72);
            Paint dp = pt(c, i == dow, 34, i == dow ? SAGE_D : i == 0 ? 0xFFC9544F : i == 6 ? 0xFF4A72C4 : 0xFF2B2B2B); dp.setTextAlign(Paint.Align.CENTER); textV(cv, dp, String.valueOf(day), cx, 108);
            List<int[]> ms = new ArrayList<>();
            for (int j = 0; j < wk.length(); j++) { JSONObject o = wk.optJSONObject(j); if (o.optInt("wd") == i) ms.add(new int[]{o.optInt("due"), color(o.optString("c"), acc)}); }
            int n = Math.min(3, ms.size()); float gx = 14;
            for (int j = 0; j < n; j++) {
                float dx = cx - gx * (n - 1) / 2f + gx * j; Paint mp = new Paint(Paint.ANTI_ALIAS_FLAG);
                if (ms.get(j)[0] == 1) { mp.setStyle(Paint.Style.STROKE); mp.setStrokeWidth(3f); mp.setColor(0xFFD9604A); cv.drawCircle(dx, 138, 5, mp); }
                else { mp.setColor(i < dow ? 0xFFB8B2A7 : ms.get(j)[1]); cv.drawCircle(dx, 138, 6, mp); }
            }
            s.add(Calendar.DAY_OF_MONTH, 1);
        }
        if (wk.length() == 0) { textV(cv, pt(c, false, 30, SUB), "이번 주 일정이 없어요", 4, 230); return bm; }
        float colW = (W - 8 - 24) / 2f;
        for (int j = 0; j < Math.min(4, wk.length()); j++) {
            JSONObject o = wk.optJSONObject(j); int col = j % 2, row = j / 2;
            float x = 4 + col * (colW + 24), cy = 206 + row * 56;
            line(cv, x, cy - 28, x + colW);
            boolean due = o.optInt("due") == 1;
            String lead = WD[o.optInt("wd")] + (due ? " 마감" : " " + o.optString("time"));
            Paint lp = pt(c, true, 27, due ? RED : SAGE_D); textV(cv, lp, lead, x, cy);
            float lw = lp.measureText(lead) + 14;
            Paint nm = pt(c, true, 29, 0xFF1F1F1F); textV(cv, nm, fit(nm, o.optString("name"), colW - lw), x + lw, cy);
        }
        if (wk.length() > 4) { Paint m = pt(c, false, 24, SUB); String tx = "+" + (wk.length() - 4); textV(cv, m, tx, W - 4 - m.measureText(tx), 318); }
        return bm;
    }

    static void updateAll(Context c) {
        AppWidgetManager m = AppWidgetManager.getInstance(c);
        for (int id : m.getAppWidgetIds(new ComponentName(c, NextPlanWidget.class))) m.updateAppWidget(id, tall(m, id) ? nextPlan(c) : week(c));
        for (int id : m.getAppWidgetIds(new ComponentName(c, TodayWidget.class))) m.updateAppWidget(id, today(c));
        for (int id : m.getAppWidgetIds(new ComponentName(c, TodoWidget.class))) m.updateAppWidget(id, todo(c));
    }
}
