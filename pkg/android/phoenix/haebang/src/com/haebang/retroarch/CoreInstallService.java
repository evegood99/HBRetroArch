package com.haebang.retroarch;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import com.retroarch.R;

/**
 * 코어 받기 대기열을 차례로 처리하는 전경 서비스.
 * 공급자만으로 받으면 부른 쪽(adb 등)이 끊긴 뒤 프로세스가 '캐시됨' 으로 내려가 얼거나 죽을 수 있어서, 받는 동안은 전경으로 둔다.
 * targetSdk 28 이라 안드로이드 12+ 의 '뒤에서 전경 서비스 시작 금지' 는 걸리지 않는다.
 */
public class CoreInstallService extends Service
{
   private static final String CHANNEL = "hb_cores";
   private static final int NOTIFY_ID = 0x4842;

   private final Handler main = new Handler(Looper.getMainLooper());
   private Thread worker;
   private int lastStartId;
   private long lastNotify;

   static void start(Context ctx)
   {
      Intent i = new Intent(ctx, CoreInstallService.class);
      if (Build.VERSION.SDK_INT >= 26)
         ctx.startForegroundService(i);
      else
         ctx.startService(i);
   }

   @Override
   public int onStartCommand(Intent intent, int flags, int startId)
   {
      lastStartId = startId;
      startForeground(NOTIFY_ID, notification(getString(R.string.hb_core_preparing), 0, true));
      if (worker == null || !worker.isAlive())
      {
         worker = new Thread(new Runnable()
         {
            @Override
            public void run()
            {
               work();
            }
         }, "hb-core-install");
         worker.start();
      }
      return START_NOT_STICKY;
   }

   private void work()
   {
      CoreStore st = CoreStore.get(this);
      CoreStore.Job j;
      while ((j = st.takeNext()) != null)
      {
         st.run(j, new CoreStore.Listener()
         {
            @Override
            public void onProgress(CoreStore.Job job)
            {
               update(job);
            }
         });
      }
      main.post(new Runnable()
      {
         @Override
         public void run()
         {
            // 일꾼이 끝나는 사이 새로 들어온 일이 있으면 다시 돌린다. 끝낼 때는 마지막 시작 번호로만 끈다.
            if (CoreStore.get(CoreInstallService.this).hasQueued())
            {
               onStartCommand(null, 0, lastStartId);
               return;
            }
            stopForeground(true);
            stopSelf(lastStartId);
         }
      });
   }

   private void update(CoreStore.Job job)
   {
      long now = System.currentTimeMillis();
      if (now - lastNotify < 500 && job.active())
         return;
      lastNotify = now;
      String text;
      if (CoreStore.INSTALLING.equals(job.state))
         text = getString(R.string.hb_core_installing, job.core);
      else if (CoreStore.DOWNLOADING.equals(job.state))
         text = getString(R.string.hb_core_downloading, job.core, job.progress);
      else if (CoreStore.FAILED.equals(job.state))
         text = getString(R.string.hb_core_failed, job.core, job.error);
      else
         text = getString(R.string.hb_core_done, job.core);
      NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
      if (nm != null)
         nm.notify(NOTIFY_ID, notification(text, job.progress, job.total <= 0 && job.active()));
   }

   private Notification notification(String text, int progress, boolean indeterminate)
   {
      Notification.Builder b;
      if (Build.VERSION.SDK_INT >= 26)
      {
         NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
         if (nm != null && nm.getNotificationChannel(CHANNEL) == null)
         {
            nm.createNotificationChannel(new NotificationChannel(CHANNEL,
                  getString(R.string.hb_core_channel), NotificationManager.IMPORTANCE_LOW));
         }
         b = new Notification.Builder(this, CHANNEL);
      }
      else
      {
         b = new Notification.Builder(this);
      }
      b.setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, progress, indeterminate);
      return b.build();
   }

   @Override
   public IBinder onBind(Intent intent)
   {
      return null;
   }
}
