package ai.pearl.wallet;

import android.content.Context;
import android.util.AtomicFile;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import org.json.JSONArray;
import org.json.JSONObject;

/** Wallet-bound private persistence. An unknown API result blocks another signed request. */
final class SpotTradeStore {
  private final AtomicFile file;
  private final String slot, eth, pearl;

  SpotTradeStore(Context c, String slot, String eth, String pearl) {
    this.slot = WalletCatalog.validSlot(slot);
    this.eth = SpotTradeApi.address(eth);
    this.pearl = PearlAddress.normalize(pearl);
    file = new AtomicFile(new File(WalletCatalog.directory(c, slot), "spot-trade.json"));
  }

  private JSONObject load() throws Exception {
    if (!file.getBaseFile().exists() && !new File(file.getBaseFile() + ".bak").exists())
      return new JSONObject()
          .put("slot", slot)
          .put("eth", eth)
          .put("pearl", pearl)
          .put("records", new JSONArray());
    byte[] b = file.readFully();
    if (b.length > 200_000) throw new IllegalArgumentException("现货记录过大");
    JSONObject o = new JSONObject(new String(b, StandardCharsets.UTF_8));
    if (!slot.equals(o.getString("slot"))
        || !eth.equals(o.getString("eth"))
        || !pearl.equals(o.getString("pearl"))) throw new IllegalArgumentException("现货记录属于其他钱包");
    return o;
  }

  private void write(JSONObject o) throws Exception {
    FileOutputStream out = null;
    try {
      out = file.startWrite();
      out.write(o.toString().getBytes(StandardCharsets.UTF_8));
      file.finishWrite(out);
    } catch (Exception e) {
      if (out != null) file.failWrite(out);
      throw e;
    }
  }

  JSONObject plan(String action) throws Exception {
    synchronized (SpotTradeStore.class) {
      JSONObject o = load();
      if (o.has("pending")) throw new IllegalArgumentException("先处理结果待确认的现货请求");
      long now = System.currentTimeMillis(), nonce = Math.max(now, o.optLong("nonce", 0) + 1);
      if (nonce <= 0 || nonce > 9_007_199_254_740_991L)
        throw new IllegalArgumentException("现货 nonce 超出范围");
      o.put("nonce", nonce);
      write(o);
      return new JSONObject()
          .put("action", action)
          .put("from", EvmPublicApi.checkedAddress(eth))
          .put("nonce", nonce)
          .put("issuedAt", now / 1000)
          .put("expires", now / 1000 + 300);
    }
  }

  String deposit() throws Exception {
    return load().optString("deposit", "");
  }

  void pin(String address) throws Exception {
    synchronized (SpotTradeStore.class) {
      JSONObject o = load();
      String a = PearlAddress.normalize(address), old = o.optString("deposit", "");
      if (!old.isEmpty() && !old.equals(a))
        throw new IllegalArgumentException("平台充值地址与第一次记录不同，已阻止充值");
      o.put("deposit", a);
      write(o);
    }
  }

  JSONObject pending() throws Exception {
    JSONObject p = load().optJSONObject("pending");
    if (p == null) return null;
    JSONObject plan = p.getJSONObject("plan"), auth = p.getJSONObject("auth");
    if (!eth.equals(SpotTradeApi.address(plan.getString("from"))))
      throw new IllegalArgumentException("现货请求账户不同");
    if (plan.getString("action").equals("withdraw")
        && plan.getString("asset").equals("PRL")
        && !pearl.equals(plan.getString("destination")))
      throw new IllegalArgumentException("PRL 提现收款人不一致");
    NativeCore.call(
        new JSONObject().put("action", "tradeverify").put("trade", plan).put("tradeAuth", auth));
    return p;
  }

  void save(JSONObject plan, JSONObject auth) throws Exception {
    synchronized (SpotTradeStore.class) {
      if (pending() != null) throw new IllegalArgumentException("已有现货请求等待确认");
      if (!eth.equals(SpotTradeApi.address(plan.getString("from"))))
        throw new IllegalArgumentException("现货请求属于其他账户");
      if (plan.getString("action").equals("withdraw")
          && plan.getString("asset").equals("PRL")
          && !pearl.equals(plan.getString("destination")))
        throw new IllegalArgumentException("Pearl 提现属于其他钱包");
      NativeCore.call(
          new JSONObject().put("action", "tradeverify").put("trade", plan).put("tradeAuth", auth));
      JSONObject o = load();
      o.put("pending", new JSONObject().put("plan", plan).put("auth", auth));
      write(o);
    }
  }

  void accepted(String resultId) throws Exception {
    synchronized (SpotTradeStore.class) {
      JSONObject o = load(), p = pending();
      if (p == null) throw new IllegalArgumentException("现货待确认请求不存在");
      record(
          o,
          p.getJSONObject("plan").getString("action"),
          SpotTradeApi.id(resultId),
          p.getJSONObject("plan"));
      o.remove("pending");
      write(o);
    }
  }

  void depositSent(String hash, String deposit, String amount) throws Exception {
    synchronized (SpotTradeStore.class) {
      if (!hash.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("Pearl 交易编号无效");
      JSONObject o = load();
      record(o, "deposit", hash, new JSONObject().put("deposit", deposit).put("quantity", amount));
      write(o);
    }
  }

  private void record(JSONObject o, String action, String id, JSONObject plan) throws Exception {
    JSONArray all = o.getJSONArray("records"), next = new JSONArray();
    int start = Math.max(0, all.length() - 99);
    for (int i = start; i < all.length(); i++) next.put(all.get(i));
    next.put(
        new JSONObject()
            .put("action", action)
            .put("id", id)
            .put("plan", plan)
            .put("time", System.currentTimeMillis()));
    o.put("records", next);
  }

  JSONArray records() throws Exception {
    return load().getJSONArray("records");
  }

  void acknowledgeUnknown() throws Exception {
    synchronized (SpotTradeStore.class) {
      JSONObject o = load(), p = pending();
      if (p == null) return;
      record(
          o,
          "unknown_acknowledged",
          Long.toString(p.getJSONObject("plan").getLong("nonce")),
          p.getJSONObject("plan"));
      o.remove("pending");
      write(o);
    }
  }

  static final class Deposit {
    final String eth, pearl, deposit, slot;
    final java.math.BigInteger quantity;

    Deposit(String slot, String eth, String pearl, String deposit, java.math.BigInteger qty) {
      this.slot = WalletCatalog.validSlot(slot);
      this.eth = SpotTradeApi.address(eth);
      this.pearl = PearlAddress.normalize(pearl);
      this.deposit = PearlAddress.normalize(deposit);
      quantity = qty;
    }

    void recheck(Context c, SpotTradeApi api) throws Exception {
      if (!deposit.equals(new SpotTradeStore(c, slot, eth, pearl).deposit())
          || !deposit.equals(api.depositAddress(eth, false)))
        throw new IllegalArgumentException("平台充值地址发生变化，已阻止发送");
      api.market();
    }
  }
}
