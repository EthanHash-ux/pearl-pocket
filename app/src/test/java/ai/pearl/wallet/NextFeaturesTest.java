package ai.pearl.wallet;

import static org.junit.Assert.*;

import java.math.*;
import java.util.*;
import org.json.*;
import org.junit.Test;

public class NextFeaturesTest {
  static final String A = "prl1pr6yuq8u2r95wjzzgpdy8cpnncpl7l8zgy6x5q0367pnc53s2famqg7pt74",
      ID = "ab".repeat(32);

  static class Memory implements PublicStore.Storage {
    Map<String, String> data = new HashMap<>();
    boolean fail;

    public String read(String k) {
      return data.getOrDefault(k, "[]");
    }

    public void write(String k, String v) {
      if (fail) throw new IllegalStateException("disk failure");
      data.put(k, v);
    }
  }

  @Test
  public void watchAndMinerBooksPersistSeparatelyAndRejectDuplicateBadAddresses() throws Exception {
    Memory m = new Memory();
    WatchBook w = new WatchBook(m), miners = new WatchBook(m, "miner_addresses");
    w.save("Watch", A.toUpperCase(Locale.ROOT));
    assertEquals(A, new WatchBook(m).list().get(0).address);
    assertTrue(miners.list().isEmpty());
    miners.save("Rig", A);
    w.remove(A);
    assertEquals(1, miners.list().size());
    assertThrows(IllegalArgumentException.class, () -> miners.save("Dup", A));
    assertThrows(IllegalArgumentException.class, () -> w.save("Bad", "prl1pbad"));
  }

  @Test
  public void ledgerSeparatesAddressesEditsDeletesAndExportsExactSafeCsv() throws Exception {
    Memory m = new Memory();
    TransactionLedger l = new TransactionLedger(m);
    l.note(A, ID, "=cmd(\"x\")");
    List<PearlApi.Transaction> tx =
        List.of(new PearlApi.Transaction(ID, new BigInteger("-100000001"), 100, 2));
    String csv = l.csv(A, tx);
    assertTrue(csv.contains("\"-1.00000001\""));
    assertTrue(csv.contains("\"'=cmd(\"\"x\"\")\""));
    assertFalse(csv.contains("1.000000010"));
    l.note(A, ID, "updated");
    assertEquals("updated", new TransactionLedger(m).note(A.toUpperCase(Locale.ROOT), ID));
    l.note(A, ID, "");
    assertEquals("", l.note(A, ID));
    assertThrows(IllegalArgumentException.class, () -> l.note(A, ID, "bad\nname"));
    assertThrows(IllegalArgumentException.class, () -> l.note(A, "bad", "x"));
  }

  @Test
  public void ledgerFiltersNotesCounterpartyExactAmountDirectionAndDateBounds() throws Exception {
    TransactionLedger l = new TransactionLedger(new Memory());
    l.note(A, ID, "Merchant order");
    PearlApi.Transaction t =
        new PearlApi.Transaction(ID, new BigInteger("100000001"), 100, 1, "PublicCounterparty");
    assertTrue(l.matches(A, t, "ORDER", "received", 100, 101));
    assertTrue(l.matches(A, t, "counterparty", "all", 0, 0));
    assertTrue(l.matches(A, t, "1.00000001", "all", 0, 0));
    assertFalse(l.matches(A, t, "", "sent", 0, 0));
    assertFalse(l.matches(A, t, "", "all", 0, 100));
  }

  @Test
  public void receiptInitialSnapshotIsSilentThenPendingAndConfirmedFireOnceAfterRestart()
      throws Exception {
    Memory m = new Memory();
    ReceiptTracker r = new ReceiptTracker(m);
    assertTrue(r.claim(A, List.of(), true, 100).isEmpty());
    PearlApi.Transaction pending = new PearlApi.Transaction(ID, BigInteger.ONE, 0, 0);
    assertEquals(1, r.claim(A, List.of(pending), true, 101).size());
    assertTrue(new ReceiptTracker(m).claim(A, List.of(pending), true, 102).isEmpty());
    PearlApi.Transaction confirmed = new PearlApi.Transaction(ID, BigInteger.ONE, 103, 1);
    assertTrue(r.claim(A, List.of(confirmed), true, 103).get(0).confirmed);
    assertTrue(r.claim(A, List.of(confirmed), true, 104).isEmpty());
    assertTrue(r.claim(A, List.of(pending), true, 105).isEmpty());
    assertTrue(r.claim(A, List.of(confirmed), true, 106).isEmpty());
  }

  @Test
  public void receiptDisabledAndFailedPersistenceDoNotConsumeEvent() throws Exception {
    Memory m = new Memory();
    ReceiptTracker r = new ReceiptTracker(m);
    assertTrue(r.claim(A, List.of(), false, 100).isEmpty());
    assertTrue(m.data.isEmpty());
    r.claim(A, List.of(), true, 100);
    PearlApi.Transaction t = new PearlApi.Transaction(ID, BigInteger.ONE, 101, 1);
    m.fail = true;
    assertThrows(IllegalStateException.class, () -> r.claim(A, List.of(t), true, 102));
    m.fail = false;
    assertEquals(1, r.claim(A, List.of(t), true, 103).size());
  }

  @Test
  public void receiptHistoricalAndOutgoingTransactionsDoNotNotify() throws Exception {
    ReceiptTracker r = new ReceiptTracker(new Memory());
    r.claim(A, List.of(), true, 100);
    assertTrue(
        r.claim(
                A,
                List.of(
                    new PearlApi.Transaction(ID, BigInteger.ONE, 99, 2),
                    new PearlApi.Transaction("cd".repeat(32), BigInteger.ONE.negate(), 101, 1)),
                true,
                102)
            .isEmpty());
  }

  @Test
  public void percentAlertsUseExactBaselineAndEditKeepsIdResetsState() throws Exception {
    Memory m = new Memory();
    PriceAlerts p = new PriceAlerts(m);
    PriceAlerts.Alert a = p.save(null, "10", true, "percent", new BigDecimal("2"));
    assertTrue(p.claim(new BigDecimal("2.19999999"), 100, 100, true).isEmpty());
    assertEquals(1, p.claim(new BigDecimal("2.2"), 101, 101, true).size());
    p.save(a.id, "20", false, "percent", new BigDecimal("3"));
    assertTrue(p.active());
    assertEquals(a.id, p.list().get(0).id);
    assertTrue(p.claim(new BigDecimal("2.40000001"), 102, 102, true).isEmpty());
    assertEquals(1, p.claim(new BigDecimal("2.4"), 103, 103, true).size());
    assertThrows(
        IllegalArgumentException.class,
        () -> p.save(null, "100", false, "percent", BigDecimal.ONE));
    assertThrows(IllegalArgumentException.class, () -> p.save(null, "10", true, "percent", null));
  }

  @Test
  public void legacyVersionFiveAlertLoadsWithoutNewFields() throws Exception {
    Memory m = new Memory();
    m.write(
        "alerts",
        "[{\"id\":\"legacy\",\"target\":\"2\",\"above\":true,\"enabled\":true,\"firedAt\":0}]");
    assertEquals(1, new PriceAlerts(m).claim(new BigDecimal("2"), 100, 100, true).size());
  }

  static String miner() throws Exception {
    return new JSONObject()
        .put(
            "stats",
            new JSONObject()
                .put("balance", "100000001")
                .put("paid", "200000000")
                .put("lastShare", 100)
                .put("hashrate", 123)
                .put("hashrate_24h", 100))
        .put(
            "workers",
            new JSONArray()
                .put(
                    new JSONObject().put("name", "Rig").put("hashrate", 123).put("lastShare", 100)))
        .put("payments", new JSONArray().put(ID + ":100000001:10").put(99))
        .put("unconfirmed", new JSONArray().put(new JSONObject().put("reward", "123")))
        .toString();
  }

  @Test
  public void minerParsesGrainsWorkersPaymentsAndNeverFabricatesUnknownZeros() throws Exception {
    MinerAccount a = MinerAccount.parse(miner(), 110);
    assertTrue(a.found);
    assertEquals(new BigInteger("100000001"), a.balance);
    assertEquals(new BigInteger("123"), a.pending);
    assertEquals(ID, a.payouts.get(0).id);
    assertEquals("Rig", a.workers.get(0).name);
    assertFalse(MinerAccount.parse("{\"error\":\"Not found\"}", 110).found);
    assertNull(MinerAccount.parse("{\"error\":\"Not found\"}", 110).balance);
    assertThrows(
        IllegalArgumentException.class,
        () -> MinerAccount.parse("{\"error\":\"Query failed\"}", 110));
    JSONObject bad = new JSONObject(miner());
    bad.getJSONObject("stats").put("balance", "-1");
    assertThrows(IllegalArgumentException.class, () -> MinerAccount.parse(bad.toString(), 110));
  }

  @Test
  public void historyPagingRequiresMatchingAddressPageAndSynchronizedMainnet() throws Exception {
    JSONObject status =
        new JSONObject(
            "{\"blockbook\":{\"coin\":\"Pearl\",\"decimals\":8,\"bestHeight\":10,\"inSync\":true,\"initialSync\":false},\"backend\":{\"chain\":\"mainnet\"}}");
    JSONObject page =
        new JSONObject()
            .put("address", A)
            .put("balance", "0")
            .put("unconfirmedBalance", "0")
            .put("txs", 26)
            .put("page", 2)
            .put("totalPages", 2)
            .put("transactions", new JSONArray());
    PearlApi api =
        new PearlApi(
            url -> {
              if (url.equals(PearlApi.BLOCKBOOK)) return status.toString();
              assertTrue(url.contains("page=2"));
              assertTrue(url.contains("pageSize=25"));
              return page.toString();
            });
    assertEquals(2, api.history(A, 2, 25).pages);
    page.put("page", 1);
    assertThrows(IllegalArgumentException.class, () -> api.history(A, 2, 25));
    page.put("page", 2).put("address", "wrong");
    assertThrows(IllegalArgumentException.class, () -> api.history(A, 2, 25));
    status.getJSONObject("blockbook").put("inSync", false);
    assertThrows(IllegalArgumentException.class, () -> api.history(A, 2, 25));
  }

  @Test
  public void minerLastShareStatesHaveExplicitTimeBoundaries() {
    assertEquals("15 分钟内有提交", MinerAccount.activity(100, 1000));
    assertEquals("超过 15 分钟未提交", MinerAccount.activity(100, 1001));
    assertEquals("时间异常", MinerAccount.activity(100, 99));
  }
}
