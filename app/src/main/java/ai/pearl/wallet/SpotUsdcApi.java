package ai.pearl.wallet;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.math.BigInteger;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import javax.net.ssl.HttpsURLConnection;
import org.json.JSONArray;
import org.json.JSONObject;

/** Arbitrum read-only balances and independent USDC Transfer-log receipt checks. */
final class SpotUsdcApi {
  static final String RPC = "https://arb1.arbitrum.io/rpc";
  static final String TRANSFER =
      "0xddf252ad1be2c89b69c2b068fc378daa952ba7f163c4a11628f55a4df523b3ef";
  private final EvmPublicApi.Rpc transport;

  SpotUsdcApi() {
    this(SpotUsdcApi::https);
  }

  SpotUsdcApi(EvmPublicApi.Rpc t) {
    transport = t;
  }

  private Object rpc(String method, JSONArray params) throws Exception {
    JSONObject request =
        new JSONObject()
            .put("jsonrpc", "2.0")
            .put("id", 1)
            .put("method", method)
            .put("params", params);
    validate(request);
    JSONObject response = new JSONObject(transport.post(request.toString()));
    if (!"2.0".equals(response.getString("jsonrpc"))
        || response.getInt("id") != 1
        || response.has("error")
        || !response.has("result")) throw new java.io.IOException("Arbitrum 数据暂不可用");
    return response.get("result");
  }

  private void chain() throws Exception {
    if (!BigInteger.valueOf(42161)
        .equals(DeFiApi.quantity(String.valueOf(rpc("eth_chainId", new JSONArray())))))
      throw new IllegalArgumentException("USDC 数据服务不是 Arbitrum One");
  }

  BigInteger balance(String eth) throws Exception {
    chain();
    String block = String.valueOf(rpc("eth_blockNumber", new JSONArray()));
    DeFiApi.quantity(block);
    String value =
        String.valueOf(
            rpc(
                "eth_call",
                new JSONArray()
                    .put(
                        new JSONObject()
                            .put("to", SpotTradeApi.USDC)
                            .put("data", "0x70a08231" + EvmPublicApi.addressWord(eth)))
                    .put(block)));
    if (!value.matches("0x[0-9a-fA-F]{64}")) throw new IllegalArgumentException("USDC 余额返回无效");
    return new BigInteger(value.substring(2), 16);
  }

  static final class Receipt {
    final BigInteger received;
    final boolean safe, finalized;

    Receipt(BigInteger n, boolean s, boolean f) {
      received = n;
      safe = s;
      finalized = f;
    }
  }

  Receipt receipt(String hash, String eth, long earliest) throws Exception {
    if (earliest <= 0 || earliest > System.currentTimeMillis() / 1000 + 30)
      throw new IllegalArgumentException("提现请求时间无效，请检查手机时钟");
    chain();
    if (!hash.matches("0x[0-9a-fA-F]{64}")) throw new IllegalArgumentException("Arbitrum 交易哈希无效");
    Object result = rpc("eth_getTransactionReceipt", new JSONArray().put(hash));
    if (result == JSONObject.NULL) return null;
    JSONObject r = (JSONObject) result;
    if (!hash.equalsIgnoreCase(r.getString("transactionHash"))
        || !DeFiApi.quantity(r.getString("status")).equals(BigInteger.ONE))
      throw new IllegalArgumentException("USDC 提现交易失败或哈希不匹配");
    String block = r.getString("blockNumber"), blockHash = r.getString("blockHash");
    BigInteger number = DeFiApi.quantity(block);
    if (!blockHash.matches("0x[0-9a-fA-F]{64}")) throw new IllegalArgumentException("提现区块哈希无效");
    JSONObject canonical =
        (JSONObject) rpc("eth_getBlockByNumber", new JSONArray().put(block).put(false));
    if (!number.equals(DeFiApi.quantity(canonical.getString("number")))
        || !blockHash.equalsIgnoreCase(canonical.getString("hash"))
        || DeFiApi.quantity(canonical.getString("timestamp"))
                .compareTo(BigInteger.valueOf(earliest - 30))
            < 0
        || DeFiApi.quantity(canonical.getString("timestamp"))
                .compareTo(BigInteger.valueOf(System.currentTimeMillis() / 1000 + 60))
            > 0) throw new IllegalArgumentException("提现区块不匹配或时间无效");
    BigInteger received = BigInteger.ZERO;
    String word = "0x" + EvmPublicApi.addressWord(eth);
    JSONArray logs = r.getJSONArray("logs");
    if (logs.length() > 1000) throw new IllegalArgumentException("提现日志过大");
    for (int i = 0; i < logs.length(); i++) {
      JSONObject log = logs.getJSONObject(i);
      if (!SpotTradeApi.USDC.equals(SpotTradeApi.address(log.getString("address")))) continue;
      JSONArray topics = log.getJSONArray("topics");
      if (topics.length() != 3 || !TRANSFER.equalsIgnoreCase(topics.getString(0))) continue;
      if (log.optBoolean("removed", false)
          || !hash.equalsIgnoreCase(log.getString("transactionHash"))
          || !blockHash.equalsIgnoreCase(log.getString("blockHash"))
          || !number.equals(DeFiApi.quantity(log.getString("blockNumber"))))
        throw new IllegalArgumentException("USDC 日志与提现区块不一致");
      String data = log.getString("data");
      if (!data.matches("0x[0-9a-fA-F]{64}")
          || !topics.getString(1).matches("0x0{24}[0-9a-fA-F]{40}")
          || !topics.getString(2).matches("0x0{24}[0-9a-fA-F]{40}"))
        throw new IllegalArgumentException("USDC 日志格式无效");
      BigInteger n = new BigInteger(data.substring(2), 16);
      if (word.equalsIgnoreCase(topics.getString(2))) received = received.add(n);
      if (word.equalsIgnoreCase(topics.getString(1))) received = received.subtract(n);
    }
    if (received.signum() <= 0) throw new IllegalArgumentException("未找到本钱包收到正确 USDC 的链上记录");
    JSONObject
        safe = (JSONObject) rpc("eth_getBlockByNumber", new JSONArray().put("safe").put(false)),
        finalized =
            (JSONObject) rpc("eth_getBlockByNumber", new JSONArray().put("finalized").put(false));
    return new Receipt(
        received,
        DeFiApi.quantity(safe.getString("number")).compareTo(number) >= 0,
        DeFiApi.quantity(finalized.getString("number")).compareTo(number) >= 0);
  }

  static void validate(JSONObject r) throws Exception {
    String m = r.getString("method");
    JSONArray p = r.getJSONArray("params");
    boolean ok = false;
    switch (m) {
      case "eth_chainId":
      case "eth_blockNumber":
        ok = p.length() == 0;
        break;
      case "eth_getTransactionReceipt":
        ok = p.length() == 1 && p.getString(0).matches("0x[0-9a-fA-F]{64}");
        break;
      case "eth_getBlockByNumber":
        ok =
            p.length() == 2
                && (p.getString(0).equals("safe")
                    || p.getString(0).equals("finalized")
                    || p.getString(0).matches("0x[0-9a-f]{1,16}"))
                && Boolean.FALSE.equals(p.get(1));
        break;
      case "eth_call":
        if (p.length() == 2) {
          JSONObject c = p.getJSONObject(0);
          ok =
              c.length() == 2
                  && SpotTradeApi.USDC.equals(c.getString("to"))
                  && c.getString("data").matches("0x70a082310{24}[0-9a-f]{40}")
                  && p.getString(1).matches("0x[0-9a-f]{1,16}");
        }
        break;
    }
    if (!ok || r.length() != 4 || r.getInt("id") != 1 || !"2.0".equals(r.getString("jsonrpc")))
      throw new IllegalArgumentException("不允许此 Arbitrum 查询");
  }

  static String https(String body) throws Exception {
    validate(new JSONObject(body));
    HttpsURLConnection c = (HttpsURLConnection) new URI(RPC).toURL().openConnection();
    try {
      c.setInstanceFollowRedirects(false);
      c.setConnectTimeout(12000);
      c.setReadTimeout(20000);
      c.setUseCaches(false);
      c.setRequestMethod("POST");
      c.setDoOutput(true);
      c.setRequestProperty("Content-Type", "application/json");
      c.setRequestProperty("Accept", "application/json");
      c.setRequestProperty("User-Agent", "PearlPocketAndroid/0.11");
      byte[] b = body.getBytes(StandardCharsets.UTF_8);
      c.setFixedLengthStreamingMode(b.length);
      try (java.io.OutputStream out = c.getOutputStream()) {
        out.write(b);
      }
      if (c.getResponseCode() != 200) throw new java.io.IOException("Arbitrum 查询暂不可用");
      String type = c.getContentType();
      if (type == null || !type.toLowerCase(java.util.Locale.ROOT).contains("application/json"))
        throw new java.io.IOException("Arbitrum 返回非 JSON 内容");
      try (InputStream in = c.getInputStream();
          ByteArrayOutputStream out = new ByteArrayOutputStream()) {
        byte[] buffer = new byte[8192];
        int n;
        while ((n = in.read(buffer)) != -1) {
          if (out.size() + n > 1_000_000) throw new java.io.IOException("Arbitrum 响应过大");
          out.write(buffer, 0, n);
        }
        return out.toString(StandardCharsets.UTF_8.name());
      }
    } finally {
      c.disconnect();
    }
  }
}
