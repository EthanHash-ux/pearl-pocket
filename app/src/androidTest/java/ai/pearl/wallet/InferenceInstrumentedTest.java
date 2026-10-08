package ai.pearl.wallet;

import static org.junit.Assert.*;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.*;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class InferenceInstrumentedTest {
  final Instrumentation instrument = InstrumentationRegistry.getInstrumentation();

  Context context() {
    return instrument.getTargetContext();
  }

  UiDevice device() {
    return UiDevice.getInstance(instrument);
  }

  Context sandbox() {
    File dir = new File(context().getCacheDir(), "inference-" + System.nanoTime());
    assertTrue(dir.mkdirs());
    return new ContextWrapper(context()) {
      @Override
      public File getNoBackupFilesDir() {
        return dir;
      }
    };
  }

  JSONObject fixture() throws Exception {
    try (java.io.InputStream in =
        instrument.getContext().getAssets().open("signing-fixture.json")) {
      java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
      in.transferTo(out);
      return new JSONObject(out.toString("UTF-8"));
    }
  }

  JSONObject catalog() throws Exception {
    return new JSONObject()
        .put("protocol", "pearl-inference-1")
        .put("network", "pearl-mainnet")
        .put("asset", "PRL")
        .put("decimals", 8)
        .put("confirmations", 6)
        .put("merchant", "模拟商家 · 不接收真实资金")
        .put("account_xpub", fixture().getString("merchant_account_xpub"))
        .put(
            "models",
            new JSONArray()
                .put(
                    new JSONObject()
                        .put("id", "test/model")
                        .put("price_per_request_units", "10000")
                        .put("max_tokens", 512)
                        .put("max_input_bytes", 4000)));
  }

  JSONObject invoice() throws Exception {
    long now = System.currentTimeMillis() / 1000;
    return new JSONObject()
        .put("id", "a".repeat(64))
        .put("index", 1)
        .put("address", fixture().getString("invoice_address_1"))
        .put("model", catalog().getJSONArray("models").getJSONObject(0))
        .put("request_count", 10)
        .put("amount_units", "100000")
        .put("created_at", now)
        .put("expires_at", now + 900)
        .put("created_height", 100)
        .put("remaining_requests", 0)
        .put("status", "unpaid");
  }

  final String key = "prlai_" + "ab".repeat(32);

  @Test
  public void nativeInvoiceAddressAndImmutablePaymentChecks() throws Exception {
    JSONObject c = catalog(), inv = invoice();
    InferenceApi.validateInvoice(inv, c);
    assertEquals(
        fixture().getString("invoice_address_1"),
        NativeCore.call(
                new JSONObject()
                    .put("action", "invoiceaddress")
                    .put("xpub", c.getString("account_xpub"))
                    .put("index", 1))
            .getString("address"));
    for (int index : new int[] {0, -1})
      assertThrows(
          Exception.class,
          () ->
              NativeCore.call(
                  new JSONObject()
                      .put("action", "invoiceaddress")
                      .put("xpub", c.getString("account_xpub"))
                      .put("index", index)));
    for (String field : new String[] {"address", "amount_units", "expires_at"}) {
      JSONObject bad = new JSONObject(inv.toString());
      bad.put(field, field.equals("address") ? NativeCore.address(new byte[32]) : "1");
      assertThrows(Exception.class, () -> InferenceApi.validateInvoice(bad, c));
    }
  }

  @Test
  public void credentialsEncryptedJournalSurvivesRestartAndWalletIsolation() throws Exception {
    Context c = sandbox();
    String slot = UUID.randomUUID().toString(), address = NativeCore.address(new byte[32]);
    InferenceStore s = new InferenceStore(c, slot, address);
    s.connect("https://merchant.example", key, catalog());
    JSONObject inv = invoice();
    s.invoice(inv);
    s.sent(inv.getString("id"), "b".repeat(64));
    s.sent(inv.getString("id"), "b".repeat(64));
    assertThrows(Exception.class, () -> s.sent(inv.getString("id"), "c".repeat(64)));
    s.call("call-original-0001", inv.getString("id"), "pending");
    new InferenceStore(c, slot, address).call("call-original-0001", inv.getString("id"), "unknown");
    JSONObject read = new InferenceStore(c, slot, address).read();
    assertEquals(key, read.getString("key"));
    assertEquals("unknown", read.getJSONArray("calls").getJSONObject(0).getString("status"));
    assertEquals(
        "b".repeat(64), read.getJSONArray("invoices").getJSONObject(0).getString("local_txid"));
    File file = new File(WalletCatalog.directory(c, slot), "inference.enc");
    String disk =
        new String(java.nio.file.Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    assertFalse(disk.contains(key));
    assertFalse(disk.contains("merchant.example"));
    assertFalse(disk.contains(address));
    assertThrows(
        Exception.class,
        () -> new InferenceStore(c, slot, NativeCore.address(new byte[16])).read());
    assertNull(new InferenceStore(c, UUID.randomUUID().toString(), address).read());
    JSONObject envelope = new JSONObject(disk);
    String encrypted = envelope.getString("ciphertext");
    envelope.put("ciphertext", (encrypted.charAt(0) == 'A' ? "B" : "A") + encrypted.substring(1));
    java.nio.file.Files.write(file.toPath(), envelope.toString().getBytes(StandardCharsets.UTF_8));
    assertThrows(Exception.class, s::read);
  }

  @Test
  public void merchantChangeAlreadySentInvoiceAndExpiryBlockAnotherPayment() throws Exception {
    Context c = sandbox();
    String slot = UUID.randomUUID().toString(), address = NativeCore.address(new byte[32]);
    InferenceStore s = new InferenceStore(c, slot, address);
    JSONObject inv = invoice(), cat = catalog();
    s.connect("https://merchant.example", key, cat);
    s.invoice(inv);
    boolean[] change = {false};
    InferenceApi api =
        new InferenceApi(
            (base, method, path, key, body, invoice, id) -> {
              assertEquals("GET", method);
              if (path.equals("/v1/catalog")) return cat;
              JSONObject fresh = new JSONObject(inv.toString());
              if (change[0]) fresh.put("amount_units", "100001");
              return fresh;
            });
    InferencePayment p = new InferencePayment(slot, address, s, api, inv);
    p.recheck();
    change[0] = true;
    assertThrows(Exception.class, p::recheck);
    change[0] = false;
    s.sent(inv.getString("id"), "b".repeat(64));
    assertThrows(Exception.class, p::recheck);
    JSONObject old = invoice();
    old.put("id", "c".repeat(64))
        .put("created_at", System.currentTimeMillis() / 1000 - 1000)
        .put("expires_at", System.currentTimeMillis() / 1000 - 100);
    s.invoice(old);
    InferencePayment expired =
        new InferencePayment(
            slot,
            address,
            s,
            new InferenceApi(
                (base, method, path, k, body, i, r) -> path.equals("/v1/catalog") ? cat : old),
            old);
    assertThrows(Exception.class, expired::recheck);
  }

  UiObject2 text(String s) {
    UiObject2 v = device().wait(Until.findObject(By.text(s)), 15000);
    assertNotNull("Missing " + s, v);
    return v;
  }

  void tap(String s) throws Exception {
    if (device().wait(Until.findObject(By.text(s)), 2000) == null)
      new UiScrollable(new UiSelector().scrollable(true)).scrollIntoView(new UiSelector().text(s));
    device().waitForIdle();
    text(s).click();
    device().waitForIdle();
  }

  void keyboard() throws Exception {
    if (device().executeShellCommand("dumpsys input_method").contains("mInputShown=true"))
      device().pressBack();
  }

  void systemAuth() throws Exception {
    android.app.KeyguardManager k =
        (android.app.KeyguardManager) context().getSystemService(Context.KEYGUARD_SERVICE);
    context()
        .startActivity(
            k.createConfirmDeviceCredentialIntent("Inference mock test", "Public test wallet only")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    UiObject2 pin = device().wait(Until.findObject(By.clazz("android.widget.EditText")), 10000);
    assertNotNull(pin);
    pin.setText("24682468");
    device().pressEnter();
    Thread.sleep(600);
  }

  void auth() throws Exception {
    UiObject2 pin = device().wait(Until.findObject(By.clazz("android.widget.EditText")), 10000);
    assertNotNull(pin);
    pin.setText("24682468");
    device().pressEnter();
    text("钱包密码").setText("Emulator-wallet-test-42");
    keyboard();
    tap("确认");
  }

  @Test
  public void actualDevicePasswordInvoicePaymentQuotaAndUnknownRequestUiAllMocked()
      throws Exception {
    assertEquals("1", device().executeShellCommand("getprop ro.kernel.qemu").trim());
    device().pressHome();
    Context c = context();
    String pearl = NativeCore.address(new byte[32]);
    WalletCatalog wc = new WalletCatalog(c);
    String found = null;
    for (WalletCatalog.Entry e : wc.list()) if (e.address.equals(pearl)) found = e.slot;
    final String slot = found == null ? wc.newSlot() : found;
    systemAuth();
    WalletVault vault = new WalletVault(c, slot);
    if (!vault.exists()) vault.create(new byte[32], "Emulator-wallet-test-42".toCharArray(), pearl);
    wc.select(slot);
    wc.rename(slot, "AI 公开模拟钱包");
    c.getSharedPreferences("backup_status", Context.MODE_PRIVATE)
        .edit()
        .putBoolean(pearl, true)
        .commit();
    c.getSharedPreferences("public_preferences", Context.MODE_PRIVATE)
        .edit()
        .remove("observed_address")
        .putBoolean("hide_balances", false)
        .commit();
    for (String f : new String[] {"inference.enc", "pending-transfer.json"})
      new android.util.AtomicFile(new File(WalletCatalog.directory(c, slot), f)).delete();
    JSONObject fixture = fixture(), cat = catalog(), inv = invoice();
    int[] writes = {0}, chats = {0};
    boolean[] timeout = {false};
    String[] request = {""};
    InferenceApi api =
        new InferenceApi(
            (base, method, path, k, body, invoice, id) -> {
              assertEquals("https://merchant.example", base);
              if (path.equals("/v1/catalog")) return cat;
              if (path.equals("/v1/accounts"))
                throw new AssertionError("existing key import should not register");
              assertEquals(key, k);
              if (path.equals("/v1/invoices")) {
                writes[0]++;
                return new JSONObject(inv.toString());
              }
              if (path.equals("/v1/invoices/" + inv.getString("id")))
                return new JSONObject(inv.toString());
              if (path.endsWith("/claim")) {
                assertEquals(
                    new InferenceStore(c, slot, pearl)
                        .invoice(inv.getString("id"))
                        .getString("local_txid"),
                    body.getString("txid"));
                inv.put("txid", body.getString("txid"))
                    .put("status", "paid")
                    .put("remaining_requests", 10);
                return new JSONObject(inv.toString());
              }
              if (path.equals("/v1/chat/completions")) {
                chats[0]++;
                request[0] = id;
                assertEquals(
                    "hello", body.getJSONArray("messages").getJSONObject(0).getString("content"));
                inv.put("remaining_requests", inv.getInt("remaining_requests") - 1);
                if (timeout[0])
                  throw new java.io.IOException("mock timeout AFTER inference executed");
                return new JSONObject()
                    .put(
                        "choices",
                        new JSONArray()
                            .put(
                                new JSONObject()
                                    .put(
                                        "message",
                                        new JSONObject().put("content", "mock answer"))));
              }
              if (path.startsWith("/v1/requests/"))
                return new JSONObject().put("status", "unknown");
              throw new AssertionError(path);
            });
    int[] broadcasts = {0};
    PearlApi pearlApi =
        new PearlApi(
            url -> {
              if (url.equals(PearlApi.BLOCKBOOK))
                return "{\"blockbook\":{\"coin\":\"Pearl\",\"decimals\":8,\"bestHeight\":1000,\"inSync\":true,\"initialSync\":false},\"backend\":{\"chain\":\"mainnet\"}}";
              if (url.contains("estimatefee/")) return "{\"result\":\"0.00010031\"}";
              JSONArray rows = fixture.getJSONObject("payment").getJSONArray("utxos");
              if (url.contains("utxo/")) return rows.toString();
              for (int i = 0; i < rows.length(); i++) {
                JSONObject u = rows.getJSONObject(i);
                if (url.endsWith("tx/" + u.getString("txid")))
                  return new JSONObject()
                      .put("txid", u.getString("txid"))
                      .put("hex", u.getString("raw"))
                      .put("confirmations", 10)
                      .toString();
              }
              throw new AssertionError(url);
            },
            (url, raw) -> {
              broadcasts[0]++;
              String id =
                  NativeCore.call(new JSONObject().put("action", "transaction").put("raw", raw))
                      .getString("txid");
              return new JSONObject().put("result", id).toString();
            });
    Instrumentation.ActivityMonitor monitor =
        instrument.addMonitor(MainActivity.class.getName(), null, false);
    c.startActivity(
        new Intent(c, MainActivity.class)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
    Activity activity = instrument.waitForMonitorWithTimeout(monitor, 15000);
    instrument.removeMonitor(monitor);
    assertNotNull(activity);
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
            slot);
    InferenceTools tools =
        new InferenceTools(activity, () -> flow, () -> slot, () -> false, () -> false, api);
    instrument.runOnMainSync(
        () -> {
          try {
            java.lang.reflect.Field wf = MainActivity.class.getDeclaredField("walletFlow");
            wf.setAccessible(true);
            ((WalletFlow) wf.get(activity)).close();
            wf.set(activity, flow);
            java.lang.reflect.Field ai = MainActivity.class.getDeclaredField("inferenceTools");
            ai.setAccessible(true);
            ((InferenceTools) ai.get(activity)).close();
            ai.set(activity, tools);
            flow.resume();
            tools.resume();
          } catch (Exception e) {
            throw new AssertionError(e);
          }
        });
    tap("用 USDT 购买 PRL");
    text("用 USDT 购买 Pearl");
    assertTrue(device().hasObject(By.text("打开 BigONE · PRL/USDT")));
    assertTrue(device().hasObject(By.textContains("Pearl 主网")));
    tap("关闭");
    tap("打开 AI 推理");
    text("AI 推理 · PRL 支付");
    device().findObject(By.desc("服务商 HTTPS 域名")).setText("https://merchant.example");
    device().findObject(By.desc("已有 PRL 推理 API Key（可选）")).setText(key);
    keyboard();
    tap("连接并核对服务商");
    text("核对 AI 服务商");
    tap("确认绑定此服务商");
    text("AI 推理 · PRL 支付");
    tap("模型与 PRL 价格");
    text("模型与 PRL 价格");
    tap("购买调用次数 · test/model");
    tap("生成 PRL 账单");
    text("PRL 推理账单");
    tap("用当前钱包支付 PRL");
    text("确认 PRL 支付 AI 账单");
    assertTrue(device().hasObject(By.textContains("商家")));
    tap("验证并发送");
    auth();
    text("交易已提交");
    tap("知道了");
    assertEquals(1, broadcasts[0]);
    assertEquals(1, writes[0]);
    assertNotNull(
        new InferenceStore(c, slot, pearl).invoice(inv.getString("id")).getString("local_txid"));
    // Authentication closed the old tools dialog; open saved records explicitly.
    tap("打开 AI 推理");
    tap("我的账单与 API 额度");
    tap("test/model · unpaid · aaaaaaaa");
    tap("查询付款并开通额度");
    text("PRL 推理账单");
    tap("试调用 AI API");
    device().findObject(By.desc("发送给 AI 的提示词")).setText("hello");
    keyboard();
    tap("发送请求 · 消耗 1 次");
    text("AI 回复");
    assertTrue(device().hasObject(By.textContains("mock answer")));
    tap("知道了");
    instrument.runOnMainSync(
        () -> {
          tools.pause();
          tools.resume();
        });
    tap("打开 AI 推理");
    tap("我的账单与 API 额度");
    tap("test/model · paid · aaaaaaaa");
    timeout[0] = true;
    tap("试调用 AI API");
    device().findObject(By.desc("发送给 AI 的提示词")).setText("hello");
    keyboard();
    tap("发送请求 · 消耗 1 次");
    text("操作未完成");
    tap("知道了");
    instrument.runOnMainSync(
        () -> {
          tools.pause();
          tools.resume();
        });
    tap("打开 AI 推理");
    tap("我的账单与 API 额度");
    tap("test/model · paid · aaaaaaaa");
    tap("试调用 AI API");
    text("先查询上一条请求");
    assertEquals(2, chats[0]);
    assertEquals(
        "unknown",
        new InferenceStore(c, slot, pearl)
            .read()
            .getJSONArray("calls")
            .getJSONObject(1)
            .getString("status"));
    assertFalse(request[0].isEmpty());
    new File(c.getFilesDir(), "inference-ui-proof.json").delete();
    java.nio.file.Files.write(
        new File(c.getFilesDir(), "inference-ui-proof.json").toPath(),
        new JSONObject()
            .put("result", "PASS")
            .put("mainnet_funds_used", false)
            .put("mock_prl_broadcasts", broadcasts[0])
            .put("mock_calls", chats[0])
            .put("system_pin_and_wallet_password", true)
            .put("unknown_result_blocks_another_call", true)
            .toString()
            .getBytes(StandardCharsets.UTF_8));
  }
}
