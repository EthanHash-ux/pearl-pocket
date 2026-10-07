package ai.pearl.wallet;

import android.app.job.*;
import android.content.*;
import java.util.*;
import java.util.concurrent.*;

/** Android controls background timing. Public metrics only; no long-running socket. */
public final class MiningWidgetJob extends JobService {
  static final int PERIODIC = 808279, MANUAL = 808280;
  private final ExecutorService worker = Executors.newSingleThreadExecutor();
  private final Object lock = new Object();
  private final Map<Integer, Object> active = new HashMap<>();

  static void schedule(Context c, boolean refresh) {
    JobScheduler scheduler = (JobScheduler) c.getSystemService(Context.JOB_SCHEDULER_SERVICE);
    if (MiningWidget.ids(c).length == 0) {
      cancel(c);
      return;
    }
    ComponentName component = new ComponentName(c, MiningWidgetJob.class);
    if (scheduler.getPendingJob(PERIODIC) == null)
      scheduler.schedule(
          new JobInfo.Builder(PERIODIC, component)
              .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
              .setPeriodic(15 * 60 * 1000L)
              .setPersisted(true)
              .build());
    if (refresh && scheduler.getPendingJob(MANUAL) == null)
      scheduler.schedule(
          new JobInfo.Builder(MANUAL, component)
              .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
              .setMinimumLatency(0)
              .build());
  }

  static void cancel(Context c) {
    JobScheduler s = (JobScheduler) c.getSystemService(Context.JOB_SCHEDULER_SERVICE);
    s.cancel(PERIODIC);
    s.cancel(MANUAL);
  }

  @Override
  public boolean onStartJob(JobParameters p) {
    Object token = new Object();
    synchronized (lock) {
      active.put(p.getJobId(), token);
    }
    worker.execute(
        () -> {
          try {
            Map<String, FortuneApi.Snapshot> cache = new HashMap<>();
            Set<String> failed = new HashSet<>();
            for (int id : MiningWidget.ids(this)) {
              if (!current(p, token)) return;
              MiningWidget.Config cfg = MiningWidget.config(this, id);
              if (cfg == null) continue;
              if (!cache.containsKey(cfg.key()) && !failed.contains(cfg.key())) {
                try {
                  cache.put(cfg.key(), new FortuneApi().fetch(cfg.address));
                } catch (Exception e) {
                  failed.add(cfg.key());
                }
              }
              if (!current(p, token)) return;
              synchronized (lock) {
                if (active.get(p.getJobId()) != token) return;
                MiningWidget.result(
                    this, id, cfg, cache.get(cfg.key()), failed.contains(cfg.key()) ? "读取失败" : "");
              }
            }
            if (current(p, token))
              try {
                MarketQuote q = new PearlApi().marketQuote();
                synchronized (lock) {
                  if (active.get(p.getJobId()) == token && q.usdt != null && q.change24h != null)
                    PriceWidget.cache(this, q.usdt, q.change24h, q.receivedAt, true);
                  else if (active.get(p.getJobId()) == token) {
                    MiningWidget.prefs(this).edit().putBoolean("price_error", true).apply();
                    MiningWidget.render(this);
                  }
                }
              } catch (Exception ignored) {
                synchronized (lock) {
                  if (active.get(p.getJobId()) == token) {
                    MiningWidget.prefs(this).edit().putBoolean("price_error", true).apply();
                    MiningWidget.render(this);
                  }
                }
              }
          } catch (Exception ignored) {
          }
          new android.os.Handler(getMainLooper())
              .post(
                  () -> {
                    synchronized (lock) {
                      if (active.get(p.getJobId()) == token) {
                        active.remove(p.getJobId());
                        jobFinished(p, false);
                      }
                    }
                  });
        });
    return true;
  }

  @Override
  public boolean onStopJob(JobParameters p) {
    synchronized (lock) {
      active.remove(p.getJobId());
    }
    return true;
  }

  @Override
  public void onDestroy() {
    synchronized (lock) {
      active.clear();
    }
    worker.shutdownNow();
    super.onDestroy();
  }

  private boolean current(JobParameters p, Object token) {
    synchronized (lock) {
      return active.get(p.getJobId()) == token;
    }
  }
}
