package ai.pearl.wallet;

import java.math.*;
import java.util.*;
import org.json.*;

/**
 * HeroMiners address schema verified against its public stats_address API. Raw share-rates are
 * never labelled H/s.
 */
final class MinerAccount {
  static final class Worker {
    final String name;
    final BigDecimal scoreRate;
    final long lastShare;

    Worker(String n, BigDecimal r, long t) {
      name = n;
      scoreRate = r;
      lastShare = t;
    }
  }

  static final class Payout {
    final String id;
    final BigInteger grains;
    final long time;

    Payout(String i, BigInteger g, long t) {
      id = i;
      grains = g;
      time = t;
    }
  }

  final boolean found;
  final BigInteger balance, paid, pending;
  final BigDecimal rate, dayRate;
  final long lastShare, receivedAt;
  final List<Worker> workers;
  final List<Payout> payouts;

  MinerAccount(
      boolean found,
      BigInteger b,
      BigInteger paid,
      BigInteger pending,
      BigDecimal rate,
      BigDecimal day,
      long last,
      long at,
      List<Worker> w,
      List<Payout> p) {
    this.found = found;
    balance = b;
    this.paid = paid;
    this.pending = pending;
    this.rate = rate;
    dayRate = day;
    lastShare = last;
    receivedAt = at;
    workers = w;
    payouts = p;
  }

  static BigInteger grains(Object value) {
    String s = value.toString();
    if (!s.matches("[0-9]{1,22}")) throw new IllegalArgumentException("矿池金额无效");
    BigInteger n = new BigInteger(s);
    if (n.compareTo(PearlAmount.MAX_GRAINS) > 0) throw new IllegalArgumentException("矿池金额超出范围");
    return n;
  }

  static BigDecimal rate(JSONObject j, String key) throws Exception {
    BigDecimal n = new BigDecimal(j.get(key).toString());
    if (n.signum() < 0 || n.precision() > 80 || n.compareTo(new BigDecimal("1e40")) > 0)
      throw new IllegalArgumentException("矿工速率无效");
    return n;
  }

  static long stamp(Object v) {
    long n = Long.parseLong(v.toString());
    if (n < 0 || n > 4102444800L) throw new IllegalArgumentException("矿工时间无效");
    return n;
  }

  static MinerAccount parse(String input, long now) throws Exception {
    JSONObject j = new JSONObject(input);
    if (j.has("error")) {
      if (j.getString("error").equals("Not found"))
        return new MinerAccount(
            false,
            null,
            null,
            null,
            null,
            null,
            0,
            now,
            Collections.emptyList(),
            Collections.emptyList());
      throw new IllegalArgumentException("矿池暂未返回该地址统计");
    }
    JSONObject s = j.getJSONObject("stats");
    List<Worker> workers = new ArrayList<>();
    JSONArray w = j.getJSONArray("workers");
    if (w.length() > 500) throw new IllegalArgumentException("工作器数据过大");
    for (int i = 0; i < w.length(); i++) {
      JSONObject v = w.getJSONObject(i);
      String name = v.getString("name");
      if (name.length() > 160 || name.codePoints().anyMatch(Character::isISOControl))
        throw new IllegalArgumentException("工作器名称无效");
      workers.add(new Worker(name, rate(v, "hashrate"), stamp(v.get("lastShare"))));
    }
    JSONArray list = j.getJSONArray("payments");
    if (list.length() > 200 || list.length() % 2 != 0)
      throw new IllegalArgumentException("矿池付款数据无效");
    List<Payout> payments = new ArrayList<>();
    for (int i = 0; i < list.length(); i += 2) {
      String[] parts = list.getString(i).split(":");
      if (parts.length != 3) throw new IllegalArgumentException("矿池付款格式无效");
      TransactionLedger.id(parts[0]);
      payments.add(new Payout(parts[0], grains(parts[1]), stamp(list.get(i + 1))));
    }
    BigInteger pending = BigInteger.ZERO;
    JSONArray pendingRows = j.getJSONArray("unconfirmed");
    if (pendingRows.length() > 500) throw new IllegalArgumentException("待成熟奖励数据过大");
    for (int i = 0; i < pendingRows.length(); i++)
      pending = pending.add(grains(pendingRows.getJSONObject(i).get("reward")));
    grains(pending);
    return new MinerAccount(
        true,
        grains(s.get("balance")),
        grains(s.get("paid")),
        pending,
        rate(s, "hashrate"),
        rate(s, "hashrate_24h"),
        stamp(s.get("lastShare")),
        now,
        workers,
        payments);
  }

  static MinerAccount fetch(String input) throws Exception {
    String a = PearlAddress.normalize(input);
    return parse(
        MiningApi.getPool("https://pearl.herominers.com/api/stats_address?address=" + a),
        System.currentTimeMillis() / 1000);
  }

  static String activity(long last, long now) {
    return last == 0
        ? "尚无提交"
        : now - last < 0 ? "时间异常" : now - last <= 900 ? "15 分钟内有提交" : "超过 15 分钟未提交";
  }
}
