package ai.pearl.wallet;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;

/** One-shot USDT thresholds, claimed under the same lock by the foreground and background job. */
final class PriceAlerts {
  static final class Alert {
    final String id;
    final String kind;
    final BigDecimal baseline;
    final BigDecimal target;
    final boolean above, enabled;
    final long firedAt;

    Alert(String id, BigDecimal target, boolean above, boolean enabled, long firedAt) {
      this(id, target, above, enabled, firedAt, "target", null);
    }

    Alert(
        String id,
        BigDecimal target,
        boolean above,
        boolean enabled,
        long firedAt,
        String kind,
        BigDecimal baseline) {
      this.id = id;
      this.target = target;
      this.above = above;
      this.enabled = enabled;
      this.firedAt = firedAt;
      this.kind = kind;
      this.baseline = baseline;
    }

    String description() {
      if (!kind.equals("target"))
        return (above ? "上涨 " : "下跌 ")
            + target.toPlainString()
            + "% · 基准 "
            + baseline.toPlainString()
            + " USDT";
      return (above ? "达到 / 高于 " : "达到 / 低于 ") + target.toPlainString() + " USDT";
    }
  }

  private final PublicStore.Storage store;

  PriceAlerts(PublicStore.Storage store) {
    this.store = store;
  }

  List<Alert> list() throws Exception {
    synchronized (PublicStore.LOCK) {
      return read();
    }
  }

  private List<Alert> read() throws Exception {
    JSONArray json = new JSONArray(store.read("alerts"));
    if (json.length() > 20) throw new IllegalArgumentException("提醒数据过大");
    List<Alert> result = new ArrayList<>();
    for (int i = 0; i < json.length(); i++) {
      JSONObject a = json.getJSONObject(i);
      String kind = a.optString("kind", "target");
      BigDecimal baseline =
          a.isNull("baseline") ? null : baseline(new BigDecimal(a.getString("baseline")));
      if (!kind.equals("target") && !kind.equals("percent")
          || kind.equals("percent") && baseline == null)
        throw new IllegalArgumentException("提醒数据无效");
      result.add(
          new Alert(
              a.getString("id"),
              target(a.getString("target")),
              a.getBoolean("above"),
              a.getBoolean("enabled"),
              a.optLong("firedAt"),
              kind,
              baseline));
    }
    return result;
  }

  private static BigDecimal baseline(BigDecimal n) {
    if (n == null
        || n.signum() <= 0
        || n.precision() > 80
        || Math.abs((long) n.scale()) > 80
        || n.compareTo(new BigDecimal("1000000000000")) > 0)
      throw new IllegalArgumentException("基准价格无效");
    return n.stripTrailingZeros();
  }

  static BigDecimal target(String value) {
    if (value == null || !value.matches("[0-9]{1,12}(\\.[0-9]{1,8})?"))
      throw new IllegalArgumentException("价格请输入正数，最多 8 位小数");
    BigDecimal target = new BigDecimal(value);
    if (target.signum() <= 0 || target.compareTo(new BigDecimal("1000000000000")) >= 0)
      throw new IllegalArgumentException("提醒价格超出范围");
    return target.stripTrailingZeros();
  }

  Alert add(String value, boolean above) throws Exception {
    return save(null, value, above, "target", null);
  }

  Alert save(String id, String value, boolean above, String kind, BigDecimal baseline)
      throws Exception {
    BigDecimal target = target(value);
    if (!kind.equals("target") && !kind.equals("percent"))
      throw new IllegalArgumentException("提醒类型无效");
    if (kind.equals("percent")
        && (baseline == null
            || baseline.signum() <= 0
            || target.compareTo(new BigDecimal("100")) >= 0))
      throw new IllegalArgumentException("涨跌幅需小于 100%，并先获取新报价");
    if (kind.equals("percent")) baseline = baseline(baseline);
    synchronized (PublicStore.LOCK) {
      List<Alert> alerts = read();
      if (id == null && alerts.size() >= 20)
        throw new IllegalArgumentException("最多保存 20 条提醒，请先删除旧提醒");
      if (id != null && !alerts.removeIf(a -> a.id.equals(id)))
        throw new IllegalArgumentException("提醒已删除");
      Alert alert =
          new Alert(
              id == null ? UUID.randomUUID().toString() : id,
              target,
              above,
              true,
              0,
              kind,
              kind.equals("percent") ? baseline : null);
      alerts.add(alert);
      write(alerts);
      return alert;
    }
  }

  void remove(String id) throws Exception {
    synchronized (PublicStore.LOCK) {
      List<Alert> alerts = read();
      alerts.removeIf(a -> a.id.equals(id));
      write(alerts);
    }
  }

  void rearm(String id) throws Exception {
    synchronized (PublicStore.LOCK) {
      List<Alert> alerts = read();
      boolean found = false;
      for (int i = 0; i < alerts.size(); i++) {
        Alert a = alerts.get(i);
        if (a.id.equals(id)) {
          alerts.set(i, new Alert(a.id, a.target, a.above, true, 0, a.kind, a.baseline));
          found = true;
          break;
        }
      }
      if (!found) throw new IllegalArgumentException("提醒已删除，请重新打开提醒列表");
      write(alerts);
    }
  }

  boolean active() throws Exception {
    for (Alert a : list()) if (a.enabled) return true;
    return false;
  }

  List<Alert> claim(BigDecimal usdt, long receivedAt, long now, boolean notificationsEnabled)
      throws Exception {
    List<Alert> fired = new ArrayList<>();
    if (!notificationsEnabled
        || usdt == null
        || usdt.signum() <= 0
        || now - receivedAt < 0
        || now - receivedAt > 120) return fired;
    synchronized (PublicStore.LOCK) {
      List<Alert> alerts = read(), updated = new ArrayList<>();
      for (Alert a : alerts) {
        BigDecimal threshold =
            a.kind.equals("target")
                ? a.target
                : a.baseline.multiply(
                    BigDecimal.ONE.add((a.above ? a.target : a.target.negate()).movePointLeft(2)));
        boolean match =
            a.enabled
                && (a.above ? usdt.compareTo(threshold) >= 0 : usdt.compareTo(threshold) <= 0);
        if (match) {
          Alert done = new Alert(a.id, a.target, a.above, false, now, a.kind, a.baseline);
          fired.add(done);
          updated.add(done);
        } else updated.add(a);
      }
      if (!fired.isEmpty())
        write(updated); // Persist the claim before any notification; concurrent checks cannot
      // duplicate it.
    }
    return fired;
  }

  private void write(List<Alert> alerts) throws Exception {
    JSONArray json = new JSONArray();
    for (Alert a : alerts)
      json.put(
          new JSONObject()
              .put("id", a.id)
              .put("target", a.target.toPlainString())
              .put("above", a.above)
              .put("enabled", a.enabled)
              .put("firedAt", a.firedAt)
              .put("kind", a.kind)
              .put("baseline", a.baseline == null ? JSONObject.NULL : a.baseline.toPlainString()));
    store.write("alerts", json.toString());
  }
}
