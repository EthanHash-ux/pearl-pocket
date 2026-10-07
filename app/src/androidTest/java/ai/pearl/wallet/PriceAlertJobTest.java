package ai.pearl.wallet;

import android.Manifest;
import android.app.NotificationManager;
import android.app.job.JobScheduler;
import android.content.Context;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.UiDevice;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class PriceAlertJobTest {
    @Test public void livePublicPoolUsesPearlAndPhysicalHashrateUnits()throws Exception{
        MiningApi.Stats stats=new MiningApi().stats();assertTrue(stats.networkHashRate.signum()>0);assertTrue(stats.hashRate.signum()>=0);assertTrue(stats.height>0);
        assertTrue(stats.share().compareTo(new java.math.BigDecimal("100"))<=0);assertTrue(System.currentTimeMillis()/1000-stats.receivedAt<30);
    }
    @Test public void backgroundJobFetchesPublicQuoteAndNotifiesOnceWithoutOpeningWallet()throws Exception{
        Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();UiDevice device=UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        assertEquals("1",device.executeShellCommand("getprop ro.kernel.qemu").trim());device.pressHome();Thread.sleep(400);
        InstrumentationRegistry.getInstrumentation().getUiAutomation().grantRuntimePermission(context.getPackageName(),Manifest.permission.POST_NOTIFICATIONS);
        context.getSharedPreferences("public_tools",Context.MODE_PRIVATE).edit().remove("alerts").commit();context.getSystemService(NotificationManager.class).cancelAll();
        PriceAlerts alerts=new PriceAlerts(PublicStore.of(context));PriceAlerts.Alert alert=alerts.add("0.00000001",true);
        try{
            AlertNotifications.schedule(context);assertNotNull(context.getSystemService(JobScheduler.class).getPendingJob(808276));
            String result=device.executeShellCommand("cmd jobscheduler run -f ai.pearl.wallet 808276");assertTrue(result,result.contains("Running job"));
            long deadline=System.currentTimeMillis()+40000;while(alerts.active()&&System.currentTimeMillis()<deadline)Thread.sleep(300);
            assertFalse("Background public quote did not fire the alert",alerts.active());
            long notificationDeadline=System.currentTimeMillis()+5000;while(context.getSystemService(NotificationManager.class).getActiveNotifications().length==0&&System.currentTimeMillis()<notificationDeadline)Thread.sleep(100);
            android.service.notification.StatusBarNotification[] posted=context.getSystemService(NotificationManager.class).getActiveNotifications();assertEquals(1,posted.length);assertEquals(alert.id,posted[0].getTag());
            assertNull("No active alerts should leave a periodic job",context.getSystemService(JobScheduler.class).getPendingJob(808276));
            AlertNotifications.check(context,new java.math.BigDecimal("2"),System.currentTimeMillis()/1000);assertEquals(1,context.getSystemService(NotificationManager.class).getActiveNotifications().length);
        }finally{alerts.remove(alert.id);AlertNotifications.schedule(context);context.getSystemService(NotificationManager.class).cancelAll();}
    }
}
