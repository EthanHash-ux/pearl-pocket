package ai.pearl.wallet;

import static org.junit.Assert.*;

import java.math.*;
import java.util.*;
import org.json.*;
import org.junit.Test;

public class CrossChainTest {
  @Test
  public void bridgeRechecksIdenticalTermsAndBlocksFeeChangesBeforeAuth() throws Exception {
    final boolean[] changed = {false};
    List<String> urls = new ArrayList<>();
    BridgeApi api =
        new BridgeApi(
            url -> {
              urls.add(url);
              if (url.endsWith("status")) return status().toString();
              if (url.contains("deposit-address"))
                return new JSONObject()
                    .put("ethAddress", EvmPublicApi.USDT)
                    .put("pearlAddress", PEARL)
                    .toString();
              JSONObject q = quote(true);
              if (changed[0]) q.put("feeGrains", "400000001").put("netGrains", "9599999999");
              return q.toString();
            });
    BridgeBook b = new BridgeBook(new NextFeaturesTest.Memory());
    BridgeApi.MintPlan plan = api.prepare(AMOUNT, EvmPublicApi.USDT, b);
    assertTrue(plan.firstUse);
    api.recheck(plan, b);
    changed[0] = true;
    assertThrows(IllegalArgumentException.class, () -> api.recheck(plan, b));
    assertTrue(urls.stream().allMatch(u -> u.startsWith(BridgeApi.BASE)));
    assertFalse(urls.stream().anyMatch(u -> u.contains("entropy") || u.contains("mnemonic")));
  }

  @Test
  public void lighterPinsPerpIdentityAndRejectsSpotInactiveAndWrongSize() throws Exception {
    JSONObject row =
        new JSONObject()
            .put("market_id", 4097)
            .put("symbol", "PRL")
            .put("market_type", "perp")
            .put("status", "active")
            .put("is_frozen", false)
            .put("market_config", new JSONObject().put("force_reduce_only", false))
            .put("mark_price", "12")
            .put("index_price", "12")
            .put("last_trade_price", "12")
            .put("min_base_amount", "5")
            .put("min_quote_amount", "10")
            .put("supported_size_decimals", 2)
            .put("default_initial_margin_fraction", 3333);
    JSONObject
        meta =
            new JSONObject().put("code", 200).put("order_book_details", new JSONArray().put(row)),
        depth =
            new JSONObject()
                .put("code", 200)
                .put("bids", levels("remaining_base_amount", "11", "100"))
                .put("asks", levels("remaining_base_amount", "12", "100"));
    LighterMarket m = new LighterMarket(meta, depth);
    m.shortFill(new BigDecimal("5.01"));
    assertThrows(IllegalArgumentException.class, () -> m.shortFill(new BigDecimal("5.001")));
    assertThrows(IllegalArgumentException.class, () -> m.shortFill(new BigDecimal("4")));
    row.put("market_type", "spot");
    assertThrows(IllegalArgumentException.class, () -> new LighterMarket(meta, depth));
    row.put("market_type", "perp").put("is_frozen", true);
    assertThrows(IllegalArgumentException.class, () -> new LighterMarket(meta, depth));
    row.put("is_frozen", false).put("market_id", 1);
    assertThrows(IllegalArgumentException.class, () -> new LighterMarket(meta, depth));
  }

  static final String PEARL = NextFeaturesTest.A;
  static final BigInteger AMOUNT = new BigInteger("10000000000");
  static final BigDecimal ZERO = BigDecimal.ZERO, ONE = BigDecimal.ONE;

  static JSONObject status() throws Exception {
    return new JSONObject()
        .put("paused", false)
        .put("decimals", 8)
        .put("timestamp", System.currentTimeMillis())
        .put(
            "contracts",
            new JSONObject()
                .put("network", "mainnet")
                .put("wprl", EvmPublicApi.WPRL)
                .put("bridgeController", EvmPublicApi.CONTROLLER))
        .put("confirmations", new JSONObject().put("pearlMinConfirmations", 6))
        .put(
            "limits",
            new JSONObject()
                .put("mintWindowRemainingGrains", "100000000000")
                .put("burnWindowRemainingGrains", "100000000000")
                .put("fastMintWindowRemainingGrains", "100000000000")
                .put("slowMintDelaySeconds", 86400));
  }

  static JSONObject quote(boolean mint) throws Exception {
    JSONObject o =
        new JSONObject()
            .put("direction", mint ? "mint" : "burn")
            .put("amountGrains", AMOUNT.toString())
            .put("feeGrains", mint ? "400000000" : "50000000")
            .put("netGrains", mint ? "9600000000" : "9950000000")
            .put("paused", false)
            .put("withinDailyCap", true)
            .put("timestamp", System.currentTimeMillis());
    if (mint) o.put("lane", "fast").put("slowLaneDelaySeconds", 0).put("confirmationsRequired", 6);
    else
      o.put(
          "transaction",
          new JSONObject()
              .put("chainId", 1)
              .put(
                  "steps",
                  new JSONArray()
                      .put(new JSONObject().put("to", EvmPublicApi.WPRL))
                      .put(new JSONObject().put("to", EvmPublicApi.CONTROLLER))));
    return o;
  }

  @Test
  public void bridgeIdentityPinsNetworkContractAndEightDecimals() throws Exception {
    new BridgeApi.Status(status(), System.currentTimeMillis());
    JSONObject o = status().put("decimals", 18);
    assertThrows(
        IllegalArgumentException.class, () -> new BridgeApi.Status(o, System.currentTimeMillis()));
    JSONObject x = status();
    x.getJSONObject("contracts").put("network", "sepolia");
    assertThrows(
        IllegalArgumentException.class, () -> new BridgeApi.Status(x, System.currentTimeMillis()));
    JSONObject y = status();
    y.getJSONObject("contracts").put("wprl", EvmPublicApi.USDT);
    assertThrows(
        IllegalArgumentException.class, () -> new BridgeApi.Status(y, System.currentTimeMillis()));
  }

  @Test
  public void bridgeRejectsStaleFutureAndStringBoolean() throws Exception {
    JSONObject o = status().put("timestamp", System.currentTimeMillis() - 91000);
    assertThrows(
        IllegalArgumentException.class, () -> new BridgeApi.Status(o, System.currentTimeMillis()));
    JSONObject x = status().put("timestamp", System.currentTimeMillis() + 31000);
    assertThrows(
        IllegalArgumentException.class, () -> new BridgeApi.Status(x, System.currentTimeMillis()));
    JSONObject y = status().put("paused", "false");
    assertThrows(
        IllegalArgumentException.class, () -> new BridgeApi.Status(y, System.currentTimeMillis()));
    JSONObject z = status();
    z.getJSONObject("limits").remove("slowMintDelaySeconds");
    assertThrows(JSONException.class, () -> new BridgeApi.Status(z, System.currentTimeMillis()));
  }

  @Test
  public void bridgeQuotesPreserveEveryGrainAndRejectWrongTotals() throws Exception {
    BridgeApi.Quote q = new BridgeApi.Quote(quote(true), true, AMOUNT, System.currentTimeMillis());
    assertEquals(new BigInteger("9600000000"), q.net);
    JSONObject o = quote(true).put("netGrains", "9600000001");
    assertThrows(
        IllegalArgumentException.class,
        () -> new BridgeApi.Quote(o, true, AMOUNT, System.currentTimeMillis()));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new BridgeApi.Quote(
                quote(true), true, AMOUNT.add(BigInteger.ONE), System.currentTimeMillis()));
    JSONObject x = quote(true).put("feeGrains", 400000000L);
    assertThrows(
        IllegalArgumentException.class,
        () -> new BridgeApi.Quote(x, true, AMOUNT, System.currentTimeMillis()));
  }

  @Test
  public void bridgeUnknownLanePausedMissingCapAndChangedTermsBlock() throws Exception {
    JSONObject o = quote(true).put("lane", "future");
    assertThrows(
        IllegalArgumentException.class,
        () -> new BridgeApi.Quote(o, true, AMOUNT, System.currentTimeMillis()));
    BridgeApi.Quote paused =
        new BridgeApi.Quote(
            quote(true).put("paused", true), true, AMOUNT, System.currentTimeMillis());
    assertThrows(IllegalArgumentException.class, paused::available);
    BridgeApi.Quote cap =
        new BridgeApi.Quote(
            quote(true).put("withinDailyCap", false), true, AMOUNT, System.currentTimeMillis());
    assertThrows(IllegalArgumentException.class, cap::available);
    JSONObject x = quote(true);
    x.remove("withinDailyCap");
    assertThrows(
        JSONException.class,
        () -> new BridgeApi.Quote(x, true, AMOUNT, System.currentTimeMillis()));
    BridgeApi.Quote a = new BridgeApi.Quote(quote(true), true, AMOUNT, System.currentTimeMillis()),
        b =
            new BridgeApi.Quote(
                quote(true).put("lane", "slow").put("slowLaneDelaySeconds", 86400),
                true,
                AMOUNT,
                System.currentTimeMillis());
    assertFalse(a.sameTerms(b));
  }

  @Test
  public void bridgeBurnRejectsWrongChainAndController() throws Exception {
    JSONObject o = quote(false);
    o.getJSONObject("transaction").put("chainId", 10);
    assertThrows(
        IllegalArgumentException.class,
        () -> new BridgeApi.Quote(o, false, AMOUNT, System.currentTimeMillis()));
    JSONObject x = quote(false);
    x.getJSONObject("transaction")
        .getJSONArray("steps")
        .getJSONObject(1)
        .put("to", EvmPublicApi.USDT);
    assertThrows(
        IllegalArgumentException.class,
        () -> new BridgeApi.Quote(x, false, AMOUNT, System.currentTimeMillis()));
  }

  @Test
  public void depositPinsPersistAndNeverSilentlyChange() throws Exception {
    NextFeaturesTest.Memory memory = new NextFeaturesTest.Memory();
    BridgeBook b = new BridgeBook(memory);
    assertTrue(b.pin(EvmPublicApi.WPRL, PEARL));
    assertFalse(new BridgeBook(memory).pin(EvmPublicApi.WPRL, PEARL));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            b.pin(
                EvmPublicApi.WPRL,
                PearlAddress.normalize(
                    "prl1p5f9qdh3h3zmny9qkaeef0g9darcylucjdf85m8kee9cuuql6jxwqpe02fx")));
    memory.fail = true;
    assertThrows(IllegalStateException.class, () -> b.pin(EvmPublicApi.USDT, PEARL));
  }

  @Test
  public void transferRecordPersistsDeduplicatesAndRejectsInjectedHash() throws Exception {
    NextFeaturesTest.Memory m = new NextFeaturesTest.Memory();
    BridgeBook b = new BridgeBook(m);
    b.save(true, "ab".repeat(32), EvmPublicApi.WPRL);
    b.save(true, "ab".repeat(32), EvmPublicApi.WPRL);
    assertEquals(1, new BridgeBook(m).list().size());
    assertThrows(
        IllegalArgumentException.class, () -> b.save(true, "../status", EvmPublicApi.WPRL));
    assertThrows(IllegalArgumentException.class, () -> b.save(false, "ab".repeat(32), ""));
    assertEquals("0x" + "cd".repeat(32), BridgeBook.hash(false, "0x" + "CD".repeat(32)));
    m.fail = true;
    assertThrows(IllegalStateException.class, () -> b.save(false, "0x" + "cd".repeat(32), ""));
  }

  @Test
  public void bridgeProgressHandlesRefundFailureAndUnknownWithoutSuccess() {
    assertTrue(new BridgeApi.Transfer("new_future_state", "", false).label(true).contains("未知"));
    assertTrue(new BridgeApi.Transfer("minted", "", true).label(true).contains("退款"));
    assertTrue(new BridgeApi.Transfer("submitted", "", false).label(false).contains("处理中"));
    assertTrue(new BridgeApi.Transfer("failed", "", false).label(true).contains("失败"));
  }

  static JSONObject rpc(String method, JSONArray p) throws Exception {
    return new JSONObject()
        .put("jsonrpc", "2.0")
        .put("id", 1)
        .put("method", method)
        .put("params", p);
  }

  static JSONArray call(String to, String data) throws Exception {
    return new JSONArray().put(new JSONObject().put("to", to).put("data", data)).put("0x123");
  }

  @Test
  public void ethereumTransportCannotSendSignApproveOrCallArbitraryContracts() throws Exception {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            EvmPublicApi.validateRequest(
                rpc("eth_sendRawTransaction", new JSONArray().put("0x00"))));
    assertThrows(
        IllegalArgumentException.class,
        () -> EvmPublicApi.validateRequest(rpc("personal_sign", new JSONArray())));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            EvmPublicApi.validateRequest(
                rpc("eth_call", call(EvmPublicApi.WPRL, "0x095ea7b3" + "0".repeat(128)))));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            EvmPublicApi.validateRequest(
                rpc("eth_call", call(EvmPublicApi.CONTROLLER, "0x313ce567"))));
    EvmPublicApi.validateRequest(
        rpc(
            "eth_call",
            call(EvmPublicApi.WPRL, "0x70a08231" + EvmPublicApi.addressWord(EvmPublicApi.USDT))));
  }

  @Test
  public void ethereumBalanceUsesOneMainnetBlockAndEightDecimals() throws Exception {
    List<String> calls = new ArrayList<>();
    EvmPublicApi api =
        new EvmPublicApi(
            body -> {
              JSONObject r = new JSONObject(body);
              calls.add(body);
              String method = r.getString("method"), value;
              if (method.equals("eth_chainId")) value = "0x1";
              else if (method.equals("eth_blockNumber")) value = "0x123";
              else if (method.equals("eth_getBalance")) value = "0xde0b6b3a7640000";
              else {
                String data = r.getJSONArray("params").getJSONObject(0).getString("data");
                value =
                    "0x"
                        + EvmPublicApi.word(
                            data.equals("0x313ce567")
                                ? BigInteger.valueOf(8)
                                : new BigInteger("100000001"));
                assertEquals("0x123", r.getJSONArray("params").getString(1));
              }
              return new JSONObject()
                  .put("jsonrpc", "2.0")
                  .put("id", 1)
                  .put("result", value)
                  .toString();
            });
    EvmPublicApi.Balance b = api.balance(EvmPublicApi.USDT);
    assertEquals("1.00000001", PearlAmount.format(b.grains));
    assertEquals(new BigInteger("1000000000000000000"), b.wei);
    assertEquals(5, calls.size());
  }

  @Test
  public void ethereumRefusesNonMainnetRpcAndZeroAddress() throws Exception {
    EvmPublicApi api =
        new EvmPublicApi(body -> "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":\"0xa\"}");
    assertThrows(IllegalArgumentException.class, api::block);
    assertThrows(IllegalArgumentException.class, () -> EvmPublicApi.address("0x" + "0".repeat(40)));
  }

  @Test
  public void dexTuplePreservesTokenOrderGrainsAndUsdtSixDecimals() throws Exception {
    List<String> selectors = new ArrayList<>();
    EvmPublicApi api =
        new EvmPublicApi(
            body -> {
              JSONObject r = new JSONObject(body);
              String data = r.getJSONArray("params").getJSONObject(0).getString("data");
              selectors.add(data.substring(0, 10));
              boolean sell = data.startsWith("0xc6a5026a");
              assertEquals(
                  EvmPublicApi.addressWord(sell ? EvmPublicApi.WPRL : EvmPublicApi.USDT),
                  data.substring(10, 74));
              assertEquals(EvmPublicApi.word(AMOUNT), data.substring(138, 202));
              return new JSONObject()
                  .put("jsonrpc", "2.0")
                  .put("id", 1)
                  .put(
                      "result",
                      "0x"
                          + EvmPublicApi.word(BigInteger.valueOf(120000001))
                          + EvmPublicApi.word(BigInteger.ONE.shiftLeft(96))
                          + EvmPublicApi.word(BigInteger.ONE)
                          + EvmPublicApi.word(BigInteger.valueOf(100000)))
                  .toString();
            });
    assertEquals(new BigDecimal("120.000001"), api.quote(AMOUNT, true, "0x123").usdt);
    api.quote(AMOUNT, false, "0x123");
    assertEquals(List.of("0xc6a5026a", "0xbd21704a"), selectors);
  }

  @Test
  public void dexRejectsMalformedResponseAndBoundaryPartialFill() throws Exception {
    EvmPublicApi shortResponse =
        new EvmPublicApi(body -> "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":\"0x01\"}");
    assertThrows(IllegalArgumentException.class, () -> shortResponse.quote(AMOUNT, true, "0x123"));
    EvmPublicApi boundary =
        new EvmPublicApi(
            body ->
                new JSONObject()
                    .put("jsonrpc", "2.0")
                    .put("id", 1)
                    .put(
                        "result",
                        "0x"
                            + EvmPublicApi.word(BigInteger.ONE)
                            + EvmPublicApi.word(new BigInteger("4295128740"))
                            + EvmPublicApi.word(BigInteger.ONE)
                            + EvmPublicApi.word(BigInteger.ONE))
                    .toString());
    assertThrows(IllegalArgumentException.class, () -> boundary.quote(AMOUNT, true, "0x123"));
  }

  static JSONArray levels(String size, String... values) throws Exception {
    JSONArray a = new JSONArray();
    for (int i = 0; i < values.length; i += 2)
      a.put(new JSONObject().put("price", values[i]).put(size, values[i + 1]));
    return a;
  }

  @Test
  public void depthWalkCalculatesFullWeightedAmountAndRefusesExtrapolation() throws Exception {
    TradeBook b =
        new TradeBook(
            levels("quantity", "9", "5", "8", "5"),
            levels("quantity", "10", "5", "12", "5"),
            "quantity");
    assertEquals(0, b.fill(BigDecimal.TEN, true).quote.compareTo(new BigDecimal("110")));
    assertEquals(0, b.fill(BigDecimal.TEN, false).quote.compareTo(new BigDecimal("85")));
    assertEquals(0, b.fill(BigDecimal.TEN, true).slippagePercent.compareTo(new BigDecimal("10")));
    assertThrows(IllegalArgumentException.class, () -> b.fill(new BigDecimal("11"), true));
  }

  @Test
  public void depthRejectsCrossedUnsortedZeroAndDuplicateOrders() throws Exception {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new TradeBook(levels("quantity", "10", "1"), levels("quantity", "9", "1"), "quantity"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new TradeBook(
                levels("quantity", "8", "1", "9", "1"), levels("quantity", "10", "1"), "quantity"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new TradeBook(levels("quantity", "8", "0"), levels("quantity", "10", "1"), "quantity"));
    JSONArray a = levels("remaining_base_amount", "8", "1", "7", "1");
    a.getJSONObject(0).put("order_id", "id");
    a.getJSONObject(1).put("order_id", "id");
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new TradeBook(a, levels("remaining_base_amount", "10", "1"), "remaining_base_amount"));
  }

  @Test
  public void crossChainNetAccountsForGasReserveAndFees() {
    BigDecimal n =
        ArbitrageMath.bridgeNet(
            new BigDecimal("100.2"),
            new BigDecimal("115.2"),
            new BigDecimal("0.01"),
            new BigDecimal("5"),
            true);
    assertEquals(0, n.compareTo(new BigDecimal("8.848")));
    assertTrue(
        ArbitrageMath.bridgeNet(
                    new BigDecimal("100"), new BigDecimal("101"), new BigDecimal("0.01"), ONE, true)
                .signum()
            < 0);
  }

  private ArbitrageMath.Hedge hedge(BigDecimal funding, BigDecimal rate) {
    return ArbitrageMath.hedge(
        new BigDecimal("100"),
        new BigDecimal("110"),
        new BigDecimal("120"),
        new BigDecimal("1.1"),
        ONE,
        ZERO,
        ZERO,
        ZERO,
        funding,
        10,
        ONE,
        ZERO,
        3333,
        rate);
  }

  @Test
  public void hedgeSeparatesEntryBasisFromExitPnlAndNegativeFunding() {
    assertEquals(0, hedge(new BigDecimal("0.001"), ONE).pnl.compareTo(new BigDecimal("10.1")));
    assertEquals(0, hedge(new BigDecimal("-0.001"), ONE).pnl.compareTo(new BigDecimal("7.9")));
    assertEquals(0, hedge(ZERO, ONE).entryBasis.compareTo(BigDecimal.TEN));
  }

  @Test
  public void hedgeDoesNotSilentlyEquateUsdcAndUsdt() {
    assertEquals(
        0,
        hedge(ZERO, new BigDecimal("0.9"))
            .pnl
            .setScale(8, RoundingMode.HALF_EVEN)
            .compareTo(new BigDecimal("-3.00000000")));
  }

  @Test
  public void costInputsRequireExplicitBudgetRejectNanAndSubgrainPrecision() {
    assertThrows(IllegalArgumentException.class, () -> ArbitrageMath.number("", ZERO, ONE));
    assertThrows(IllegalArgumentException.class, () -> ArbitrageMath.percent("NaN"));
    assertThrows(IllegalArgumentException.class, () -> ArbitrageMath.percent("-1"));
    assertThrows(IllegalArgumentException.class, () -> ArbitrageMath.percent("0.000000001"));
    assertEquals(new BigDecimal("0.002"), ArbitrageMath.percent("0.2"));
  }
}
