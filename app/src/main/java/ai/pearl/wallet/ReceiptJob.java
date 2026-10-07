package ai.pearl.wallet;

import android.app.job.*;
import java.util.concurrent.*;

public final class ReceiptJob extends JobService {
  private final ExecutorService worker = Executors.newSingleThreadExecutor();
  private final Object lock = new Object();
  private int generation;

  public boolean onStartJob(JobParameters params) {
    final int token;
    synchronized (lock) {
      token = ++generation;
    }
    worker.execute(
        () -> {
          try {
            for (String address : ReceiptNotifications.addresses(this)) {
              synchronized (lock) {
                if (token != generation) return;
              }
              PearlApi.Account account = new PearlApi().history(address, 1, 100).account;
              synchronized (lock) {
                if (token != generation) return;
                ReceiptNotifications.check(this, address, account);
              }
            }
          } catch (Exception ignored) {
          }
          new android.os.Handler(getMainLooper())
              .post(
                  () -> {
                    synchronized (lock) {
                      if (token == generation) jobFinished(params, false);
                    }
                  });
        });
    return true;
  }

  public boolean onStopJob(JobParameters p) {
    synchronized (lock) {
      generation++;
    }
    return true;
  }

  public void onDestroy() {
    synchronized (lock) {
      generation++;
    }
    worker.shutdownNow();
    super.onDestroy();
  }
}
