package ai.pearl.wallet;

import android.app.job.JobParameters;
import android.app.job.JobService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** OS-scheduled read-only checks; no wallet file, keys, account addresses or foreground service. */
public final class PriceAlertJob extends JobService {
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final Object lock=new Object();
    private int generation;
    @Override public boolean onStartJob(JobParameters params){
        final int token;
        synchronized(lock){token=++generation;}
        worker.execute(()->{
            try {
                MarketQuote quote=new PearlApi().marketQuote();
                synchronized(lock){if(token!=generation)return;AlertNotifications.check(this,quote.usdt,quote.receivedAt);}
            }catch(Exception ignored){/* Failed public quotes never trigger alerts. The next periodic job retries. */}
            new android.os.Handler(getMainLooper()).post(()->{synchronized(lock){if(token==generation)jobFinished(params,false);}});
        });return true;
    }
    @Override public boolean onStopJob(JobParameters params){synchronized(lock){generation++;}return true;}
    @Override public void onDestroy(){synchronized(lock){generation++;}worker.shutdownNow();super.onDestroy();}
}
