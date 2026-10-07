package ai.pearl.wallet;

import android.app.*;
import android.appwidget.*;
import android.content.*;
import android.net.Uri;
import android.widget.*;
import java.math.*;
import org.json.*;

/** A separate public-data widget: no wallet unlock, secret material or signing identity. */
public final class MiningWidget extends AppWidgetProvider {
  static final String REFRESH = "ai.pearl.wallet.MINING_WIDGET_REFRESH",
      PIN = "ai.pearl.wallet.MINING_WIDGET_PIN";
  static final String OPEN = "mining_widget_id";
  static final Object LOCK = new Object();

  static final class Config {
    final String name, address, revision;

    Config(String n, String a) throws Exception {
      this(n, a, java.util.UUID.randomUUID().toString());
    }

    Config(String n, String a, String r) throws Exception {
      name = AddressBook.name(n);
      address = a.isEmpty() ? "" : PearlAddress.normalize(a);
      revision = r;
    }

    String key() {
      return address;
    }
  }

  static SharedPreferences prefs(Context c) {
    return c.getSharedPreferences("mining_widget", 0);
  }

  static int[] ids(Context c) {
    return AppWidgetManager.getInstance(c)
        .getAppWidgetIds(new ComponentName(c, MiningWidget.class));
  }

  static boolean valid(Context c, int id) {
    AppWidgetProviderInfo p = AppWidgetManager.getInstance(c).getAppWidgetInfo(id);
    return p != null && new ComponentName(c, MiningWidget.class).equals(p.provider);
  }

  static Config config(Context c, int id) {
    try {
      JSONObject o = new JSONObject(prefs(c).getString("config_" + id, ""));
      return new Config(o.getString("name"), o.getString("address"), o.getString("revision"));
    } catch (Exception e) {
      return null;
    }
  }

  static void save(Context c, int id, Config cfg) throws Exception {
    synchronized (LOCK) {
      if (!valid(c, id)) throw new IllegalArgumentException("小组件已被移除，请重新添加");
      String raw =
          new JSONObject()
              .put("name", cfg.name)
              .put("address", cfg.address)
              .put("revision", cfg.revision)
              .toString();
      if (!prefs(c)
          .edit()
          .putString("config_" + id, raw)
          .remove("data_" + id)
          .remove("error_" + id)
          .commit()) throw new IllegalStateException("设置保存失败");
    }
    render(c);
    MiningWidgetJob.schedule(c, true);
  }

  static FortuneApi.Snapshot snapshot(Context c, int id, Config cfg) {
    if (cfg == null) return null;
    try {
      return FortuneApi.fromCache(prefs(c).getString("data_" + id, ""), cfg.address);
    } catch (Exception e) {
      return null;
    }
  }

  static void result(Context c, int id, Config expected, FortuneApi.Snapshot data, String error)
      throws Exception {
    synchronized (LOCK) {
      Config current = config(c, id);
      if (!valid(c, id) || current == null || !current.revision.equals(expected.revision)) return;
      SharedPreferences.Editor edit = prefs(c).edit().putBoolean("error_" + id, !error.isEmpty());
      if (data != null && data.address.equals(current.address))
        edit.putString("data_" + id, data.cache());
      if (!edit.commit()) throw new IllegalStateException("组件缓存保存失败");
    }
    render(c);
  }

  static String time(long at, long now, String noun) {
    if (at <= 0) return noun + "待更新";
    return noun
        + (now < at || now - at > 300 ? "缓存 " : " ")
        + new java.text.SimpleDateFormat("MM/dd HH:mm", java.util.Locale.CHINA)
            .format(new java.util.Date(at * 1000));
  }

  static void render(Context c) {
    for (int id : ids(c)) {
      Config cfg = config(c, id);
      FortuneApi.Snapshot data = snapshot(c, id, cfg);
      long now = System.currentTimeMillis() / 1000;
      RemoteViews v = new RemoteViews(c.getPackageName(), R.layout.mining_widget);
      v.setTextViewText(R.id.mw_name, cfg == null ? "选择矿工或矿池" : cfg.name);
      v.setTextViewText(
          R.id.mw_rate_label,
          cfg != null && !cfg.address.isEmpty()
              ? "PEARLFORTUNE · 我的 1 小时估算"
              : "PEARLFORTUNE · 矿池 1 小时估算");
      SharedPreferences price = c.getSharedPreferences("price_widget", 0);
      String quote = "— USDT", change = "24h —";
      try {
        BigDecimal p = new BigDecimal(price.getString("usdt", "0")),
            d = new BigDecimal(price.getString("change", "0"));
        if (p.signum() > 0) {
          quote =
              p.setScale(4, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString() + " USDT";
          change = "24h " + (d.signum() > 0 ? "+" : "") + d.setScale(2, RoundingMode.HALF_UP) + "%";
        }
      } catch (Exception ignored) {
      }
      v.setTextViewText(R.id.mw_price, quote);
      v.setTextViewText(R.id.mw_change, change);
      try {
        v.setTextColor(
            R.id.mw_change,
            new BigDecimal(price.getString("change", "0")).signum() < 0
                ? android.graphics.Color.rgb(255, 151, 156)
                : android.graphics.Color.rgb(143, 233, 197));
      } catch (Exception ignored) {
      }
      v.setTextViewText(
          R.id.mw_price_time,
          (prefs(c).getBoolean("price_error", false) ? "刷新失败 · " : "")
              + time(price.getLong("at", 0), now, "行情"));
      v.setTextViewText(R.id.mw_rate, data == null ? "—" : data.window(1).display());
      v.setTextViewText(
          R.id.mw_windows,
          data == null
              ? "8h —    ·    24h —"
              : "8h " + data.window(8).display() + "  ·  24h " + data.window(24).display());
      boolean cached =
          data != null
              && (now < data.at
                  || now - data.at > 300
                  || prefs(c).getBoolean("error_" + id, false));
      String state = data == null ? "等待公开矿池数据" : data.connections();
      if (data != null && !data.address.isEmpty()) {
        if (data.online != null)
          state =
              time(data.connectionsAt, now, "连接")
                  + " · "
                  + (data.online ? data.workers.size() + " 个连接" : "无在线连接");
        if (data.online != null
            && data.online
            && (now < data.connectionsAt || now - data.connectionsAt > 300))
          state = "连接缓存 · " + data.workers.size() + " 个连接";
        else if (data.online != null
            && !data.online
            && (now < data.connectionsAt || now - data.connectionsAt > 300))
          state = "连接缓存 · 上次无在线连接";
        long stale = data.workers.stream().filter(w -> w.stale || now - w.lastStats > 300).count();
        if (stale > 0) state += " · " + stale + " 个自报已旧";
      }
      v.setTextViewText(R.id.mw_status, (cached ? "缓存 · " : "") + state);
      v.setTextViewText(
          R.id.mw_mining_time,
          (prefs(c).getBoolean("error_" + id, false) ? "刷新失败 · " : "")
              + time(data == null ? 0 : data.at, now, "算力"));
      Intent open =
          new Intent(c, MainActivity.class)
              .setData(Uri.parse("pearl-widget://open/" + id))
              .putExtra(OPEN, id)
              .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
      Intent setup =
          new Intent(c, MiningWidgetConfigActivity.class)
              .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id);
      v.setOnClickPendingIntent(
          R.id.mw_body,
          PendingIntent.getActivity(
              c,
              id,
              cfg == null ? setup : open,
              PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
      v.setOnClickPendingIntent(
          R.id.mw_config,
          PendingIntent.getActivity(
              c, id, setup, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
      v.setOnClickPendingIntent(
          R.id.mw_refresh,
          PendingIntent.getBroadcast(
              c,
              id,
              new Intent(c, MiningWidget.class).setAction(REFRESH),
              PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
      AppWidgetManager.getInstance(c).updateAppWidget(id, v);
    }
  }

  @Override
  public void onUpdate(Context c, AppWidgetManager m, int[] widgets) {
    render(c);
    MiningWidgetJob.schedule(c, true);
  }

  @Override
  public void onDeleted(Context c, int[] widgets) {
    synchronized (LOCK) {
      SharedPreferences.Editor e = prefs(c).edit();
      for (int id : widgets) e.remove("config_" + id).remove("data_" + id).remove("error_" + id);
      e.apply();
    }
    MiningWidgetJob.schedule(c, false);
  }

  @Override
  public void onDisabled(Context c) {
    MiningWidgetJob.cancel(c);
  }

  @Override
  public void onRestored(Context c, int[] old, int[] next) {
    // Public configuration survives a launcher host restore; wallet backups are disabled.
    for (int i = 0; i < Math.min(old.length, next.length); i++) {
      Config cfg = config(c, old[i]);
      if (cfg != null)
        try {
          save(c, next[i], new Config(cfg.name, cfg.address));
        } catch (Exception ignored) {
        }
    }
    onDeleted(c, old);
  }

  @Override
  public void onReceive(Context c, Intent i) {
    super.onReceive(c, i);
    if (REFRESH.equals(i.getAction())) MiningWidgetJob.schedule(c, true);
    if (PIN.equals(i.getAction())) {
      int id =
          i.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID);
      String token = i.getData() == null ? "" : i.getData().getLastPathSegment();
      try {
        String raw = prefs(c).getString("pin_" + token, "");
        JSONObject cfg = new JSONObject(raw);
        if (System.currentTimeMillis() - cfg.getLong("created") > 3600000 || !valid(c, id)) return;
        if (config(c, id) == null)
          save(c, id, new Config(cfg.getString("name"), cfg.getString("address")));
        prefs(c).edit().remove("pin_" + token).apply();
      } catch (Exception ignored) {
      }
    }
  }

  static void pin(Activity a, Config cfg) {
    AppWidgetManager m = AppWidgetManager.getInstance(a);
    if (!m.isRequestPinAppWidgetSupported()) {
      Toast.makeText(a, "长按桌面，选择小组件 → 掌珠钱包 → 算力与价格", Toast.LENGTH_LONG).show();
      return;
    }
    try {
      String token = java.util.UUID.randomUUID().toString();
      // Clear abandoned requests before creating another public-only pin callback.
      SharedPreferences.Editor clean = prefs(a).edit();
      long now = System.currentTimeMillis();
      for (java.util.Map.Entry<String, ?> e : prefs(a).getAll().entrySet())
        if (e.getKey().startsWith("pin_")) {
          try {
            if (now - new JSONObject(String.valueOf(e.getValue())).getLong("created") > 3600000)
              clean.remove(e.getKey());
          } catch (Exception ignored) {
            clean.remove(e.getKey());
          }
        }
      clean.apply();
      boolean saved =
          prefs(a)
              .edit()
              .putString(
                  "pin_" + token,
                  new JSONObject()
                      .put("name", cfg.name)
                      .put("address", cfg.address)
                      .put("created", System.currentTimeMillis())
                      .toString())
              .commit();
      if (!saved) throw new IllegalStateException("组件设置保存失败");
      Intent callback =
          new Intent(a, MiningWidget.class)
              .setAction(PIN)
              .setData(Uri.parse("pearl-widget://pin/" + token));
      // The launcher fills in the newly allocated ID. Explicit, unique, public-only callback.
      PendingIntent pi =
          PendingIntent.getBroadcast(
              a, 0, callback, PendingIntent.FLAG_MUTABLE | PendingIntent.FLAG_ONE_SHOT);
      if (!m.requestPinAppWidget(new ComponentName(a, MiningWidget.class), null, pi))
        prefs(a).edit().remove("pin_" + token).apply();
    } catch (Exception e) {
      Toast.makeText(a, "添加失败，请从桌面的小组件列表添加", Toast.LENGTH_LONG).show();
    }
  }
}
