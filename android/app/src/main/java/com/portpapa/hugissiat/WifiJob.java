package com.portpapa.hugissiat;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

/**
 * "와이파이에서만 올리기"를 켠 상태에서 데이터로 연결돼 있어 저장을 미뤘을 때 쓰는 예약 작업.
 * 와이파이(데이터 요금이 안 드는 연결)에 붙으면 시스템이 깨워 준다.
 * - 앱이 살아 있으면: 앱이 네트워크 변화를 직접 듣고 바로 이어서 올린다(여기서는 할 일 없음)
 * - 앱이 꺼져 있으면: "와이파이에 연결됐어요" 알림을 띄우고, 누르면 앱이 열리며 이어서 올린다
 */
public class WifiJob extends JobService {
    static final int ID = 7302;

    static void schedule(Context c) {
        try {
            JobScheduler js = (JobScheduler) c.getSystemService(Context.JOB_SCHEDULER_SERVICE);
            if (js == null || js.getPendingJob(ID) != null) return;
            js.schedule(new JobInfo.Builder(ID, new ComponentName(c, WifiJob.class))
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_UNMETERED)
                    .setPersisted(true).build());
        } catch (Exception ignored) { }
    }

    static void cancel(Context c) {
        try { JobScheduler js = (JobScheduler) c.getSystemService(Context.JOB_SCHEDULER_SERVICE); if (js != null) js.cancel(ID); } catch (Exception ignored) { }
    }

    @Override
    public boolean onStartJob(JobParameters p) {
        try {
            SharedPreferences sp = getSharedPreferences("chd", 0);
            int n = sp.getInt("wifiWait", 0);
            if (n > 0 && !MainActivity.alive) {
                NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
                if (nm != null) {
                    if (nm.getNotificationChannel(UploadService.CH) == null)
                        nm.createNotificationChannel(new NotificationChannel(UploadService.CH, "드라이브 업로드", NotificationManager.IMPORTANCE_LOW));
                    PendingIntent pi = PendingIntent.getActivity(this, ID, new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP),
                            PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
                    nm.notify(ID, new android.app.Notification.Builder(this, UploadService.CH).setSmallIcon(R.drawable.ic_stat)
                            .setContentTitle("와이파이에 연결됐어요")
                            .setContentText("눌러서 기다리던 드라이브 저장 " + n + "건을 이어서 해요")
                            .setContentIntent(pi).setAutoCancel(true).build());
                }
            }
        } catch (Exception ignored) { }
        return false;
    }

    @Override
    public boolean onStopJob(JobParameters p) { return false; }
}
