package ai.pearl.wallet;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Locale;
import org.json.JSONArray;
import org.json.JSONObject;

/** Read-only Ethereum mainnet calls; no seed, signer, approvals or send methods. */
final class EvmPublicApi {
  static final String RPC = "https://ethereum-rpc.publicnode.com";
  static final String WPRL = "0x07696dcab55e62cfef953666b29fe1970518cb00";
  static final String CONTROLLER = "0xa6571b73489d4ebfa269a107208665df7c80aef5";
  static final String USDT = "0xdac17f958d2ee523a2206206994597c13d831ec7";
  static final String FACTORY = "0x1f98431c8ad98523631ae4a59f267346ea31f984";
  static final String POOL = "0x89a67c6dee35db9815da2fb9191f0998a8b37c39";
  static final String QUOTER = "0x61ffe014ba17989e743c5f6cb21bf9697530b21e";

  interface Rpc {
    String post(String body) throws Exception;
  }

  private final Rpc rpc;

  EvmPublicApi() {
    this(PearlApi::httpsRpc);
  }

  EvmPublicApi(Rpc rpc) {
    this.rpc = rpc;
  }

  static String address(String input) {
    if (input == null || !input.trim().matches("0x[0-9a-fA-F]{40}"))
      throw new IllegalArgumentException("Ethereum 地址无效");
    String value = input.trim().toLowerCase(Locale.ROOT);
    if (value.equals("0x" + zeros(40))) throw new IllegalArgumentException("不能使用零地址");
    return value;
  }

  static String checkedAddress(String input) throws Exception {
    return NativeCore.call(new JSONObject().put("action", "ethaddress").put("raw", input))
        .getString("address");
  }

  private static String zeros(int n) {
    char[] chars = new char[n];
    java.util.Arrays.fill(chars, '0');
    return new String(chars);
  }

  static String word(BigInteger value) {
    if (value.signum() < 0 || value.bitLength() > 256)
      throw new IllegalArgumentException("Ethereum 金额超出范围");
    String h = value.toString(16);
    return zeros(64 - h.length()) + h;
  }

  static String addressWord(String a) {
    return word(new BigInteger(address(a).substring(2), 16));
  }

  static void validateRequest(JSONObject r) throws Exception {
    if (!"2.0".equals(r.getString("jsonrpc")) || r.getInt("id") != 1 || r.length() != 4)
      throw new IllegalArgumentException("RPC 请求无效");
    JSONArray p = r.getJSONArray("params");
    String method = r.getString("method");
    if (method.equals("eth_chainId") || method.equals("eth_blockNumber")) {
      if (p.length() != 0) throw new IllegalArgumentException("RPC 参数无效");
      return;
    }
    if (method.equals("eth_getBalance")) {
      if (p.length() != 2 || !p.getString(1).matches("0x[0-9a-f]+"))
        throw new IllegalArgumentException("RPC 参数无效");
      address(p.getString(0));
      return;
    }
    if (!method.equals("eth_call") || p.length() != 2 || !p.getString(1).matches("0x[0-9a-f]+"))
      throw new IllegalArgumentException("只允许 Ethereum 公开查询");
    JSONObject c = p.getJSONObject(0);
    String to = address(c.getString("to")), data = c.getString("data");
    if (c.length() != 2 || !data.matches("0x[0-9a-f]+"))
      throw new IllegalArgumentException("只允许固定查询合约");
    boolean allowed =
        ((to.equals(WPRL) || to.equals(USDT)) && data.equals("0x313ce567"))
            || (to.equals(WPRL) && data.matches("0x70a08231[0-9a-f]{64}"))
            || (to.equals(FACTORY)
                && data.equals(
                    "0x1698ee82"
                        + addressWord(WPRL)
                        + addressWord(USDT)
                        + word(BigInteger.valueOf(10000))))
            || (to.equals(QUOTER) && quoteDataAllowed(data));
    if (!allowed) throw new IllegalArgumentException("只允许固定查询合约");
  }

  private static boolean quoteDataAllowed(String d) {
    if (d.length() != 330) return false;
    boolean sell = d.startsWith("0xc6a5026a"), buy = d.startsWith("0xbd21704a");
    return (sell || buy)
        && d.substring(10, 74).equals(addressWord(sell ? WPRL : USDT))
        && d.substring(74, 138).equals(addressWord(sell ? USDT : WPRL))
        && new BigInteger(d.substring(138, 202), 16).signum() > 0
        && d.substring(202, 266).equals(word(BigInteger.valueOf(10000)))
        && d.substring(266).equals(zeros(64));
  }

  private String request(String method, JSONArray params) throws Exception {
    JSONObject r =
        new JSONObject()
            .put("jsonrpc", "2.0")
            .put("id", 1)
            .put("method", method)
            .put("params", params);
    validateRequest(r);
    JSONObject v = new JSONObject(rpc.post(r.toString()));
    if (!"2.0".equals(v.getString("jsonrpc")) || v.getInt("id") != 1 || v.has("error"))
      throw new IllegalArgumentException("Ethereum 查询失败，当前数量可能无法报价");
    String s = v.getString("result");
    if (!s.matches("0x[0-9a-fA-F]+") || s.length() > 8194)
      throw new IllegalArgumentException("Ethereum 返回值无效");
    return s;
  }

  String block() throws Exception {
    if (!new BigInteger(request("eth_chainId", new JSONArray()).substring(2), 16)
        .equals(BigInteger.ONE)) throw new IllegalArgumentException("RPC 不是 Ethereum 主网");
    String block = request("eth_blockNumber", new JSONArray());
    if (new BigInteger(block.substring(2), 16).signum() <= 0)
      throw new IllegalArgumentException("Ethereum 区块无效");
    return block;
  }

  private String call(String to, String data, String block) throws Exception {
    return request(
        "eth_call",
        new JSONArray().put(new JSONObject().put("to", to).put("data", data)).put(block));
  }

  private BigInteger uint(String hex) {
    if (hex.length() != 66) throw new IllegalArgumentException("Ethereum 合约返回值长度无效");
    return new BigInteger(hex.substring(2), 16);
  }

  void verifyPool(String block) throws Exception {
    if (!uint(call(WPRL, "0x313ce567", block)).equals(BigInteger.valueOf(8))
        || !uint(call(USDT, "0x313ce567", block)).equals(BigInteger.valueOf(6)))
      throw new IllegalArgumentException("代币精度发生变化");
    String pool =
        call(
            FACTORY,
            "0x1698ee82" + addressWord(WPRL) + addressWord(USDT) + word(BigInteger.valueOf(10000)),
            block);
    if (!uint(pool).equals(new BigInteger(POOL.substring(2), 16)))
      throw new IllegalArgumentException("WPRL 池地址不匹配");
  }

  static final class DexQuote {
    final BigDecimal usdt;
    final BigInteger gas;
    final String block;

    DexQuote(BigDecimal u, BigInteger g, String b) {
      usdt = u;
      gas = g;
      block = b;
    }
  }

  DexQuote quote(BigInteger grains, boolean sell, String block) throws Exception {
    if (grains.signum() <= 0 || grains.compareTo(new BigInteger("210000000000000000")) > 0)
      throw new IllegalArgumentException("WPRL 数量无效");
    String data =
        (sell ? "0xc6a5026a" : "0xbd21704a")
            + addressWord(sell ? WPRL : USDT)
            + addressWord(sell ? USDT : WPRL)
            + word(grains)
            + word(BigInteger.valueOf(10000))
            + word(BigInteger.ZERO);
    String result = call(QUOTER, data, block);
    if (result.length() != 258) throw new IllegalArgumentException("DEX 报价返回值无效");
    BigInteger amount = new BigInteger(result.substring(2, 66), 16),
        sqrt = new BigInteger(result.substring(66, 130), 16),
        gas = new BigInteger(result.substring(194), 16);
    // Quoter exact-output reverts on insufficient liquidity. Exact-input may
    // consume only part of the input at the global price boundary: reject it.
    if (amount.signum() <= 0
        || sqrt.compareTo(new BigInteger("4295128740")) <= 0
        || sqrt.compareTo(new BigInteger("1461446703485210103287273052203988822378723970341")) >= 0
        || gas.signum() <= 0) throw new IllegalArgumentException("DEX 流动性不足，拒绝部分数量报价");
    return new DexQuote(new BigDecimal(amount, 6), gas, block);
  }

  static final class Balance {
    final BigInteger grains, wei;
    final String block;

    Balance(BigInteger g, BigInteger w, String b) {
      grains = g;
      wei = w;
      block = b;
    }
  }

  Balance balance(String input) throws Exception {
    String a = address(input), b = block();
    if (!uint(call(WPRL, "0x313ce567", b)).equals(BigInteger.valueOf(8)))
      throw new IllegalArgumentException("WPRL 精度不匹配");
    BigInteger grains = uint(call(WPRL, "0x70a08231" + addressWord(a), b));
    BigInteger wei =
        new BigInteger(request("eth_getBalance", new JSONArray().put(a).put(b)).substring(2), 16);
    return new Balance(grains, wei, b);
  }
}
