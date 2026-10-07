package ai.pearl.wallet;

import android.app.*;
import android.appwidget.*;
import android.content.*;
import android.widget.*;
import java.math.*;

/** Public market data only; Android may delay scheduled widget updates. */
public final class PriceWidget extends AppWidgetProvider {
  private static final String REFRESH = "ai.pearl.wallet.WIDGET_REFRESH";
  private static long written;
  private static final java.util.concurrent.atomic.AtomicBoolean refreshing =
      new java.util.concurrent.atomic.AtomicBoolean();

  static void cache(Context c, BigDecimal price, BigDecimal change, long at, boolean force) {
    // A USD/CNY-only fallback must neither crash nor overwrite a USDT quote.
    if (price == null || change == null || price.signum() <= 0 || at <= 0) return;
    synchronized (PriceWidget.class) {
      if (!force && at - written < 60) return;
      written = at;
      c.getSharedPreferences("price_widget", 0)
          .edit()
          .putString("usdt", price.toPlainString())
          .putString("change", change.toPlainString())
          .putLong("at", at)
          .apply();
      render(c);
    }
  }

  static void render(Context c) {
    AppWidgetManager manager = AppWidgetManager.getInstance(c);
    for (int id : manager.getAppWidgetIds(new ComponentName(c, PriceWidget.class))) {
      RemoteViews v = new RemoteViews(c.getPackageName(), R.layout.price_widget);
      android.content.SharedPreferences p = c.getSharedPreferences("price_widget", 0);
      long at = p.getLong("at", 0), now = System.currentTimeMillis() / 1000;
      String price = "— USDT", change = "暂无报价";
      try {
        BigDecimal n = new BigDecimal(p.getString("usdt", "0")),
            d = new BigDecimal(p.getString("change", "0"));
        if (n.signum() > 0) {
          price = n.stripTrailingZeros().toPlainString() + " USDT";
          change = "24h " + (d.signum() > 0 ? "+" : "") + d.setScale(2, RoundingMode.HALF_UP) + "%";
        }
      } catch (Exception ignored) {
      }
      v.setTextViewText(R.id.widget_price, price);
      v.setTextViewText(R.id.widget_change, change);
      v.setTextViewText(
          R.id.widget_time,
          at == 0
              ? "点刷新获取 BigONE 行情"
              : (now - at > 300 || now < at ? "缓存 · " : "接收 · ")
                  + new java.text.SimpleDateFormat("MM/dd HH:mm", java.util.Locale.CHINA)
                      .format(new java.util.Date(at * 1000)));
      Intent open = new Intent(c, MainActivity.class).putExtra("show_market", true);
      v.setOnClickPendingIntent(
          R.id.widget_body,
          PendingIntent.getActivity(
              c, 4, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
      Intent refresh = new Intent(c, PriceWidget.class).setAction(REFRESH);
      v.setOnClickPendingIntent(
          R.id.widget_refresh,
          PendingIntent.getBroadcast(
              c, 5, refresh, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
      manager.updateAppWidget(id, v);
    }
  }

  public void onUpdate(Context c, AppWidgetManager m, int[] ids) {
    render(c);
    refresh(c);
  }

  public void onReceive(Context c, Intent intent) {
    super.onReceive(c, intent);
    if (REFRESH.equals(intent.getAction())) refresh(c);
  }

  private void refresh(Context c) {
    if (!refreshing.compareAndSet(false, true)) return;
    PendingResult pending = goAsync();
    Context app = c.getApplicationContext();
    new Thread(
            () -> {
              try {
                MarketQuote q = new PearlApi().marketQuote();
                cache(app, q.usdt, q.change24h, q.receivedAt, true);
              } catch (Exception ignored) {
                render(app);
              } finally {
                refreshing.set(false);
                pending.finish();
              }
            },
            "Pearl-public-widget")
        .start();
  }

  static void pin(Activity a) {
    AppWidgetManager m = AppWidgetManager.getInstance(a);
    if (m.isRequestPinAppWidgetSupported())
      m.requestPinAppWidget(new ComponentName(a, PriceWidget.class), null, null);
    else android.widget.Toast.makeText(a, "长按桌面，选择小组件 → 掌珠钱包", Toast.LENGTH_LONG).show();
  }
}
