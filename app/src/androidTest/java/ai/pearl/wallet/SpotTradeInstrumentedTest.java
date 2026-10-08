package ai.pearl.wallet;

import static org.junit.Assert.*;

import android.app.Activity;
import android.app.Instrumentation;
import android.app.KeyguardManager;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.view.WindowManager;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.*;
import java.io.File;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class SpotTradeInstrumentedTest {
  final Instrumentation instrument = InstrumentationRegistry.getInstrumentation();

  Context context() {
    return instrument.getTargetContext();
  }

  UiDevice device() {
    return UiDevice.getInstance(instrument);
  }

  Context sandbox() {
    File dir = new File(context().getCacheDir(), "spot-test-" + System.nanoTime());
    assertTrue(dir.mkdirs());
    return new ContextWrapper(context()) {
      @Override
      public File getNoBackupFilesDir() {
        return dir;
      }
    };
  }

  static JSONObject plan(String from, String action) throws Exception {
    long now = System.currentTimeMillis() / 1000;
    JSONObject p =
        new JSONObject()
            .put("action", action)
            .put("from", from)
            .put("nonce", System.currentTimeMillis() * 1024)
            .put("issuedAt", now)
            .put("expires", now + 240);
    if (action.equals("place_order"))
      p.put("side", "sell").put("price", "2160000").put("quantity", "560000000");
    if (action.equals("withdraw"))
      p.put("asset", "USDC-ARB").put("destination", from).put("quantity", "3500000");
    if (action.equals("cancel_order")) p.put("orderId", "123");
    return p;
  }

  @Test
  public void nativeSignaturesPassAndroidNetworkBoundaryAcrossMnemonicLengths() throws Exception {
    for (int n : new int[] {16, 20, 24, 28, 32})
      for (String action : new String[] {"place_order", "withdraw", "cancel_order"}) {
        byte[] entropy = new byte[n];
        String from = NativeCore.ethereumIdentity(entropy).getString("address");
        JSONObject p = plan(from, action), a = NativeCore.tradeSign(entropy, p);
        assertEquals(5, a.length());
        NativeCore.call(
            new JSONObject().put("action", "tradeverify").put("trade", p).put("tradeAuth", a));
        String
            key =
                action.equals("place_order")
                    ? "order"
                    : action.equals("withdraw") ? "withdrawal" : "request",
            path =
                action.equals("place_order")
                    ? "/v1/orders"
                    : action.equals("withdraw") ? "/v1/withdrawals" : "/v1/orders/123",
            method = action.equals("cancel_order") ? "DELETE" : "POST";
        JSONObject body =
            new JSONObject().put(key, new JSONObject(a.getString("payload"))).put("auth", a);
        SpotTradeApi.validateRoute(method, path, body.toString());
        body.getJSONObject(key).put("extra", true);
        assertThrows(
            IllegalArgumentException.class,
            () -> SpotTradeApi.validateRoute(method, path, body.toString()));
      }
  }

  @Test
  public void pendingRequestBlocksDuplicateSigningAndSurvivesRestartTimeoutAndTampering()
      throws Exception {
    Context c = sandbox();
    String slot = UUID.randomUUID().toString(),
        from = NativeCore.ethereumIdentity(new byte[16]).getString("address"),
        pearl = NativeCore.address(new byte[16]);
    SpotTradeStore s = new SpotTradeStore(c, slot, from, pearl);
    JSONObject
        p =
            s.plan("place_order")
                .put("side", "sell")
                .put("quantity", "560000000")
                .put("price", "2160000"),
        a = NativeCore.tradeSign(new byte[16], p);
    s.save(p, a);
    int[] attempts = {0};
    SpotTradeApi api =
        new SpotTradeApi(
            (m, path, body) -> {
              attempts[0]++;
              assertEquals(
                  a.getString("signature"),
                  new JSONObject(body).getJSONObject("auth").getString("signature"));
              throw new java.io.IOException("simulated timeout AFTER venue accepted the order");
            });
    assertThrows(java.io.IOException.class, () -> api.submit(p, a));
    assertEquals(1, attempts[0]);
    assertEquals(
        a.getString("signature"),
        new SpotTradeStore(c, slot, from, pearl)
            .pending()
            .getJSONObject("auth")
            .getString("signature"));
    assertThrows(IllegalArgumentException.class, () -> s.plan("withdraw"));
    assertNull(new SpotTradeStore(c, UUID.randomUUID().toString(), from, pearl).pending());
    assertThrows(
        IllegalArgumentException.class,
        () -> new SpotTradeStore(c, slot, DeFiApi.USDC, pearl).pending());
    s.pin(pearl);
    assertThrows(IllegalArgumentException.class, () -> s.pin(NativeCore.address(new byte[20])));
    File f = new File(WalletCatalog.directory(c, slot), "spot-trade.json");
    JSONObject tampered =
        new JSONObject(
            new String(java.nio.file.Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
    tampered.getJSONObject("pending").getJSONObject("plan").put("quantity", "660000000");
    java.nio.file.Files.write(f.toPath(), tampered.toString().getBytes(StandardCharsets.UTF_8));
    assertThrows(IllegalArgumentException.class, s::pending);
  }

  @Test
  public void liveVenueAndHistoricalArbitrumReceiptReadOnly() throws Exception {
    List<String> calls = new ArrayList<>();
    SpotTradeApi api =
        new SpotTradeApi(
            (m, path, body) -> {
              assertEquals("GET", m);
              assertNull(body);
              calls.add(path);
              return SpotTradeApi.https(m, path, body);
            });
    SpotTradeApi.Market m = api.market();
    assertTrue(m.buyFee >= 0);
    assertNotNull(api.book());
    assertEquals(BigInteger.ZERO, api.balances("0x0000000000000000000000000000000000000001").usdc);
    SpotUsdcApi rpc =
        new SpotUsdcApi(
            body -> {
              JSONObject r = new JSONObject(body);
              assertFalse(r.getString("method").equals("eth_sendRawTransaction"));
              calls.add(r.getString("method"));
              return SpotUsdcApi.https(body);
            });
    assertTrue(rpc.balance("0x0000000000000000000000000000000000000001").signum() >= 0);
    // Public historical venue withdrawal sampled read-only from /v1/withdrawals.
    SpotUsdcApi.Receipt receipt =
        rpc.receipt(
            "0xfb91af690cdf461d871bb243473e9c0fb064885340bb45772cdc897c5b44019a",
            "0xa9faa1161206524e83427ca7f656b8140c7a4c4e",
            1791410695);
    assertNotNull(receipt);
    assertEquals(new BigInteger("13756000"), receipt.received);
    assertTrue(receipt.safe);
    JSONObject report =
        new JSONObject()
            .put("result", "PASS")
            .put(
                "scope",
                "read-only Pearl Trade native market and historical Arbitrum USDC Transfer receipt")
            .put("calls", new JSONArray(calls))
            .put("received_units", receipt.received.toString())
            .put("safe", receipt.safe)
            .put("finalized", receipt.finalized)
            .put("orders_submitted", 0)
            .put("withdrawals_submitted", 0);
    java.nio.file.Files.write(
        new File(context().getExternalFilesDir(null), "spot-live-read.json").toPath(),
        report.toString().getBytes(StandardCharsets.UTF_8));
  }

  static JSONObject market() throws Exception {
    try (java.io.InputStream in =
        InstrumentationRegistry.getInstrumentation()
            .getContext()
            .getAssets()
            .open("spot-market.json")) {
      java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
      byte[] buf = new byte[4096];
      int n;
      while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
      return new JSONObject(out.toString("UTF-8"));
    }
  }

  static final class Venue implements SpotTradeApi.Transport {
    final JSONArray orders = new JSONArray(), withdrawals = new JSONArray();
    String from, pearl, deposit;
    BigInteger prl = new BigInteger("10000000000"), usdc = BigInteger.ZERO;
    int mutations;
    boolean timeout;

    public synchronized String request(String m, String path, String body) throws Exception {
      if (m.equals("GET"))
        switch (path.split("\\?")[0]) {
          case "/v1/markets":
            return market().toString();
          case "/v1/markets/PRL/orderbook":
            return "{\"market\":\"PRL\",\"bids\":[{\"price\":\"2160000\",\"qtySats\":\"10000000000\"}],\"asks\":[{\"price\":\"3000000\",\"qtySats\":\"10000000000\"}]}";
          case "/v1/orders":
            return new JSONObject().put("orders", orders).toString();
          case "/v1/withdrawals":
            return new JSONObject().put("withdrawals", withdrawals).toString();
          default:
            if (path.startsWith("/v1/balances/"))
              return new JSONObject()
                  .put(
                      "balances",
                      new JSONArray()
                          .put(
                              new JSONObject()
                                  .put("asset", "PRL")
                                  .put("available", prl.toString())
                                  .put("reserved", "0"))
                          .put(
                              new JSONObject()
                                  .put("asset", "USDC-ARB")
                                  .put("available", usdc.toString())
                                  .put("reserved", "0")))
                  .toString();
            if (path.startsWith("/v1/users/"))
              return new JSONObject()
                  .put("eth_address", from)
                  .put("pearl_deposit_address", deposit)
                  .toString();
            throw new AssertionError(path);
        }
      if (path.equals("/v1/users"))
        return new JSONObject()
            .put("eth_address", from)
            .put("pearl_deposit_address", deposit)
            .toString();
      SpotTradeApi.validateRoute(m, path, body);
      JSONObject b = new JSONObject(body);
      mutations++;
      if (path.equals("/v1/orders")) {
        JSONObject p = b.getJSONObject("order");
        BigInteger q = new BigInteger(p.getString("qty_sats")),
            price = new BigInteger(p.getString("price_micro_per_prl"));
        boolean fill = price.equals(new BigInteger("2160000"));
        JSONObject o =
            new JSONObject()
                .put("id", mutations)
                .put("eth_address", from)
                .put("market", "PRL")
                .put("side", p.getString("side"))
                .put("qty_sats", q.toString())
                .put("filled_sats", fill ? q.toString() : "0")
                .put("price_micro_per_prl", price.toString())
                .put("status", fill ? "filled" : "open");
        orders.put(o);
        if (fill) {
          prl = prl.subtract(q);
          usdc = usdc.add(SpotTradeTools.notional(q, price, false));
        }
        if (timeout) {
          timeout = false;
          throw new java.io.IOException("simulated response timeout after order execution");
        }
        return new JSONObject().put("order", o).toString();
      }
      if (path.equals("/v1/withdrawals")) {
        JSONObject p = b.getJSONObject("withdrawal");
        BigInteger q = new BigInteger(p.getString("amount_units"));
        assertEquals("USDC-ARB", p.getString("asset"));
        assertEquals(
            from.toLowerCase(java.util.Locale.ROOT),
            p.getString("dest_address").toLowerCase(java.util.Locale.ROOT));
        usdc = usdc.subtract(q);
        JSONObject w =
            new JSONObject()
                .put("id", mutations)
                .put("eth_address", from)
                .put("asset", "USDC-ARB")
                .put("chain", "arb")
                .put("dest_address", from)
                .put("amount_units", q.subtract(BigInteger.valueOf(500000)).toString())
                .put("fee_units", "500000")
                .put("status", "confirmed")
                .put("tx_hash", "0x" + "34".repeat(32))
                .put("requested_at", System.currentTimeMillis() / 1000);
        withdrawals.put(w);
        return new JSONObject().put("id", mutations).toString();
      }
      if (m.equals("DELETE")) {
        JSONObject o = SpotTradeTools.find(orders, path.substring(path.lastIndexOf('/') + 1));
        o.put("status", "cancelled");
        return "{\"ok\":true}";
      }
      throw new AssertionError(path);
    }
  }

  static final class ReceiptRpc implements EvmPublicApi.Rpc {
    String from;
    BigInteger received = new BigInteger("3000000");

    public synchronized String post(String body) throws Exception {
      JSONObject r = new JSONObject(body);
      JSONArray p = r.getJSONArray("params");
      Object value;
      switch (r.getString("method")) {
        case "eth_chainId":
          value = "0xa4b1";
          break;
        case "eth_blockNumber":
          value = "0x100";
          break;
        case "eth_call":
          value = "0x" + EvmPublicApi.word(received);
          break;
        case "eth_getBlockByNumber":
          value =
              new JSONObject()
                  .put(
                      "number",
                      p.getString(0).equals("safe") || p.getString(0).equals("finalized")
                          ? "0x102"
                          : p.getString(0))
                  .put("hash", "0x" + "56".repeat(32))
                  .put("timestamp", "0x" + Long.toHexString(System.currentTimeMillis() / 1000));
          break;
        case "eth_getTransactionReceipt":
          value =
              new JSONObject()
                  .put("transactionHash", p.getString(0))
                  .put("status", "0x1")
                  .put("blockNumber", "0x100")
                  .put("blockHash", "0x" + "56".repeat(32))
                  .put(
                      "logs",
                      new JSONArray()
                          .put(
                              new JSONObject()
                                  .put("address", SpotTradeApi.USDC)
                                  .put("transactionHash", p.getString(0))
                                  .put("blockNumber", "0x100")
                                  .put("blockHash", "0x" + "56".repeat(32))
                                  .put("data", "0x" + EvmPublicApi.word(received))
                                  .put(
                                      "topics",
                                      new JSONArray()
                                          .put(SpotUsdcApi.TRANSFER)
                                          .put("0x" + EvmPublicApi.addressWord(SpotTradeApi.VAULT))
                                          .put("0x" + EvmPublicApi.addressWord(from)))));
          break;
        default:
          throw new AssertionError(r.getString("method"));
      }
      return new JSONObject().put("jsonrpc", "2.0").put("id", 1).put("result", value).toString();
    }
  }

  UiObject2 text(String s) {
    UiObject2 v = device().wait(Until.findObject(By.text(s)), 15000);
    assertNotNull("Missing " + s, v);
    return v;
  }

  void tap(String s) throws Exception {
    if (device().wait(Until.findObject(By.text(s)), 3000) == null)
      new UiScrollable(new UiSelector().scrollable(true)).scrollIntoView(new UiSelector().text(s));
    for (int i = 0; i < 3; i++) {
      device().waitForIdle();
      try {
        text(s).click();
        return;
      } catch (StaleObjectException e) {
        if (i == 2) throw e;
      }
    }
  }

  void keyboard() throws Exception {
    if (device().executeShellCommand("dumpsys input_method").contains("mInputShown=true"))
      device().pressBack();
  }

  void auth(String password) throws Exception {
    UiObject2 pin = device().wait(Until.findObject(By.clazz("android.widget.EditText")), 10000);
    assertNotNull(pin);
    pin.setText("24682468");
    device().pressEnter();
    text("钱包密码").setText(password);
    keyboard();
    tap("确认");
  }

  void open() throws Exception {
    tap("买卖 PRL");
  }

  void systemAuth() throws Exception {
    Intent i =
        ((KeyguardManager) context().getSystemService(Context.KEYGUARD_SERVICE))
            .createConfirmDeviceCredentialIntent("Spot fixture setup", "Public fixture only");
    context().startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    UiObject2 pin = device().wait(Until.findObject(By.clazz("android.widget.EditText")), 10000);
    assertNotNull(pin);
    pin.setText("24682468");
    device().pressEnter();
    assertTrue(device().wait(Until.gone(By.text("Spot fixture setup")), 10000));
    Thread.sleep(400);
  }

  @Test
  public void walletUiDepositsSignsSellWithdrawCancelAndRecoversUnknownResult() throws Exception {
    assertEquals("1", device().executeShellCommand("getprop ro.kernel.qemu").trim());
    Context c = context();
    String pearl = NativeCore.address(new byte[32]),
        from = NativeCore.ethereumIdentity(new byte[32]).getString("address");
    WalletCatalog catalog = new WalletCatalog(c);
    String found = null;
    for (WalletCatalog.Entry entry : catalog.list())
      if (entry.address.equals(pearl)) found = entry.slot;
    final String selected = found == null ? catalog.newSlot() : found;
    systemAuth();
    WalletVault vault = new WalletVault(c, selected);
    String password = "Emulator-wallet-test-42";
    if (!vault.exists()) vault.create(new byte[32], password.toCharArray(), pearl);
    catalog.rename(selected, "公开模拟测试钱包");
    new WalletCatalog(c).select(selected);
    c.getSharedPreferences("public_preferences", Context.MODE_PRIVATE)
        .edit()
        .remove("observed_address")
        .putBoolean("hide_balances", false)
        .commit();
    c.getSharedPreferences("backup_status", Context.MODE_PRIVATE)
        .edit()
        .putBoolean(pearl, true)
        .commit();
    for (String f :
        new String[] {
          "spot-trade.json",
          "pending-transfer.json",
          "pending-ethereum.json",
          "ethereum-address.json"
        }) new android.util.AtomicFile(new File(WalletCatalog.directory(c, selected), f)).delete();
    new EthereumAccount(c, selected).save(pearl, NativeCore.ethereumIdentity(new byte[32]));
    Venue venue = new Venue();
    venue.from = from;
    venue.pearl = pearl;
    JSONObject fixture;
    try (java.io.InputStream in =
        instrument.getContext().getAssets().open("signing-fixture.json")) {
      java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
      in.transferTo(out);
      fixture = new JSONObject(out.toString("UTF-8"));
    }
    venue.deposit = fixture.getJSONObject("payment").getString("to");
    SpotTradeApi api = new SpotTradeApi(venue);
    ReceiptRpc rpc = new ReceiptRpc();
    rpc.from = from;
    Instrumentation.ActivityMonitor monitor =
        instrument.addMonitor(MainActivity.class.getName(), null, false);
    c.startActivity(
        new Intent(c, MainActivity.class)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
    Activity activity = instrument.waitForMonitorWithTimeout(monitor, 15000);
    instrument.removeMonitor(monitor);
    assertNotNull(activity);
    assertTrue(
        (activity.getWindow().getAttributes().flags & WindowManager.LayoutParams.FLAG_SECURE) != 0);
    int[] deposits = {0};
    PearlApi pearlApi =
        new PearlApi(
            url -> {
              if (url.equals(PearlApi.BLOCKBOOK))
                return "{\"blockbook\":{\"coin\":\"Pearl\",\"decimals\":8,\"bestHeight\":1000,\"inSync\":true,\"initialSync\":false},\"backend\":{\"chain\":\"mainnet\"}}";
              if (url.contains("estimatefee/")) return "{\"result\":\"0.00010031\"}";
              JSONArray utxos = fixture.getJSONObject("payment").getJSONArray("utxos");
              if (url.contains("utxo/")) return utxos.toString();
              for (int n = 0; n < utxos.length(); n++) {
                JSONObject u = utxos.getJSONObject(n);
                if (url.endsWith("tx/" + u.getString("txid")))
                  return new JSONObject()
                      .put("txid", u.getString("txid"))
                      .put("hex", u.getString("raw"))
                      .put("confirmations", 10)
                      .toString();
              }
              throw new AssertionError("Unexpected Pearl GET " + url);
            },
            (url, raw) -> {
              assertEquals(PearlApi.BLOCKBOOK + "sendtx/", url);
              deposits[0]++;
              String txid =
                  NativeCore.call(new JSONObject().put("action", "transaction").put("raw", raw))
                      .getString("txid");
              return new JSONObject().put("result", txid).toString();
            });
    java.lang.reflect.Field flowField = MainActivity.class.getDeclaredField("walletFlow"),
        toolsField = MainActivity.class.getDeclaredField("spotTradeTools");
    flowField.setAccessible(true);
    toolsField.setAccessible(true);
    instrument.runOnMainSync(
        () -> {
          try {
            ((WalletFlow) flowField.get(activity)).close();
            ((SpotTradeTools) toolsField.get(activity)).close();
            WalletFlow flow =
                new WalletFlow(
                    activity,
                    new WalletFlow.Host() {
                      public void changed() {}

                      public String address() {
                        return pearl;
                      }
                    },
                    pearlApi,
                    selected,
                    api);
            flow.resume();
            flowField.set(activity, flow);
            SpotTradeTools tools =
                new SpotTradeTools(
                    activity,
                    () -> flow,
                    () -> selected,
                    () -> "公开模拟测试钱包",
                    () -> false,
                    () -> false,
                    api,
                    new SpotUsdcApi(rpc));
            tools.resume();
            toolsField.set(activity, tools);
          } catch (Exception e) {
            throw new AssertionError(e);
          }
        });
    open();
    tap("充值原生 PRL");
    text("充值金额（PRL）").setText("0.001");
    keyboard();
    tap("我已在官网用同一账户核对完整充值地址，并知晓充值后由平台控制 PRL");
    tap("预览 PRL 充值");
    text("确认转账");
    assertTrue(
        device().hasObject(By.textContains("充值不是卖出"))
            || device().hasObject(By.textContains("这是充值")));
    tap("验证并发送");
    auth(password);
    text("交易已提交");
    tap("知道了");
    assertEquals(1, deposits[0]);
    open();
    tap("卖出 PRL，获得 USDC");
    text("交易数量（PRL，最少 1）").setText("5.6");
    keyboard();
    tap("获取真实盘口并预览订单");
    text("确认现货操作");
    assertTrue(device().hasObject(By.textContains("最低卖出价")));
    tap("验证并签署现货请求");
    auth(password);
    text("平台订单已接收");
    tap("知道了");
    assertEquals(1, venue.mutations);
    assertNull(new SpotTradeStore(c, selected, from, pearl).pending());
    open();
    tap("提现 USDC 到本钱包");
    text("提现金额（USDC）").setText("3.5");
    keyboard();
    tap("核验提现费并预览");
    text("确认现货操作");
    assertTrue(device().hasObject(By.textContains("预计链上收到 · 3")));
    tap("验证并签署现货请求");
    auth(password);
    text("提现申请已接收");
    tap("知道了");
    open();
    tap("提现与到账查询");
    tap("核验 USDC 到账 #2");
    text("USDC 已在链上收到");
    tap("知道了");
    tap("关闭");
    open();
    tap("卖出 PRL，获得 USDC");
    text("交易数量（PRL，最少 1）").setText("1");
    UiObject2 price = device().wait(Until.findObject(By.desc("最低卖出价（USDC / PRL）")), 10000);
    assertNotNull(price);
    price.setText("3.5");
    keyboard();
    tap("获取真实盘口并预览订单");
    tap("验证并签署现货请求");
    auth(password);
    text("平台订单已接收");
    tap("知道了");
    open();
    tap("订单与撤单");
    tap("撤销订单 #3");
    tap("验证并签署现货请求");
    auth(password);
    text("撤单状态已更新");
    tap("知道了");
    venue.timeout = true;
    open();
    tap("卖出 PRL，获得 USDC");
    text("交易数量（PRL，最少 1）").setText("1");
    keyboard();
    tap("获取真实盘口并预览订单");
    tap("验证并签署现货请求");
    auth(password);
    text("现货操作未完成");
    tap("知道了");
    assertEquals(5, venue.mutations);
    assertNotNull(new SpotTradeStore(c, selected, from, pearl).pending());
    open();
    text("现货结果待确认");
    assertFalse(device().hasObject(By.text("卖出 PRL，获得 USDC")));
    tap("查询平台订单");
    text("订单与撤单");
    tap("关闭");
    open();
    tap("已核对结果，解除本机锁定");
    tap("记录核对并解除锁定");
    text("PRL / USDC 现货");
    assertNull(new SpotTradeStore(c, selected, from, pearl).pending());
    assertEquals(5, venue.mutations);
    tap("关闭");
    JSONObject report =
        new JSONObject()
            .put("result", "PASS")
            .put(
                "scope",
                "Android UI, real device/password authentication and native signatures with"
                    + " simulated Pearl/CLOB/Arbitrum transports")
            .put("pearl_deposits", deposits[0])
            .put("signed_venue_requests", venue.mutations)
            .put(
                "covers",
                new JSONArray(
                    Arrays.asList(
                        "deposit_address_pin",
                        "native_pearl_deposit",
                        "price_bound_sell",
                        "withdraw_net_plus_fee",
                        "receipt_transfer_log",
                        "limit_cancel",
                        "timeout_after_acceptance",
                        "pending_blocks_duplicate",
                        "explicit_unknown_resolution")))
            .put("mainnet_funds_used", false);
    java.nio.file.Files.write(
        new File(c.getExternalFilesDir(null), "spot-ui-test.json").toPath(),
        report.toString().getBytes(StandardCharsets.UTF_8));
  }
}
