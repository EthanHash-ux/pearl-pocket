package ai.pearl.wallet;

import java.math.BigInteger;
import org.json.JSONArray;
import org.json.JSONObject;

/** Experimental community bridge. All amounts are 8-decimal grains, never wei. */
final class BridgeApi {
  static final String BASE = "https://api.pearlbridge.xyz/v1/";
  private final PearlApi.Transport get;

  BridgeApi() {
    this(PearlApi::httpsGet);
  }

  BridgeApi(PearlApi.Transport transport) {
    get = transport;
  }

  static BigInteger grains(JSONObject o, String key) throws Exception {
    Object raw = o.get(key);
    if (!(raw instanceof String) || !((String) raw).matches("[0-9]{1,18}"))
      throw new IllegalArgumentException("桥金额字段无效");
    BigInteger n = new BigInteger((String) raw);
    if (n.compareTo(new BigInteger("210000000000000000")) > 0)
      throw new IllegalArgumentException("桥金额超出范围");
    return n;
  }

  static boolean bool(JSONObject o, String key) throws Exception {
    Object v = o.get(key);
    if (!(v instanceof Boolean)) throw new IllegalArgumentException("桥状态字段缺失或无效");
    return (Boolean) v;
  }

  static int bounded(JSONObject o, String key, int min, int max) throws Exception {
    Object v = o.get(key);
    if (!(v instanceof Number) || !v.toString().matches("[0-9]+"))
      throw new IllegalArgumentException("桥参数无效");
    BigInteger n = new BigInteger(v.toString());
    if (n.compareTo(BigInteger.valueOf(min)) < 0 || n.compareTo(BigInteger.valueOf(max)) > 0)
      throw new IllegalArgumentException("桥参数超出范围");
    return n.intValue();
  }

  static long timestamp(JSONObject o, long now) throws Exception {
    long time = o.getLong("timestamp"), age = now - time;
    if (time <= 0 || age > 90_000 || age < -30_000)
      throw new IllegalArgumentException("桥报价已过期，请刷新；同时检查手机时间");
    return time;
  }

  static final class Status {
    final boolean paused;
    final int confirmations, slowSeconds;
    final BigInteger mintRemaining, burnRemaining, fastRemaining;
    final long time;

    Status(JSONObject o, long now) throws Exception {
      JSONObject c = o.getJSONObject("contracts");
      if (!"mainnet".equals(c.getString("network"))
          || o.getInt("decimals") != 8
          || !EvmPublicApi.WPRL.equals(EvmPublicApi.address(c.getString("wprl")))
          || !EvmPublicApi.CONTROLLER.equals(EvmPublicApi.address(c.getString("bridgeController"))))
        throw new IllegalArgumentException("桥网络或固定合约不匹配");
      paused = bool(o, "paused");
      time = timestamp(o, now);
      JSONObject l = o.getJSONObject("limits");
      mintRemaining = grains(l, "mintWindowRemainingGrains");
      burnRemaining = grains(l, "burnWindowRemainingGrains");
      fastRemaining = grains(l, "fastMintWindowRemainingGrains");
      slowSeconds = bounded(l, "slowMintDelaySeconds", 0, 604800);
      confirmations = bounded(o.getJSONObject("confirmations"), "pearlMinConfirmations", 1, 1000);
    }
  }

  Status status() throws Exception {
    return new Status(new JSONObject(get.get(BASE + "status")), System.currentTimeMillis());
  }

  static final class Quote {
    final boolean mint, paused, withinCap;
    final BigInteger amount, fee, net;
    final String lane;
    final int delay, confirmations;
    final long time;

    Quote(JSONObject o, boolean mint, BigInteger expected, long now) throws Exception {
      this.mint = mint;
      if (!(mint ? "mint" : "burn").equals(o.getString("direction")))
        throw new IllegalArgumentException("桥方向不匹配");
      amount = grains(o, "amountGrains");
      fee = grains(o, "feeGrains");
      net = grains(o, "netGrains");
      if (amount.signum() <= 0
          || !amount.equals(expected)
          || !fee.add(net).equals(amount)
          || net.signum() <= 0) throw new IllegalArgumentException("桥费用或数量不一致");
      paused = bool(o, "paused");
      withinCap = bool(o, "withinDailyCap");
      time = timestamp(o, now);
      if (mint) {
        lane = o.getString("lane");
        if (!lane.equals("fast") && !lane.equals("slow"))
          throw new IllegalArgumentException("桥通道未知");
        delay = bounded(o, "slowLaneDelaySeconds", 0, 604800);
        confirmations = bounded(o, "confirmationsRequired", 1, 1000);
        if (lane.equals("fast") && delay != 0) throw new IllegalArgumentException("快通道延迟不一致");
      } else {
        lane = "burn";
        delay = 0;
        confirmations = 0;
        JSONObject tx = o.getJSONObject("transaction");
        JSONArray steps = tx.getJSONArray("steps");
        if (tx.getInt("chainId") != 1
            || steps.length() != 2
            || !EvmPublicApi.WPRL.equals(
                EvmPublicApi.address(steps.getJSONObject(0).getString("to")))
            || !EvmPublicApi.CONTROLLER.equals(
                EvmPublicApi.address(steps.getJSONObject(1).getString("to"))))
          throw new IllegalArgumentException("赎回合约不匹配");
      }
    }

    void available() {
      if (paused || !withinCap)
        throw new IllegalArgumentException(paused ? "桥已暂停，不能继续" : "桥当日额度不足，不能继续");
    }

    boolean sameTerms(Quote q) {
      return mint == q.mint
          && amount.equals(q.amount)
          && fee.equals(q.fee)
          && net.equals(q.net)
          && lane.equals(q.lane)
          && delay == q.delay
          && confirmations == q.confirmations;
    }
  }

  Quote quote(boolean mint, BigInteger amount) throws Exception {
    if (amount.signum() <= 0 || amount.compareTo(new BigInteger("210000000000000000")) > 0)
      throw new IllegalArgumentException("桥金额无效");
    return new Quote(
        new JSONObject(
            get.get(BASE + "quote/" + (mint ? "mint" : "burn") + "?amountGrains=" + amount)),
        mint,
        amount,
        System.currentTimeMillis());
  }

  String deposit(String eth) throws Exception {
    eth = EvmPublicApi.address(eth);
    JSONObject o = new JSONObject(get.get(BASE + "deposit-address?ethAddress=" + eth));
    if (!eth.equals(EvmPublicApi.address(o.getString("ethAddress"))))
      throw new IllegalArgumentException("桥收款人不匹配");
    String a = PearlAddress.normalize(o.getString("pearlAddress"));
    if (!a.startsWith("prl1p")) throw new IllegalArgumentException("桥充值地址类型无效");
    return a;
  }

  static final class MintPlan {
    final Quote quote;
    final String eth, deposit;
    final boolean firstUse;

    MintPlan(Quote q, String e, String d, boolean first) {
      quote = q;
      eth = EvmPublicApi.address(e);
      deposit = PearlAddress.normalize(d);
      firstUse = first;
    }

    String summary() {
      return "Ethereum 收款地址\n"
          + eth
          + "\n预计得到 "
          + PearlAmount.format(quote.net)
          + " WPRL\n桥费 "
          + PearlAmount.format(quote.fee)
          + " PRL（另付 Pearl 网络费）\n至少 "
          + quote.confirmations
          + " 个 Pearl 确认 · "
          + (quote.lane.equals("fast") ? "快通道，到账时间由桥决定" : "慢通道，额外等待 " + quote.delay / 3600 + " 小时")
          + "\n通过社区 PearlBridge 托管与铸造；转账确认不等于跨链完成。";
    }
  }

  MintPlan prepare(BigInteger amount, String eth, BridgeBook book) throws Exception {
    Status s = status();
    Quote q = quote(true, amount);
    q.available();
    if (s.paused || s.confirmations != q.confirmations)
      throw new IllegalArgumentException("桥状态变化，请重新报价");
    String deposit = deposit(eth);
    boolean first = book.pin(eth, deposit);
    return new MintPlan(q, eth, deposit, first);
  }

  void recheck(MintPlan plan, BridgeBook book) throws Exception {
    Status s = status();
    Quote fresh = quote(true, plan.quote.amount);
    fresh.available();
    if (s.paused
        || s.confirmations != fresh.confirmations
        || !plan.quote.sameTerms(fresh)
        || !plan.deposit.equals(deposit(plan.eth)))
      throw new IllegalArgumentException("桥地址、费用或通道已变化，取消本次发送，请重新报价");
    book.pin(plan.eth, plan.deposit);
  }

  static final class Transfer {
    final String state, otherHash;
    final boolean refunded;

    Transfer(String s, String h, boolean r) {
      state = s;
      otherHash = h;
      refunded = r;
    }

    String label(boolean mint) {
      if (refunded) return "桥报告已退款，请核对 Pearl 链";
      if (state.equals("under_review")) return "桥人工检查中";
      if (mint && (state.equals("minted") || state.equals("finalized")))
        return "桥报告已铸造，请核对 Ethereum 链";
      if (!mint && state.equals("finalized")) return "桥报告已付款，请核对 Pearl 链";
      if (state.equals("failed") || state.equals("cancelled") || state.equals("reorged"))
        return "桥报告失败 / 取消，需要检查";
      if (state.equals("pending")
          || state.equals("queued")
          || state.equals("signing")
          || state.equals("submitted")
          || state.equals("submitted_stuck")) return "桥处理中 · " + state;
      return "桥返回未知状态，需要检查 · " + state;
    }
  }

  Transfer transfer(boolean mint, String hash) throws Exception {
    BridgeBook.hash(mint, hash);
    JSONObject o = new JSONObject(get.get(BASE + (mint ? "mints/" : "burns/") + hash));
    String state = o.optString("state", "");
    if (state.length() > 48 || !state.matches("[a-z_]*"))
      throw new IllegalArgumentException("桥进度状态无效");
    boolean refunded = !o.isNull("refundedAt") && o.has("refundedAt");
    String other = o.optString(refunded ? "refundPrlTxId" : mint ? "mintTxHash" : "pearlTxId", "");
    if (!other.isEmpty()) BridgeBook.hash(refunded || !mint, other);
    return new Transfer(state, other, refunded);
  }
}
