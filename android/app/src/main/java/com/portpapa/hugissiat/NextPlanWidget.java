package com.portpapa.hugissiat;

import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.Context;
import android.os.Bundle;

/** 위젯: 일정 (이번 주, 세로로 늘리면 이번 달) */
public class NextPlanWidget extends AppWidgetProvider {
    @Override
    public void onUpdate(Context c, AppWidgetManager m, int[] ids) {
        for (int id : ids) m.updateAppWidget(id, Widgets.tall(m, id) ? Widgets.nextPlan(c) : Widgets.week(c));
    }

    @Override
    public void onAppWidgetOptionsChanged(Context c, AppWidgetManager m, int id, Bundle o) {
        m.updateAppWidget(id, Widgets.tall(m, id) ? Widgets.nextPlan(c) : Widgets.week(c));
    }
}