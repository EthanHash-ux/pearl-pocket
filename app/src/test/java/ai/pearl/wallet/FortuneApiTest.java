package ai.pearl.wallet;

import static org.junit.Assert.*;

import java.math.*;
import org.json.*;
import org.junit.Test;

public class FortuneApiTest {
  static final String ADDRESS = "prl1pr6yuq8u2r95wjzzgpdy8cpnncpl7l8zgy6x5q0367pnc53s2famqg7pt74";
  static final long NOW = 1791404400;

  private JSONObject window(int h) throws Exception {
    return new JSONObject()
        .put("hours", h)
        .put("hashrate", 999)
        .put("hashrate_str", "2.87 ExaMAC/s")
        .put(
            "share_statistics",
            new JSONObject()
                .put("from", NOW - h * 3600)
                .put("to", NOW)
                .put("observed_ms", h * 3600000L)
                .put("effective_hashrate", "2870000000000000000")
                .put("accepted_count", "9007199254740993")
                .put("rejected_count", "1")
                .put("dropped_events", "0")
                .put("complete", true));
  }

  private JSONObject miner() throws Exception {
    return new JSONObject()
        .put("chain", "pearl")
        .put("miner_address", FortuneApi.mask(ADDRESS))
        .put("generated_at", NOW)
        .put(
            "hourly_shares",
            new JSONObject()
                .put("chain_name", "pearl")
                .put("miner_address", FortuneApi.mask(ADDRESS))
                .put(
                    "rolling_hashrates",
                    new JSONArray().put(window(1)).put(window(8)).put(window(24))));
  }

  private String envelope(JSONObject d) throws Exception {
    return new JSONObject().put("data", d).toString();
  }

  private FortuneApi.Snapshot parse(JSONObject d) throws Exception {
    return FortuneApi.parseMiner(envelope(d), ADDRESS, NOW);
  }

  @Test
  public void estimatedContributionKeepsProviderUnitAndExactLargeCounters() throws Exception {
    FortuneApi.Snapshot s = parse(miner());
    assertEquals("2.87 EMAC/s", s.window(1).display());
    assertEquals("100.00%", s.window(1).acceptance());
    assertEquals(new BigInteger("9007199254740993"), s.window(1).accepted);
    assertEquals(new BigDecimal("2870000000000000000"), s.window(1).rate);
    assertEquals(
        "H/s",
        parse(
                miner()
                    .put(
                        "hourly_shares",
                        new JSONObject()
                            .put("chain_name", "pearl")
                            .put("miner_address", ADDRESS)
                            .put(
                                "rolling_hashrates",
                                new JSONArray().put(window(1).put("hashrate_str", "2.87 EH/s")))))
            .window(1)
            .unit);
  }

  @Test
  public void missingRateNeverFallsBackToRawNumberOrZero() throws Exception {
    JSONObject d = miner();
    d.getJSONObject("hourly_shares")
        .getJSONArray("rolling_hashrates")
        .getJSONObject(0)
        .getJSONObject("share_statistics")
        .put("effective_hashrate", JSONObject.NULL);
    assertNull(parse(d).window(1).rate);
    assertEquals("—", parse(d).window(1).display());
    assertNotNull(parse(d).window(8).rate);
  }

  @Test
  public void incompleteDroppedOrShortWindowsStayUnknown() throws Exception {
    for (String fault : new String[] {"complete", "dropped_events", "observed_ms"}) {
      JSONObject d = miner(),
          s =
              d.getJSONObject("hourly_shares")
                  .getJSONArray("rolling_hashrates")
                  .getJSONObject(0)
                  .getJSONObject("share_statistics");
      s.put(fault, fault.equals("complete") ? false : fault.equals("dropped_events") ? "1" : 1000);
      assertNull(parse(d).window(1).rate);
      assertEquals("—", parse(d).window(1).acceptance());
    }
  }

  @Test
  public void observedZeroIsKnownButNoSharesIsNotHundredPercent() throws Exception {
    JSONObject d = miner(),
        s =
            d.getJSONObject("hourly_shares")
                .getJSONArray("rolling_hashrates")
                .getJSONObject(0)
                .getJSONObject("share_statistics");
    s.put("effective_hashrate", 0).put("accepted_count", "0").put("rejected_count", "0");
    assertEquals("0.00 MAC/s", parse(d).window(1).display());
    assertEquals("—", parse(d).window(1).acceptance());
  }

  @Test
  public void wrongAddressMaskChainFreshnessAndUnitAreRejected() throws Exception {
    assertThrows(Exception.class, () -> parse(miner().put("miner_address", "prl1other...wrong")));
    assertThrows(Exception.class, () -> parse(miner().put("chain", "nock-ai")));
    assertThrows(Exception.class, () -> parse(miner().put("generated_at", NOW - 601)));
    assertThrows(Exception.class, () -> parse(miner().put("generated_at", NOW + 61)));
    JSONObject d = miner();
    d.getJSONObject("hourly_shares").put("chain_name", "ethereum");
    assertThrows(Exception.class, () -> parse(d));
    JSONObject bad = miner();
    bad.getJSONObject("hourly_shares")
        .getJSONArray("rolling_hashrates")
        .getJSONObject(0)
        .put("hashrate_str", "2.87 fake/s");
    assertThrows(Exception.class, () -> parse(bad));
  }

  @Test
  public void workersSelfReportDoesNotReplaceEstimatedRateAndCacheStripsNetworkIdentity()
      throws Exception {
    JSONObject connection =
        new JSONObject()
            .put("address", FortuneApi.mask(ADDRESS))
            .put("configured", true)
            .put("online", true)
            .put(
                "workers",
                new JSONArray()
                    .put(
                        new JSONObject()
                            .put("worker", "rig-01")
                            .put("reported_hashrate", 123000)
                            .put("reported_gpus", 2)
                            .put("stale", false)
                            .put(
                                "last_stats_at",
                                java.time.Instant.ofEpochSecond(NOW - 10).toString())
                            .put("remote", "192.0.2.25")
                            .put("session_id", "private-session")));
    FortuneApi.Snapshot s = FortuneApi.withConnections(parse(miner()), envelope(connection), NOW);
    assertEquals(1, s.workers.size());
    assertFalse(s.workers.get(0).stale);
    assertEquals("2.87 EMAC/s", s.window(1).display());
    assertEquals("123.00 kH/s", FortuneApi.formatRate(s.workers.get(0).reported, "H/s"));
    assertFalse(s.cache().contains("192.0.2.25"));
    assertFalse(s.cache().contains("private-session"));
    FortuneApi.Snapshot restored = FortuneApi.fromCache(s.cache(), ADDRESS);
    assertEquals(s.window(24).display(), restored.window(24).display());
    assertEquals("rig-01", restored.workers.get(0).name);
    connection.put("address", "other");
    assertThrows(Exception.class, () -> FortuneApi.withConnections(s, envelope(connection), NOW));
  }

  @Test
  public void unconfiguredAndUnavailableConnectionsAreNotOfflineZero() throws Exception {
    FortuneApi.Snapshot s =
        FortuneApi.withConnections(
            parse(miner()),
            envelope(new JSONObject().put("address", ADDRESS).put("configured", false)),
            NOW);
    assertNull(s.online);
    assertEquals("连接状态未知", s.connections());
  }

  @Test
  public void workerOldReportsRemainDistinctFromOnlineConnection() throws Exception {
    JSONObject c =
        new JSONObject()
            .put("address", ADDRESS)
            .put("configured", true)
            .put("online", true)
            .put(
                "workers",
                new JSONArray()
                    .put(
                        new JSONObject()
                            .put("worker", "rig")
                            .put("stale", false)
                            .put(
                                "last_stats_at",
                                java.time.Instant.ofEpochSecond(NOW - 600).toString())));
    FortuneApi.Snapshot s = FortuneApi.withConnections(parse(miner()), envelope(c), NOW);
    assertTrue(s.online);
    assertTrue(s.workers.get(0).stale);
    assertNull(s.workers.get(0).reported);
  }

  @Test
  public void configAndAddressRouteValidateOnlyPublicPearlInputs() throws Exception {
    FortuneApi.validateConfig(
        envelope(
            new JSONObject()
                .put("chain", "pearl")
                .put("coin_symbol", "PRL")
                .put("atomic_units", 100000000)));
    assertThrows(
        Exception.class,
        () ->
            FortuneApi.validateConfig(
                envelope(
                    new JSONObject()
                        .put("chain", "pearl")
                        .put("coin_symbol", "PRL")
                        .put("atomic_units", 65536))));
    assertEquals("https://pearlfortune.org/#miner=" + ADDRESS, FortuneApi.website(ADDRESS));
    assertThrows(Exception.class, () -> FortuneApi.website("https://evil.invalid"));
  }

  @Test
  public void duplicateAndFutureObservationWindowsAreRejected() throws Exception {
    JSONObject d = miner();
    d.getJSONObject("hourly_shares").getJSONArray("rolling_hashrates").put(window(1));
    assertThrows(Exception.class, () -> parse(d));
    JSONObject future = miner();
    future
        .getJSONObject("hourly_shares")
        .getJSONArray("rolling_hashrates")
        .getJSONObject(0)
        .getJSONObject("share_statistics")
        .put("to", NOW + 100);
    assertThrows(Exception.class, () -> parse(future));
  }
}
