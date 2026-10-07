package ai.pearl.wallet;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import java.math.BigDecimal;

final class AlertNotifications {
    private static final String CHANNEL="pearl_price_alerts";
    private static final int JOB=808276;
    static boolean available(Context context){
        NotificationManager manager=context.getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel(CHANNEL,"Pearl 价格提醒",NotificationManager.IMPORTANCE_DEFAULT));
        return (Build.VERSION.SDK_INT<33||context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)==PackageManager.PERMISSION_GRANTED)
                &&manager.areNotificationsEnabled()&&manager.getNotificationChannel(CHANNEL).getImportance()!=NotificationManager.IMPORTANCE_NONE;
    }
    static void schedule(Context context){
        try {
            JobScheduler scheduler=context.getSystemService(JobScheduler.class);
            if(!new PriceAlerts(PublicStore.of(context)).active()||!available(context)){scheduler.cancel(JOB);return;}
            if(scheduler.getPendingJob(JOB)==null){
                int result=scheduler.schedule(new JobInfo.Builder(JOB,new ComponentName(context,PriceAlertJob.class))
                        .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setPeriodic(15*60*1000L).setPersisted(true).build());
                if(result!=JobScheduler.RESULT_SUCCESS)throw new IllegalStateException("系统未能安排后台检查");
            }
        }catch(Exception e){android.util.Log.w("PearlAlerts","Background scheduling unavailable",e);}
    }
    static boolean backgroundScheduled(Context context){return context.getSystemService(JobScheduler.class).getPendingJob(JOB)!=null;}
    static void check(Context context,BigDecimal usdt,long receivedAt)throws Exception{
        long now=System.currentTimeMillis()/1000;
        for(PriceAlerts.Alert alert:new PriceAlerts(PublicStore.of(context)).claim(usdt,receivedAt,now,available(context))){
            Intent intent=new Intent(context,MainActivity.class).putExtra("show_market",true).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP|Intent.FLAG_ACTIVITY_SINGLE_TOP);
            PendingIntent open=PendingIntent.getActivity(context,0,intent,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
            Notification notification=new Notification.Builder(context,CHANNEL).setSmallIcon(R.drawable.ic_pearl)
                    .setContentTitle("Pearl 价格提醒").setContentText("PRL 报价 "+usdt.toPlainString()+" USDT · "+alert.description())
                    .setStyle(new Notification.BigTextStyle().bigText("BigONE PRL/USDT 报价："+usdt.toPlainString()+" USDT\n"+alert.description()+"\n该提醒已触发一次，可在行情页重新创建。"))
                    .setContentIntent(open).setAutoCancel(true).setVisibility(Notification.VISIBILITY_PRIVATE).build();
            context.getSystemService(NotificationManager.class).notify(alert.id,1,notification);
        }
        schedule(context);
    }
}
