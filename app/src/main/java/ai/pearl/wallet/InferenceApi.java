package ai.pearl.wallet;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import javax.net.ssl.HttpsURLConnection;
import org.json.JSONArray;
import org.json.JSONObject;

/** Customer-selected PRL merchant. Wallet secrets never enter this API. */
final class InferenceApi {
  interface Transport {
    JSONObject request(
        String base,
        String method,
        String path,
        String key,
        JSONObject body,
        String invoice,
        String requestId)
        throws Exception;
  }

  private final Transport transport;

  InferenceApi() {
    this(InferenceApi::https);
  }

  InferenceApi(Transport t) {
    transport = t;
  }

  static String base(String value) throws Exception {
    URI u = new URI(value.trim());
    if (!"https".equals(u.getScheme())
        || u.getHost() == null
        || u.getUserInfo() != null
        || u.getQuery() != null
        || u.getFragment() != null
        || (u.getPort() != -1 && u.getPort() != 443)
        || !(u.getPath().isEmpty() || u.getPath().equals("/")))
      throw new IllegalArgumentException("请输入服务商的 HTTPS 域名，不含路径、参数或端口");
    return "https://" + u.getHost().toLowerCase(java.util.Locale.ROOT);
  }

  JSONObject catalog(String base) throws Exception {
    JSONObject c = transport.request(base(base), "GET", "/v1/catalog", "", null, "", "");
    if (!"pearl-inference-1".equals(c.getString("protocol"))
        || !"pearl-mainnet".equals(c.getString("network"))
        || !"PRL".equals(c.getString("asset"))
        || c.getInt("decimals") != 8
        || c.getInt("confirmations") != 6
        || c.getString("merchant").isEmpty()
        || c.getString("merchant").length() > 100
        || c.getString("account_xpub").length() > 150)
      throw new IllegalArgumentException("服务商的 PRL 支付协议不兼容");
    JSONArray models = c.getJSONArray("models");
    if (models.length() < 1 || models.length() > 30) throw new IllegalArgumentException("模型列表无效");
    java.util.HashSet<String> ids = new java.util.HashSet<>();
    for (int i = 0; i < models.length(); i++) {
      JSONObject m = models.getJSONObject(i);
      if (!ids.add(m.getString("id"))
          || m.getString("id").isEmpty()
          || m.getString("id").length() > 150
          || units(m.getString("price_per_request_units")) < 333
          || units(m.getString("price_per_request_units")) > 100_000_000
          || m.getInt("max_tokens") < 1
          || m.getInt("max_tokens") > 4096
          || m.getInt("max_input_bytes") < 1
          || m.getInt("max_input_bytes") > 16384) throw new IllegalArgumentException("模型价格或调用上限无效");
    }
    NativeCore.call(
        new JSONObject()
            .put("action", "invoiceaddress")
            .put("xpub", c.getString("account_xpub"))
            .put("index", 1));
    return c;
  }

  static long units(String s) {
    if (!s.matches("[1-9][0-9]{0,13}")) throw new IllegalArgumentException("PRL 金额无效");
    return Long.parseLong(s);
  }

  String register(String base, String code) throws Exception {
    String key =
        transport
            .request(
                base(base),
                "POST",
                "/v1/accounts",
                "",
                new JSONObject().put("signup_code", code),
                "",
                "")
            .getString("api_key");
    validateKey(key);
    return key;
  }

  static void validateKey(String key) {
    if (!key.matches("prlai_[0-9a-f]{64}"))
      throw new IllegalArgumentException("PRL 推理 API Key 格式无效");
  }

  JSONObject create(String base, String key, JSONObject catalog, JSONObject model, int count)
      throws Exception {
    validateKey(key);
    if (count < 1 || count > 1000) throw new IllegalArgumentException("购买次数应为 1–1000");
    JSONObject inv =
        transport.request(
            base(base),
            "POST",
            "/v1/invoices",
            key,
            new JSONObject().put("model", model.getString("id")).put("request_count", count),
            "",
            "");
    validateInvoice(inv, catalog);
    if (inv.getInt("request_count") != count
        || !sameModel(inv.getJSONObject("model"), model)
        || !"unpaid".equals(inv.getString("status")))
      throw new IllegalArgumentException("账单与购买选择不一致");
    if (inv.getLong("created_at") > System.currentTimeMillis() / 1000 + 60
        || inv.getLong("expires_at") <= System.currentTimeMillis() / 1000 + 120)
      throw new IllegalArgumentException("账单已过期，请检查手机时间");
    return inv;
  }

  static boolean sameModel(JSONObject a, JSONObject b) throws Exception {
    return a.getString("id").equals(b.getString("id"))
        && a.getString("price_per_request_units").equals(b.getString("price_per_request_units"))
        && a.getInt("max_tokens") == b.getInt("max_tokens")
        && a.getInt("max_input_bytes") == b.getInt("max_input_bytes");
  }

  static void validateInvoice(JSONObject inv, JSONObject catalog) throws Exception {
    String id = inv.getString("id");
    long index = inv.getLong("index");
    int count = inv.getInt("request_count");
    if (!id.matches("[0-9a-f]{64}")
        || index <= 0
        || index >= 0x80000000L
        || count < 1
        || count > 1000
        || inv.getLong("created_height") <= 0
        || inv.getLong("expires_at") - inv.getLong("created_at") != 900
        || inv.getInt("remaining_requests") < 0
        || inv.getInt("remaining_requests") > count
        || (!"unpaid".equals(inv.getString("status")) && !"paid".equals(inv.getString("status"))))
      throw new IllegalArgumentException("账单状态或期限无效");
    long total =
        Math.multiplyExact(
            units(inv.getJSONObject("model").getString("price_per_request_units")), count);
    if (units(inv.getString("amount_units")) != total)
      throw new IllegalArgumentException("账单总金额不一致");
    String address =
        NativeCore.call(
                new JSONObject()
                    .put("action", "invoiceaddress")
                    .put("xpub", catalog.getString("account_xpub"))
                    .put("index", index))
            .getString("address");
    if (!address.equals(PearlAddress.normalize(inv.getString("address"))))
      throw new IllegalArgumentException("账单收款地址不属于已绑定服务商");
  }

  static void unchanged(JSONObject before, JSONObject after) throws Exception {
    for (String k :
        new String[] {
          "id",
          "address",
          "index",
          "request_count",
          "amount_units",
          "created_at",
          "expires_at",
          "created_height"
        })
      if (!before.get(k).toString().equals(after.get(k).toString()))
        throw new IllegalArgumentException("账单条款已变化，请停止付款");
    if (!sameModel(before.getJSONObject("model"), after.getJSONObject("model")))
      throw new IllegalArgumentException("模型价格或上限已变化");
  }

  JSONObject invoice(String base, String key, String id) throws Exception {
    if (!id.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("账单 ID 无效");
    return transport.request(base(base), "GET", "/v1/invoices/" + id, key, null, "", "");
  }

  JSONObject claim(String base, String key, String id, String tx) throws Exception {
    if (!id.matches("[0-9a-f]{64}") || !tx.matches("[0-9a-f]{64}"))
      throw new IllegalArgumentException("付款交易 ID 无效");
    return transport.request(
        base(base),
        "POST",
        "/v1/invoices/" + id + "/claim",
        key,
        new JSONObject().put("txid", tx),
        "",
        "");
  }

  JSONObject chat(String base, String key, JSONObject invoice, String prompt, String id)
      throws Exception {
    if (prompt.trim().isEmpty()
        || prompt.getBytes(StandardCharsets.UTF_8).length
            > invoice.getJSONObject("model").getInt("max_input_bytes"))
      throw new IllegalArgumentException("提示词为空或超过服务商输入上限");
    return transport.request(
        base(base),
        "POST",
        "/v1/chat/completions",
        key,
        new JSONObject()
            .put("model", invoice.getJSONObject("model").getString("id"))
            .put(
                "messages",
                new JSONArray().put(new JSONObject().put("role", "user").put("content", prompt)))
            .put("max_tokens", invoice.getJSONObject("model").getInt("max_tokens"))
            .put("stream", false),
        invoice.getString("id"),
        id);
  }

  JSONObject requestStatus(String base, String key, String id) throws Exception {
    if (!id.matches("[a-zA-Z0-9_-]{16,80}")) throw new IllegalArgumentException("调用 ID 无效");
    return transport.request(base(base), "GET", "/v1/requests/" + id, key, null, "", "");
  }

  static String newRequestId() {
    return UUID.randomUUID().toString();
  }

  private static JSONObject https(
      String base,
      String method,
      String path,
      String key,
      JSONObject body,
      String invoice,
      String requestId)
      throws Exception {
    HttpsURLConnection c = (HttpsURLConnection) new URI(base + path).toURL().openConnection();
    try {
      c.setConnectTimeout(15000);
      c.setReadTimeout(100000);
      c.setInstanceFollowRedirects(false);
      c.setRequestMethod(method);
      c.setRequestProperty("Accept", "application/json");
      if (!key.isEmpty()) {
        validateKey(key);
        c.setRequestProperty("Authorization", "Bearer " + key);
      }
      if (!invoice.isEmpty()) c.setRequestProperty("X-Pearl-Invoice", invoice);
      if (!requestId.isEmpty()) c.setRequestProperty("Idempotency-Key", requestId);
      if (body != null) {
        byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
        if (bytes.length > 24000) throw new IllegalArgumentException("请求过大");
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/json");
        c.setFixedLengthStreamingMode(bytes.length);
        try (java.io.OutputStream out = c.getOutputStream()) {
          out.write(bytes);
        }
      }
      int status = c.getResponseCode();
      if (status != 200 && status != 201)
        throw new java.io.IOException("服务返回 HTTP " + status + "；付款或调用结果请查询原记录");
      try (InputStream in = c.getInputStream();
          ByteArrayOutputStream out = new ByteArrayOutputStream()) {
        byte[] chunk = new byte[4096];
        int n;
        while ((n = in.read(chunk)) != -1) {
          out.write(chunk, 0, n);
          if (out.size() > 131072) throw new java.io.IOException("服务响应过大");
        }
        return new JSONObject(out.toString(StandardCharsets.UTF_8.name()));
      }
    } finally {
      c.disconnect();
    }
  }
}
