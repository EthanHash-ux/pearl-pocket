package ai.pearl.wallet;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.math.BigInteger;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import javax.net.ssl.HttpsURLConnection;
import org.json.JSONArray;
import org.json.JSONObject;

/** Fixed native PRL / Arbitrum USDC custodial spot venue. No perpetuals or arbitrary API calls. */
final class SpotTradeApi {
  static final String BASE = "https://api.pearl-trade.com";
  static final String USDC = "0xaf88d065e77c8cc2239327c5edb3a432268e5831";
  static final String VAULT = "0x779fb955d033dc12e8b08a07d69f4c1973aa0c37";
  static final BigInteger GRAINS = BigInteger.valueOf(100_000_000),
      LOT = BigInteger.valueOf(1_000_000);

  interface Transport {
    String request(String method, String path, String body) throws Exception;
  }

  private final Transport transport;

  SpotTradeApi() {
    this(SpotTradeApi::https);
  }

  SpotTradeApi(Transport t) {
    transport = t;
  }

  static String address(String a) {
    return EvmPublicApi.address(a);
  }

  static BigInteger units(Object o) throws Exception {
    String s = String.valueOf(o);
    if (!s.matches("0|[1-9][0-9]{0,18}")) throw new IllegalArgumentException("现货金额字段无效");
    return new BigInteger(s);
  }

  static String id(Object o) throws Exception {
    BigInteger n = units(o);
    if (n.signum() <= 0 || n.bitLength() > 53) throw new IllegalArgumentException("订单编号无效");
    return n.toString();
  }

  static String display(BigInteger n, int dp) {
    return new java.math.BigDecimal(n, dp).stripTrailingZeros().toPlainString();
  }

  static BigInteger qty(String s) throws Exception {
    BigInteger n = PearlAmount.parsePositivePrl(s);
    if (n.compareTo(GRAINS) < 0
        || !n.mod(LOT).equals(BigInteger.ZERO)
        || n.compareTo(new BigInteger("2100000000000000")) > 0)
      throw new IllegalArgumentException("最少 1 PRL，数量必须是 0.01 PRL 的整数倍；不会自动截去小数");
    return n;
  }

  static BigInteger price(String s) throws Exception {
    BigInteger n = DeFiAmount.parse(s);
    if (!n.mod(BigInteger.valueOf(100)).equals(BigInteger.ZERO)
        || n.compareTo(new BigInteger("1000000000000")) > 0)
      throw new IllegalArgumentException("价格最多 4 位小数，并须在支持范围内");
    return n;
  }

  JSONObject get(String path) throws Exception {
    return new JSONObject(transport.request("GET", path, null));
  }

  static final class Market {
    final int buyFee, sellFee;
    final BigInteger prlWithdrawal, usdcWithdrawal;

    Market(JSONObject o) throws Exception {
      JSONObject market = null;
      JSONArray rows = o.getJSONArray("markets");
      for (int i = 0; i < rows.length(); i++)
        if (rows.getJSONObject(i).getString("market").equals("PRL")) {
          if (market != null) throw new IllegalArgumentException("市场重复");
          market = rows.getJSONObject(i);
        }
      if (market == null
          || !"PRL".equals(market.getString("base"))
          || !"USDC-ARB".equals(market.getString("quote")))
        throw new IllegalArgumentException("原生 PRL 现货市场未开放");
      JSONObject base = market.getJSONObject("base_asset"),
          quote = market.getJSONObject("quote_asset"),
          c = market.getJSONObject("constants");
      if (!"pearl".equals(base.getString("chain"))
          || !"PRL".equals(base.getString("code"))
          || base.getInt("decimals") != 8
          || !base.has("contract")
          || !base.isNull("contract")
          || !"arb".equals(quote.getString("chain"))
          || !"USDC-ARB".equals(quote.getString("code"))
          || quote.getInt("decimals") != 6
          || !USDC.equals(address(quote.getString("contract")))
          || c.getInt("price_tick_micro") != 100
          || !LOT.equals(units(c.get("lot_units")))
          || !GRAINS.equals(units(c.get("min_order_units")))
          || c.getInt("base_decimals") != 8
          || c.getInt("quote_decimals") != 6
          || !VAULT.equals(
              address(o.getJSONObject("chains").getJSONObject("42161").getString("vault"))))
        throw new IllegalArgumentException("市场网络、代币或交易单位已变化，请更新钱包");
      JSONObject fees = market.getJSONObject("fees");
      buyFee = fee(fees, "buy_fee_bps");
      sellFee = fee(fees, "sell_fee_bps");
      JSONObject w = o.getJSONObject("fees").getJSONObject("withdrawal_fees");
      prlWithdrawal = units(w.get("PRL"));
      usdcWithdrawal = units(w.get("USDC-ARB"));
    }

    static int fee(JSONObject o, String k) throws Exception {
      int v = o.getInt(k);
      if (v < 0 || v > 1000 || !units(o.get(k)).equals(BigInteger.valueOf(v)))
        throw new IllegalArgumentException("平台费率超出范围");
      return v;
    }

    boolean same(Market m) {
      return buyFee == m.buyFee
          && sellFee == m.sellFee
          && prlWithdrawal.equals(m.prlWithdrawal)
          && usdcWithdrawal.equals(m.usdcWithdrawal);
    }
  }

  Market market() throws Exception {
    return new Market(get("/v1/markets"));
  }

  static final class Balances {
    BigInteger prl, usdc, prlReserved, usdcReserved;

    Balances(JSONObject o) throws Exception {
      Set<String> assets = new HashSet<>();
      JSONArray a = o.getJSONArray("balances");
      if (a.length() > 100) throw new IllegalArgumentException("余额响应过大");
      for (int i = 0; i < a.length(); i++) {
        JSONObject b = a.getJSONObject(i);
        String asset = b.getString("asset");
        if (!assets.add(asset)) throw new IllegalArgumentException("余额资产重复");
        if (asset.equals("PRL")) {
          prl = units(b.get("available"));
          prlReserved = units(b.get("reserved"));
        }
        if (asset.equals("USDC-ARB")) {
          usdc = units(b.get("available"));
          usdcReserved = units(b.get("reserved"));
        }
      }
      if (prl == null || usdc == null) throw new IllegalArgumentException("交易余额缺少资产");
    }
  }

  Balances balances(String eth) throws Exception {
    return new Balances(get("/v1/balances/" + address(eth)));
  }

  TradeBook book() throws Exception {
    JSONObject o = get("/v1/markets/PRL/orderbook?depth=50");
    if (!"PRL".equals(o.getString("market"))) throw new IllegalArgumentException("盘口市场错误");
    JSONArray bids = levels(o.getJSONArray("bids")), asks = levels(o.getJSONArray("asks"));
    return new TradeBook(bids, asks, "size");
  }

  private static JSONArray levels(JSONArray rows) throws Exception {
    JSONArray result = new JSONArray();
    for (int i = 0; i < rows.length(); i++) {
      JSONObject r = rows.getJSONObject(i);
      BigInteger p = units(r.get("price")), q = units(r.get("qtySats"));
      if (p.signum() <= 0 || q.signum() <= 0) throw new IllegalArgumentException("盘口金额无效");
      result.put(new JSONObject().put("price", display(p, 6)).put("size", display(q, 8)));
    }
    return result;
  }

  String depositAddress(String eth, boolean allocate) throws Exception {
    eth = address(eth);
    JSONObject o =
        allocate
            ? new JSONObject(
                transport.request(
                    "POST", "/v1/users", new JSONObject().put("eth_address", eth).toString()))
            : get("/v1/users/" + eth);
    if (!eth.equals(address(o.getString("eth_address"))))
      throw new IllegalArgumentException("充值地址绑定了其他账户");
    String pearl = PearlAddress.normalize(o.getString("pearl_deposit_address"));
    if (!pearl.startsWith("prl1p")) throw new IllegalArgumentException("充值地址类型无效");
    return pearl;
  }

  JSONArray orders(String eth) throws Exception {
    return get("/v1/orders?user=" + address(eth)).getJSONArray("orders");
  }

  JSONArray withdrawals(String eth) throws Exception {
    return get("/v1/withdrawals?user=" + address(eth)).getJSONArray("withdrawals");
  }

  JSONObject submit(JSONObject plan, JSONObject auth) throws Exception {
    NativeCore.call(
        new JSONObject().put("action", "tradeverify").put("trade", plan).put("tradeAuth", auth));
    if (plan.getLong("expires") <= System.currentTimeMillis() / 1000)
      throw new IllegalArgumentException("签名请求已过期；保留记录并检查平台，不会重新签署");
    String action = plan.getString("action"), method = "POST", path, key;
    switch (action) {
      case "place_order":
        path = "/v1/orders";
        key = "order";
        break;
      case "withdraw":
        path = "/v1/withdrawals";
        key = "withdrawal";
        break;
      case "cancel_order":
        path = "/v1/orders/" + id(plan.getString("orderId"));
        key = "request";
        method = "DELETE";
        break;
      default:
        throw new IllegalArgumentException("不支持此现货操作");
    }
    // Both body and payload come from the native signer. Java cannot substitute terms.
    JSONObject body =
        new JSONObject().put(key, new JSONObject(auth.getString("payload"))).put("auth", auth);
    return new JSONObject(transport.request(method, path, body.toString()));
  }

  static void validateRoute(String method, String path, String body) throws Exception {
    boolean read =
        method.equals("GET")
            && body == null
            && (path.equals("/v1/markets")
                || path.equals("/v1/markets/PRL/orderbook?depth=50")
                || path.matches("/v1/(balances|users)/0x[0-9a-f]{40}")
                || path.matches("/v1/(orders|withdrawals)\\?user=0x[0-9a-f]{40}"));
    if (read) return;
    if (method.equals("POST") && path.equals("/v1/users")) {
      JSONObject o = new JSONObject(body);
      if (o.length() != 1
          || !o.getString("eth_address").equals(address(o.getString("eth_address"))))
        throw new IllegalArgumentException("账户分配请求无效");
      return;
    }
    String action, key;
    if (method.equals("POST") && path.equals("/v1/orders")) {
      action = "place_order";
      key = "order";
    } else if (method.equals("POST") && path.equals("/v1/withdrawals")) {
      action = "withdraw";
      key = "withdrawal";
    } else if (method.equals("DELETE") && path.matches("/v1/orders/[1-9][0-9]{0,15}")) {
      action = "cancel_order";
      key = "request";
    } else throw new IllegalArgumentException("不允许此现货接口");
    JSONObject o = new JSONObject(body), a = o.getJSONObject("auth"), p = o.getJSONObject(key);
    if (o.length() != 2
        || a.length() != 5
        || !p.toString().equals(new JSONObject(a.getString("payload")).toString()))
      throw new IllegalArgumentException("现货请求与签名不一致");
    JSONObject plan =
        new JSONObject()
            .put("action", action)
            .put("from", a.getString("eth_address"))
            .put("nonce", a.getLong("nonce"))
            .put("issuedAt", a.getLong("issued_at"))
            .put("expires", a.getLong("issued_at") + 300);
    if (action.equals("place_order")) {
      if (p.length() != 5
          || !"PRL".equals(p.getString("market"))
          || !"limit".equals(p.getString("type")))
        throw new IllegalArgumentException("仅支持 PRL 保护限价单");
      plan.put("side", p.getString("side"))
          .put("price", p.getString("price_micro_per_prl"))
          .put("quantity", p.getString("qty_sats"));
    }
    if (action.equals("withdraw")) {
      if (p.length() != 3) throw new IllegalArgumentException("提现字段无效");
      plan.put("asset", p.getString("asset"))
          .put("destination", p.getString("dest_address"))
          .put("quantity", p.getString("amount_units"));
    }
    if (action.equals("cancel_order")) {
      if (p.length() != 1 || !path.equals("/v1/orders/" + id(p.get("order_id"))))
        throw new IllegalArgumentException("撤单编号不一致");
      plan.put("orderId", id(p.get("order_id")));
    }
    NativeCore.call(
        new JSONObject().put("action", "tradeverify").put("trade", plan).put("tradeAuth", a));
    long age = System.currentTimeMillis() / 1000 - a.getLong("issued_at");
    if (age < -30 || age >= 300) throw new IllegalArgumentException("现货请求签名已过期");
  }

  static String https(String method, String path, String body) throws Exception {
    validateRoute(method, path, body);
    URI uri = new URI(BASE + path);
    HttpsURLConnection c = (HttpsURLConnection) uri.toURL().openConnection();
    try {
      c.setInstanceFollowRedirects(false);
      c.setConnectTimeout(12_000);
      c.setReadTimeout(20_000);
      c.setUseCaches(false);
      c.setRequestMethod(method);
      c.setRequestProperty("Accept", "application/json");
      c.setRequestProperty("Cache-Control", "no-cache");
      c.setRequestProperty("User-Agent", "PearlPocketAndroid/0.11");
      if (body != null) {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > 8192) throw new IllegalArgumentException("现货请求过大");
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/json");
        c.setFixedLengthStreamingMode(bytes.length);
        try (java.io.OutputStream out = c.getOutputStream()) {
          out.write(bytes);
        }
      }
      int code = c.getResponseCode();
      if (code < 200 || code >= 300)
        throw new java.io.IOException("平台返回 HTTP " + code + "；提交结果须查询平台订单或提现记录");
      String type = c.getContentType();
      if (type == null || !type.toLowerCase(java.util.Locale.ROOT).contains("application/json"))
        throw new java.io.IOException("现货服务返回了非 JSON 内容");
      try (InputStream in = c.getInputStream();
          ByteArrayOutputStream out = new ByteArrayOutputStream()) {
        byte[] buffer = new byte[8192];
        int n;
        while ((n = in.read(buffer)) != -1) {
          if (out.size() + n > 1_000_000) throw new java.io.IOException("现货响应过大");
          out.write(buffer, 0, n);
        }
        return out.toString(StandardCharsets.UTF_8.name());
      }
    } finally {
      c.disconnect();
    }
  }
}
