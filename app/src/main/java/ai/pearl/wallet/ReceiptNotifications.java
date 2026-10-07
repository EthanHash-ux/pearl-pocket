package ai.pearl.wallet;

import android.app.*;
import android.app.job.*;
import android.content.*;
import android.os.Build;
import java.util.*;

final class ReceiptNotifications {
  static final int JOB = 808277;
  private static final String CHANNEL = "pearl_receipts";

  static List<String> addresses(Context c) throws Exception {
    List<String> result = new ArrayList<>();
    for (WalletCatalog.Entry entry : new WalletCatalog(c).list())
      if (entry.readable && !result.contains(entry.address)) result.add(entry.address);
    for (WatchBook.Entry e : new WatchBook(PublicStore.of(c)).list())
      if (!result.contains(e.address)) result.add(e.address);
    return result;
  }

  static boolean available(Context c) {
    NotificationManager m = c.getSystemService(NotificationManager.class);
    m.createNotificationChannel(
        new NotificationChannel(CHANNEL, "Pearl 收款与确认", NotificationManager.IMPORTANCE_DEFAULT));
    return (Build.VERSION.SDK_INT < 33
            || c.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                == android.content.pm.PackageManager.PERMISSION_GRANTED)
        && m.areNotificationsEnabled()
        && m.getNotificationChannel(CHANNEL).getImportance() != 0;
  }

  static void schedule(Context c) {
    try {
      JobScheduler j = c.getSystemService(JobScheduler.class);
      if (!c.getSharedPreferences("public_preferences", 0)
              .getBoolean("receipt_notifications", false)
          || addresses(c).isEmpty()
          || !available(c)) {
        j.cancel(JOB);
        return;
      }
      if (j.getPendingJob(JOB) == null)
        j.schedule(
            new JobInfo.Builder(JOB, new ComponentName(c, ReceiptJob.class))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPeriodic(15 * 60 * 1000L)
                .setPersisted(true)
                .build());
    } catch (Exception ignored) {
    }
  }

  static void check(Context c, String address, PearlApi.Account account) throws Exception {
    if (!c.getSharedPreferences("public_preferences", 0).getBoolean("receipt_notifications", false)
        || !addresses(c).contains(address)) return;
    boolean hidden =
        c.getSharedPreferences("public_preferences", 0).getBoolean("hide_balances", false);
    for (ReceiptTracker.Event e :
        new ReceiptTracker(PublicStore.of(c))
            .claim(
                address, account.transactions, available(c), System.currentTimeMillis() / 1000)) {
      Intent intent =
          new Intent(c, MainActivity.class)
              .putExtra("show_receipts", true)
              .putExtra("receipt_address", address)
              .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
      PendingIntent open =
          PendingIntent.getActivity(
              c,
              address.hashCode(),
              intent,
              PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
      Notification n =
          new Notification.Builder(c, CHANNEL)
              .setSmallIcon(R.drawable.ic_pearl)
              .setContentTitle(e.confirmed ? "Pearl 收款已有确认" : "发现 Pearl 待确认收款")
              .setContentText(
                  (hidden ? "金额已隐藏" : PearlAmount.format(e.tx.netGrains) + " PRL")
                      + " · "
                      + address.substring(0, 12)
                      + "…")
              .setContentIntent(open)
              .setAutoCancel(true)
              .setVisibility(Notification.VISIBILITY_PRIVATE)
              .build();
      c.getSystemService(NotificationManager.class)
          .notify(address + e.tx.id, e.confirmed ? 3 : 2, n);
    }
  }
}
