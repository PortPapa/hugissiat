package com.portpapa.hugissiat;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;

/**
 * 드라이브 저장이 도는 동안 앱 프로세스를 살려 두는 서비스.
 * 앱을 나가거나 최근 앱에서 밀어 닫아도 올리던 사진·영상을 끝까지 올린다.
 * 실제 업로드는 MainActivity의 업로드 대기열이 하고, 이 서비스는 알림만 붙잡고 있는다.
 * 알림 번호는 업로드 진행 알림과 같아서(7301) 진행 상황이 같은 알림에 그대로 보인다.
 */
public class UploadService extends Service {
    static final String CH = "upload";
    static final int NOTI = 7301;

    static void start(Context c, String text) {
        try {
            Intent i = new Intent(c, UploadService.class).putExtra("t", text);
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i); else c.startService(i);
        } catch (Exception ignored) { } // 앱이 뒤에 있을 땐 시작이 막힐 수 있다: 그땐 그냥 대기열만 돈다
    }

    static void stop(Context c) {
        try { c.stopService(new Intent(c, UploadService.class)); } catch (Exception ignored) { }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String t = intent != null ? intent.getStringExtra("t") : null;
        try {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null && nm.getNotificationChannel(CH) == null)
                nm.createNotificationChannel(new NotificationChannel(CH, "드라이브 업로드", NotificationManager.IMPORTANCE_LOW));
            PendingIntent pi = PendingIntent.getActivity(this, NOTI, new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP),
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            Notification n = new Notification.Builder(this, CH).setSmallIcon(R.drawable.ic_stat)
                    .setContentTitle("드라이브에 올리는 중").setContentText(t == null || t.isEmpty() ? "기록" : t)
                    .setContentIntent(pi).setOngoing(true).setOnlyAlertOnce(true).setProgress(0, 0, true).build();
            if (Build.VERSION.SDK_INT >= 29) startForeground(NOTI, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
            else startForeground(NOTI, n);
        } catch (Exception e) {
            stopSelf();
        }
        return START_NOT_STICKY;
    }

    @Override
    public void onTimeout(int startId, int fgsType) { // 안드로이드 15: 하루 사용 시간이 끝나면 멈춘다
        stopSelf();
    }

    @Override
    public void onDestroy() {
        // 마지막 '저장했어요/실패' 알림이 같은 번호라서, 서비스가 끝나도 알림은 남겨 둔다
        try { stopForeground(Service.STOP_FOREGROUND_DETACH); } catch (Exception ignored) { }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
