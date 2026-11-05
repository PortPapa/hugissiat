package com.portpapa.hugissiat;

import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.Context;

/** 위젯: 다음 갈 곳 + [메모] [다녀옴] */
public class NextPlanWidget extends AppWidgetProvider {
    @Override
    public void onUpdate(Context c, AppWidgetManager m, int[] ids) {
        for (int id : ids) m.updateAppWidget(id, Widgets.nextPlan(c));
    }
}
