package ai.pearl.wallet;

import static org.junit.Assert.*;

import org.json.JSONObject;
import org.junit.Test;

public class InferenceApiTest {
  @Test
  public void merchantHostCannotCarryCredentialsPathsOrRedirectTargets() throws Exception {
    assertEquals("https://merchant.example", InferenceApi.base(" https://MERCHANT.example/ "));
    for (String url :
        new String[] {
          "http://merchant.example",
          "https://key@merchant.example",
          "https://merchant.example/v1",
          "https://merchant.example?redirect=evil",
          "https://merchant.example#evil",
          "https://merchant.example:8080",
          "file:///tmp/key"
        }) assertThrows(Exception.class, () -> InferenceApi.base(url));
  }

  @Test
  public void prlUsesIntegerGrainAndDedicatedApiKeys() {
    assertEquals(10000, InferenceApi.units("10000"));
    for (String v : new String[] {"0", "-1", "1.0", "01", "1e4", "9999999999999999999"})
      assertThrows(IllegalArgumentException.class, () -> InferenceApi.units(v));
    InferenceApi.validateKey("prlai_" + "ab".repeat(32));
    for (String k :
        new String[] {
          "sk-prl-infapi-abc", "seed", "prlai_" + "ab".repeat(31), "prlai_" + "gg".repeat(32)
        }) assertThrows(IllegalArgumentException.class, () -> InferenceApi.validateKey(k));
  }

  static JSONObject invoice() throws Exception {
    return new JSONObject()
        .put("id", "a".repeat(64))
        .put("address", "prl1p-placeholder")
        .put("index", 1)
        .put("request_count", 10)
        .put("amount_units", "100000")
        .put("created_at", 1000)
        .put("expires_at", 1900)
        .put("created_height", 10)
        .put(
            "model",
            new JSONObject()
                .put("id", "test/model")
                .put("price_per_request_units", "10000")
                .put("max_tokens", 512)
                .put("max_input_bytes", 4000));
  }

  @Test
  public void recheckRejectsChangedInvoiceTermsAndAcceptsQuotaUpdates() throws Exception {
    JSONObject original = invoice(),
        paid = invoice().put("status", "paid").put("remaining_requests", 9);
    InferenceApi.unchanged(original, paid);
    for (String field :
        new String[] {
          "id",
          "address",
          "index",
          "request_count",
          "amount_units",
          "created_at",
          "expires_at",
          "created_height"
        }) {
      JSONObject bad = invoice().put(field, "changed");
      assertThrows(Exception.class, () -> InferenceApi.unchanged(original, bad));
    }
    for (String field :
        new String[] {"id", "price_per_request_units", "max_tokens", "max_input_bytes"}) {
      JSONObject bad = invoice();
      bad.getJSONObject("model").put(field, "changed");
      assertThrows(Exception.class, () -> InferenceApi.unchanged(original, bad));
    }
  }

  @Test
  public void chatUsesBoundInvoiceAndExplicitOutputBudget() throws Exception {
    int[] sent = {0};
    InferenceApi api =
        new InferenceApi(
            (base, method, path, key, body, invoice, id) -> {
              sent[0]++;
              assertEquals("POST", method);
              assertEquals("/v1/chat/completions", path);
              assertEquals("a".repeat(64), invoice);
              assertEquals(512, body.getInt("max_tokens"));
              assertFalse(body.getBoolean("stream"));
              assertEquals("test/model", body.getString("model"));
              assertEquals(
                  "hello", body.getJSONArray("messages").getJSONObject(0).getString("content"));
              return new JSONObject();
            });
    api.chat(
        "https://merchant.example",
        "prlai_" + "ab".repeat(32),
        invoice(),
        "hello",
        InferenceApi.newRequestId());
    assertEquals(1, sent[0]);
    assertThrows(
        IllegalArgumentException.class,
        () ->
            api.chat(
                "https://merchant.example",
                "prlai_" + "ab".repeat(32),
                invoice(),
                "中".repeat(1500),
                InferenceApi.newRequestId()));
    assertEquals(1, sent[0]);
  }
}
