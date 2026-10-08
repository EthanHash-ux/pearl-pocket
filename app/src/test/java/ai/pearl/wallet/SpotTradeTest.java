package ai.pearl.wallet;

import static org.junit.Assert.*;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import org.json.*;
import org.junit.Test;

public class SpotTradeTest {
  static final String HASH = "0x" + "12".repeat(32), BLOCK = "0x" + "34".repeat(32);

  static final class ReceiptFixture implements EvmPublicApi.Rpc {
    String chain = "0xa4b1",
        canonical = BLOCK,
        contract = SpotTradeApi.USDC,
        destination = eth,
        status = "0x1",
        hash = HASH;
    boolean removed;
    long timestamp = System.currentTimeMillis() / 1000;

    public String post(String body) throws Exception {
      JSONObject r = new JSONObject(body);
      JSONArray p = r.getJSONArray("params");
      Object value;
      switch (r.getString("method")) {
        case "eth_chainId":
          value = chain;
          break;
        case "eth_getBlockByNumber":
          value =
              new JSONObject()
                  .put(
                      "number",
                      p.getString(0).equals("safe")
                          ? "0x102"
                          : p.getString(0).equals("finalized") ? "0xfe" : p.getString(0))
                  .put("hash", canonical)
                  .put("timestamp", "0x" + Long.toHexString(timestamp));
          break;
        case "eth_getTransactionReceipt":
          value =
              new JSONObject()
                  .put("transactionHash", hash)
                  .put("blockNumber", "0x100")
                  .put("blockHash", BLOCK)
                  .put("status", status)
                  .put(
                      "logs",
                      new JSONArray()
                          .put(
                              new JSONObject()
                                  .put("address", contract)
                                  .put("transactionHash", HASH)
                                  .put("blockNumber", "0x100")
                                  .put("blockHash", BLOCK)
                                  .put("removed", removed)
                                  .put(
                                      "data",
                                      "0x" + EvmPublicApi.word(BigInteger.valueOf(3_000_000)))
                                  .put(
                                      "topics",
                                      new JSONArray()
                                          .put(SpotUsdcApi.TRANSFER)
                                          .put("0x" + EvmPublicApi.addressWord(SpotTradeApi.VAULT))
                                          .put("0x" + EvmPublicApi.addressWord(destination)))));
          break;
        default:
          throw new AssertionError(r.getString("method"));
      }
      return new JSONObject().put("jsonrpc", "2.0").put("id", 1).put("result", value).toString();
    }
  }

  @Test
  public void receiptRequiresCorrectTokenRecipientCanonicalBlockAndFreshRequest() throws Exception {
    ReceiptFixture f = new ReceiptFixture();
    long now = System.currentTimeMillis() / 1000;
    SpotUsdcApi.Receipt r = new SpotUsdcApi(f).receipt(HASH, eth, now);
    assertEquals(BigInteger.valueOf(3_000_000), r.received);
    assertTrue(r.safe);
    assertFalse(r.finalized);
    for (int i = 0; i < 9; i++) {
      ReceiptFixture bad = new ReceiptFixture();
      switch (i) {
        case 0:
          bad.chain = "0x1";
          break;
        case 1:
          bad.canonical = "0x" + "56".repeat(32);
          break;
        case 2:
          bad.contract = DeFiApi.USDC;
          break;
        case 3:
          bad.destination = DeFiApi.USDC;
          break;
        case 4:
          bad.status = "0x0";
          break;
        case 5:
          bad.hash = "0x" + "56".repeat(32);
          break;
        case 6:
          bad.removed = true;
          break;
        case 7:
          bad.timestamp = now - 60;
          break;
        case 8:
          bad.timestamp = now + 3600;
          break;
      }
      assertThrows(
          "receipt mutation " + i,
          IllegalArgumentException.class,
          () -> new SpotUsdcApi(bad).receipt(HASH, eth, now));
    }
  }

  static JSONObject market() throws Exception {
    try (java.io.InputStream in = SpotTradeTest.class.getResourceAsStream("/spot-market.json")) {
      assertNotNull(in);
      return new JSONObject(new String(in.readAllBytes(), StandardCharsets.UTF_8));
    }
  }

  static String eth = "0x9858effd232b4033e47d90003d41ec34ecaeda94";

  @Test
  public void liveMarketUnitsAndSideFeesAreRequired() throws Exception {
    JSONObject sample = market();
    SpotTradeApi.Market m = new SpotTradeApi.Market(sample);
    assertEquals(8, m.buyFee);
    assertEquals(0, m.sellFee);
    assertEquals(new BigInteger("500000"), m.usdcWithdrawal);
    sample
        .getJSONArray("markets")
        .getJSONObject(0)
        .getJSONObject("quote_asset")
        .put("contract", DeFiApi.USDC);
    assertThrows(IllegalArgumentException.class, () -> new SpotTradeApi.Market(sample));
    JSONObject changed = market();
    changed
        .getJSONArray("markets")
        .getJSONObject(0)
        .getJSONObject("base_asset")
        .put("chain", "eth");
    assertThrows(IllegalArgumentException.class, () -> new SpotTradeApi.Market(changed));
    JSONObject fees = market();
    fees.getJSONArray("markets").getJSONObject(0).getJSONObject("fees").put("sell_fee_bps", 0.5);
    assertThrows(IllegalArgumentException.class, () -> new SpotTradeApi.Market(fees));
  }

  @Test
  public void orderAmountsAreExactAndNeverRoundedIntoLots() throws Exception {
    assertEquals(new BigInteger("123000000"), SpotTradeApi.qty("1.23"));
    assertEquals(new BigInteger("2160000"), SpotTradeApi.price("2.16"));
    for (String q : new String[] {"0.99", "1.001", "1e3", "1.000000001"})
      assertThrows(IllegalArgumentException.class, () -> SpotTradeApi.qty(q));
    for (String p : new String[] {"0", "2.160001", "1e3", "1,000"})
      assertThrows(IllegalArgumentException.class, () -> SpotTradeApi.price(p));
    assertEquals(
        new BigInteger("12096000"),
        SpotTradeTools.notional(new BigInteger("560000000"), new BigInteger("2160000"), false));
    assertEquals(BigInteger.ONE, SpotTradeTools.fee(BigInteger.ONE, 8));
  }

  @Test
  public void nativeSpotBookUsesMicroUsdcAndGrains() throws Exception {
    SpotTradeApi api =
        new SpotTradeApi(
            (m, p, b) ->
                "{\"market\":\"PRL\",\"bids\":[{\"price\":\"2160000\",\"qtySats\":\"100000000\"},{\"price\":\"2000000\",\"qtySats\":\"100000000\"}],\"asks\":[{\"price\":\"3000000\",\"qtySats\":\"100000000\"}]}");
    assertEquals(
        0,
        api.book()
            .fill(new java.math.BigDecimal("2"), false)
            .quote
            .compareTo(new java.math.BigDecimal("4.16")));
    assertThrows(
        IllegalArgumentException.class,
        () -> api.book().fill(new java.math.BigDecimal("3"), false));
  }

  @Test
  public void duplicateAndIncompleteBalancesFailClosed() throws Exception {
    JSONObject valid =
        new JSONObject(
            "{\"balances\":[{\"asset\":\"PRL\",\"available\":\"100000000\",\"reserved\":\"0\"},{\"asset\":\"USDC-ARB\",\"available\":\"5000000\",\"reserved\":\"0\"}]}");
    assertEquals(new BigInteger("5000000"), new SpotTradeApi.Balances(valid).usdc);
    valid.getJSONArray("balances").put(valid.getJSONArray("balances").getJSONObject(0));
    assertThrows(IllegalArgumentException.class, () -> new SpotTradeApi.Balances(valid));
  }

  static JSONObject withdrawal() throws Exception {
    return new JSONObject()
        .put("id", 641)
        .put("eth_address", eth)
        .put("asset", "USDC-ARB")
        .put("dest_address", eth)
        .put("amount_units", "13756000")
        .put("fee_units", "500000");
  }

  @Test
  public void withdrawalResponseContainsNetPlusSeparateFee() throws Exception {
    JSONObject p =
        new JSONObject()
            .put("asset", "USDC-ARB")
            .put("destination", eth)
            .put("quantity", "14256000");
    SpotTradeTools.matchWithdrawal(withdrawal(), eth, p);
    p.put("quantity", "13756000");
    assertThrows(
        IllegalArgumentException.class, () -> SpotTradeTools.matchWithdrawal(withdrawal(), eth, p));
  }

  @Test
  public void returnedOrderMustBelongToTheSignedAccountAndTerms() throws Exception {
    JSONObject p =
        new JSONObject().put("side", "sell").put("quantity", "560000000").put("price", "2160000");
    JSONObject o =
        new JSONObject()
            .put("id", 3038666)
            .put("eth_address", eth)
            .put("market", "PRL")
            .put("side", "sell")
            .put("qty_sats", "560000000")
            .put("filled_sats", "100000000")
            .put("price_micro_per_prl", 2160000)
            .put("status", "open");
    SpotTradeTools.matchOrder(o, eth, p);
    o.put("price_micro_per_prl", 2000000);
    assertThrows(IllegalArgumentException.class, () -> SpotTradeTools.matchOrder(o, eth, p));
  }

  @Test
  public void onlyCuratedPublicEndpointsAndReadOnlyArbitrumRpcAreAllowed() throws Exception {
    SpotTradeApi.validateRoute("GET", "/v1/orders?user=" + eth, null);
    assertThrows(
        IllegalArgumentException.class, () -> SpotTradeApi.validateRoute("POST", "/v1/chat", "{}"));
    assertThrows(
        IllegalArgumentException.class,
        () -> SpotTradeApi.validateRoute("GET", "https://example.com/", null));
    JSONObject r =
        new JSONObject()
            .put("jsonrpc", "2.0")
            .put("id", 1)
            .put("method", "eth_sendRawTransaction")
            .put("params", new JSONArray().put("0x02"));
    assertThrows(IllegalArgumentException.class, () -> SpotUsdcApi.validate(r));
  }
}
