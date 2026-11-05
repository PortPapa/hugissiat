package com.portpapa.hugissiat;

import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.Context;

/** 위젯: 바로가기 [링크로 시작] [현장 메모] */
public class QuickWidget extends AppWidgetProvider {
    @Override
    public void onUpdate(Context c, AppWidgetManager m, int[] ids) {
        for (int id : ids) m.updateAppWidget(id, Widgets.quick(c));
    }
}
