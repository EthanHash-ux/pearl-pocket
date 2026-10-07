package ai.pearl.wallet;

import java.math.*;
import java.time.Instant;
import java.util.*;
import org.json.*;

/** Public PearlFortune monitoring. No keys, signing, payouts or miner execution. */
final class FortuneApi {
  static final String ORIGIN = "https://pearlfortune.org";
  private final PearlApi.Transport transport;

  FortuneApi() {
    this(PearlApi::httpsGet);
  }

  FortuneApi(PearlApi.Transport t) {
    transport = t;
  }

  static final class Window {
    final int hours;
    final BigDecimal rate;
    final String unit;
    final BigInteger accepted, rejected;
    final boolean complete;

    Window(int h, BigDecimal r, String u, BigInteger a, BigInteger b, boolean c) {
      hours = h;
      rate = r;
      unit = u;
      accepted = a;
      rejected = b;
      complete = c;
    }

    String display() {
      return rate == null ? "—" : formatRate(rate, unit);
    }

    String acceptance() {
      if (!complete || accepted == null || rejected == null || accepted.add(rejected).signum() == 0)
        return "—";
      return new BigDecimal(accepted)
              .multiply(new BigDecimal("100"))
              .divide(new BigDecimal(accepted.add(rejected)), 2, RoundingMode.HALF_UP)
          + "%";
    }
  }

  static final class Worker {
    final String name;
    final BigDecimal reported;
    final Long gpus;
    final boolean stale;
    final long lastStats;

    Worker(String n, BigDecimal r, Long g, boolean s, long at) {
      name = n;
      reported = r;
      gpus = g;
      stale = s;
      lastStats = at;
    }
  }

  static final class Snapshot {
    final String address;
    final long at, connectionsAt;
    final List<Window> windows;
    final List<Worker> workers;
    final Boolean online;
    final String connectionError;

    Snapshot(
        String a,
        long t,
        List<Window> w,
        List<Worker> workers,
        Boolean online,
        long ct,
        String error) {
      address = a;
      at = t;
      windows = Collections.unmodifiableList(w);
      this.workers = Collections.unmodifiableList(workers);
      this.online = online;
      connectionsAt = ct;
      connectionError = error;
    }

    Window window(int h) {
      for (Window w : windows) if (w.hours == h) return w;
      return new Window(h, null, "H/s", null, null, false);
    }

    String connections() {
      return address.isEmpty()
          ? "矿池总览"
          : online == null ? "连接状态未知" : online ? "矿池报告 " + workers.size() + " 个在线连接" : "矿池报告无在线连接";
    }

    // Persist sanitized public metrics only; do not cache remote IPs or entire provider responses.
    String cache() throws Exception {
      JSONObject o =
          new JSONObject()
              .put("address", address)
              .put("at", at)
              .put("connectionsAt", connectionsAt)
              .put("online", online == null ? JSONObject.NULL : online)
              .put("connectionError", connectionError);
      JSONArray ws = new JSONArray(), miners = new JSONArray();
      for (Window w : windows)
        ws.put(
            new JSONObject()
                .put("hours", w.hours)
                .put("rate", w.rate == null ? JSONObject.NULL : w.rate.toPlainString())
                .put("unit", w.unit)
                .put("accepted", w.accepted == null ? JSONObject.NULL : w.accepted.toString())
                .put("rejected", w.rejected == null ? JSONObject.NULL : w.rejected.toString())
                .put("complete", w.complete));
      for (Worker w : workers)
        miners.put(
            new JSONObject()
                .put("name", w.name)
                .put("reported", w.reported == null ? JSONObject.NULL : w.reported.toPlainString())
                .put("gpus", w.gpus == null ? JSONObject.NULL : w.gpus)
                .put("stale", w.stale)
                .put("lastStats", w.lastStats));
      return o.put("windows", ws).put("workers", miners).toString();
    }
  }

  Snapshot fetch(String input) throws Exception {
    String address = input.isEmpty() ? "" : PearlAddress.normalize(input);
    validateConfig(transport.get(ORIGIN + "/api/v1/config"));
    long now = System.currentTimeMillis() / 1000;
    if (address.isEmpty())
      return parsePool(transport.get(ORIGIN + "/api/v1/summary?hours=24"), now);
    Snapshot s =
        parseMiner(transport.get(ORIGIN + "/api/v1/miners/" + address + "?hours=24"), address, now);
    try {
      return withConnections(
          s,
          transport.get(ORIGIN + "/api/v1/miners/" + address + "/connections"),
          System.currentTimeMillis() / 1000);
    } catch (Exception e) {
      return new Snapshot(
          s.address, s.at, s.windows, new ArrayList<>(), null, 0, "连接状态读取失败，请刷新或查看官网");
    }
  }

  static void validateConfig(String raw) throws Exception {
    JSONObject d = data(raw);
    if (!"pearl".equals(d.getString("chain"))
        || !"PRL".equals(d.getString("coin_symbol"))
        || d.getLong("atomic_units") != 100000000)
      throw new IllegalArgumentException("矿池返回的币种不是 Pearl");
  }

  static Snapshot parsePool(String raw, long now) throws Exception {
    JSONObject d = data(raw);
    chain(d, "chain");
    long at = fresh(d.getLong("generated_at"), now);
    return new Snapshot(
        "",
        at,
        windows(d.getJSONObject("pool_stats").getJSONArray("rolling_stats"), now),
        new ArrayList<>(),
        null,
        0,
        "");
  }

  static Snapshot parseMiner(String raw, String input, long now) throws Exception {
    String address = PearlAddress.normalize(input);
    JSONObject d = data(raw);
    chain(d, "chain");
    bind(d.getString("miner_address"), address);
    JSONObject hourly = d.getJSONObject("hourly_shares");
    chain(hourly, "chain_name");
    bind(hourly.getString("miner_address"), address);
    return new Snapshot(
        address,
        fresh(d.getLong("generated_at"), now),
        windows(hourly.getJSONArray("rolling_hashrates"), now),
        new ArrayList<>(),
        null,
        0,
        "");
  }

  static Snapshot withConnections(Snapshot s, String raw, long now) throws Exception {
    JSONObject d = data(raw);
    bind(d.getString("address"), s.address);
    if (!d.getBoolean("configured"))
      return new Snapshot(s.address, s.at, s.windows, new ArrayList<>(), null, now, "矿池未提供连接数据");
    boolean online = d.getBoolean("online");
    JSONArray a = d.getJSONArray("workers");
    if (a.length() > 500 || online != (a.length() > 0))
      throw new IllegalArgumentException("矿池连接统计不一致");
    List<Worker> workers = new ArrayList<>();
    for (int i = 0; i < a.length(); i++) {
      JSONObject w = a.getJSONObject(i);
      Long g = w.isNull("reported_gpus") ? null : gpuCount(w.get("reported_gpus"));
      if (g != null && g > 100000) throw new IllegalArgumentException("GPU 数量无效");
      long t = iso(w.optString("last_stats_at", ""));
      if (t > now + 60) throw new IllegalArgumentException("矿机报告时间无效");
      workers.add(
          new Worker(
              label(w.optString("worker", "未命名 worker")),
              nullableNumber(w, "reported_hashrate"),
              g,
              w.optBoolean("stale", true) || t == 0 || now - t > 300,
              t));
    }
    return new Snapshot(s.address, s.at, s.windows, workers, online, now, "");
  }

  private static List<Window> windows(JSONArray a, long now) throws Exception {
    if (a.length() > 12) throw new IllegalArgumentException("算力窗口过多");
    List<Window> result = new ArrayList<>();
    Set<Integer> seen = new HashSet<>();
    for (int i = 0; i < a.length(); i++) {
      JSONObject o = a.getJSONObject(i);
      int h = o.getInt("hours");
      if (h != 1 && h != 8 && h != 24) continue;
      if (!seen.add(h)) throw new IllegalArgumentException("算力窗口重复");
      String unit = unit(o.getString("hashrate_str"));
      BigDecimal rate = null;
      BigInteger accepted = null, rejected = null;
      boolean complete = false;
      JSONObject stats = o.optJSONObject("share_statistics");
      if (stats != null) {
        long from = stats.getLong("from"),
            to = stats.getLong("to"),
            observed = stats.getLong("observed_ms");
        if (to <= from
            || to - from > h * 3600L + 60
            || observed < 0
            || observed > (to - from) * 1000L + 1000
            || to > now + 60
            || now - to > 900) throw new IllegalArgumentException("矿池算力窗口时间无效");
        accepted = integer(stats.get("accepted_count"));
        rejected = integer(stats.get("rejected_count"));
        complete =
            stats.optBoolean("complete", false)
                && integer(stats.get("dropped_events")).signum() == 0
                && to - from >= h * 3600L - 1
                && observed >= h * 3600000L - 1000;
        // Missing effective rate stays unknown. Partial observations never become a full-window
        // zero.
        if (complete) rate = nullableNumber(stats, "effective_hashrate");
      }
      result.add(new Window(h, rate, unit, accepted, rejected, complete));
    }
    return result;
  }

  static Snapshot fromCache(String raw, String input) throws Exception {
    String address = input.isEmpty() ? "" : PearlAddress.normalize(input);
    JSONObject o = new JSONObject(raw);
    if (!address.equals(o.getString("address"))) throw new IllegalArgumentException("缓存地址不匹配");
    JSONArray ws = o.getJSONArray("windows"), ms = o.getJSONArray("workers");
    if (ws.length() > 3 || ms.length() > 500) throw new IllegalArgumentException("缓存过大");
    List<Window> windows = new ArrayList<>();
    List<Worker> workers = new ArrayList<>();
    Set<Integer> seen = new HashSet<>();
    for (int i = 0; i < ws.length(); i++) {
      JSONObject w = ws.getJSONObject(i);
      int h = w.getInt("hours");
      if ((h != 1 && h != 8 && h != 24) || !seen.add(h))
        throw new IllegalArgumentException("缓存窗口无效");
      String u = w.getString("unit");
      if (!u.equals("MAC/s") && !u.equals("H/s")) throw new IllegalArgumentException("缓存单位无效");
      windows.add(
          new Window(
              h,
              nullableNumber(w, "rate"),
              u,
              w.isNull("accepted") ? null : integer(w.get("accepted")),
              w.isNull("rejected") ? null : integer(w.get("rejected")),
              w.getBoolean("complete")));
    }
    for (int i = 0; i < ms.length(); i++) {
      JSONObject w = ms.getJSONObject(i);
      workers.add(
          new Worker(
              label(w.getString("name")),
              nullableNumber(w, "reported"),
              w.isNull("gpus") ? null : gpuCount(w.get("gpus")),
              w.getBoolean("stale"),
              w.getLong("lastStats")));
    }
    return new Snapshot(
        address,
        o.getLong("at"),
        windows,
        workers,
        o.isNull("online") ? null : o.getBoolean("online"),
        o.getLong("connectionsAt"),
        label(o.optString("connectionError", "")));
  }

  static String website(String address) throws Exception {
    return ORIGIN + "/#miner" + (address.isEmpty() ? "" : "=" + PearlAddress.normalize(address));
  }

  static String mask(String address) {
    return address.substring(0, 10) + "..." + address.substring(address.length() - 8);
  }

  private static void bind(String returned, String requested) {
    if (!returned.equals(requested) && !returned.equals(mask(requested)))
      throw new IllegalArgumentException("矿池返回了其他地址的数据");
  }

  private static void chain(JSONObject d, String key) throws Exception {
    if (!"pearl".equals(d.getString(key))) throw new IllegalArgumentException("矿池数据链不匹配");
  }

  private static JSONObject data(String raw) throws Exception {
    return new JSONObject(raw).getJSONObject("data");
  }

  private static long fresh(long at, long now) {
    if (at <= 0 || at > now + 60 || now - at > 600)
      throw new IllegalArgumentException("矿池数据已过期，请稍后刷新");
    return at;
  }

  private static BigDecimal nullableNumber(JSONObject o, String key) throws Exception {
    if (o.isNull(key)) return null;
    BigDecimal n = new BigDecimal(o.get(key).toString());
    if (n.signum() < 0
        || n.precision() > 80
        || n.scale() < -40
        || n.scale() > 40
        || n.compareTo(new BigDecimal("1e40")) > 0) throw new IllegalArgumentException("矿池数值无效");
    return n;
  }

  private static long gpuCount(Object value) {
    BigInteger count = integer(value);
    if (count.compareTo(BigInteger.valueOf(100000)) > 0)
      throw new IllegalArgumentException("GPU 数量无效");
    return count.longValue();
  }

  private static BigInteger integer(Object value) {
    String s = value.toString();
    if (!s.matches("[0-9]{1,80}")) throw new IllegalArgumentException("矿池计数无效");
    return new BigInteger(s);
  }

  private static String unit(String s) {
    if (s.matches("[0-9]+(?:\\.[0-9]+)? (?:[kMGTPE]?H/s)")) return "H/s";
    if (s.matches(
        "[0-9]+(?:\\.[0-9]+)?"
            + " (?:MAC/s|KiloMAC/s|MegaMAC/s|GigaMAC/s|TeraMAC/s|PetaMAC/s|ExaMAC/s)"))
      return "MAC/s";
    throw new IllegalArgumentException("矿池算力单位未知");
  }

  static String formatRate(BigDecimal rate, String unit) {
    String[] prefix = {"", "k", "M", "G", "T", "P", "E"};
    int i = 0;
    while (rate.compareTo(new BigDecimal("1000")) >= 0 && i < 6) {
      rate = rate.movePointLeft(3);
      i++;
    }
    return rate.setScale(2, RoundingMode.HALF_UP).toPlainString() + " " + prefix[i] + unit;
  }

  private static String label(String s) {
    if (s.length() > 160 || s.chars().anyMatch(Character::isISOControl))
      throw new IllegalArgumentException("矿机名称无效");
    return s;
  }

  private static long iso(String s) {
    try {
      return s.isEmpty() || s.startsWith("0001-") ? 0 : Instant.parse(s).getEpochSecond();
    } catch (Exception e) {
      return 0;
    }
  }
}
