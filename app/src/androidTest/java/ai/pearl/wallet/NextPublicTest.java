package ai.pearl.wallet;

import static org.junit.Assert.*;

import android.app.*;
import android.content.*;
import android.graphics.*;
import android.net.*;
import android.widget.*;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.*;
import java.io.*;
import java.math.*;
import java.util.*;
import org.junit.*;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class NextPublicTest {
  static final String A = "prl1pr6yuq8u2r95wjzzgpdy8cpnncpl7l8zgy6x5q0367pnc53s2famqg7pt74";

  Context context() {
    return InstrumentationRegistry.getInstrumentation().getTargetContext();
  }

  UiDevice device() {
    return UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
  }

  UiObject2 text(String s) {
    UiObject2 v = device().wait(Until.findObject(By.text(s)), 15000);
    assertNotNull("Missing " + s, v);
    return v;
  }

  private void hideKeyboard() {
    if (device().hasObject(By.pkg("com.google.android.inputmethod.latin"))) device().pressBack();
  }

  private long receiptCount(NotificationManager m, String tag) {
    return Arrays.stream(m.getActiveNotifications()).filter(n -> tag.equals(n.getTag())).count();
  }

  private void tap(BySelector selector) throws Exception {
    for (int attempt = 0; attempt < 3; attempt++) {
      try {
        device().waitForIdle();
        UiObject2 v = device().wait(Until.findObject(selector), 15000);
        assertNotNull(v);
        v.click();
        return;
      } catch (StaleObjectException e) {
        if (attempt == 2) throw e;
        Thread.sleep(200);
      }
    }
  }

  @Test
  public void exportedPngDecodesExactPaymentAndProviderCannotOpenPrivateOrWrite() throws Exception {
    Context c = context();
    String payload = PaymentRequest.encode(A, "1.00000001");
    byte[] bytes = QrImage.png(payload, A, "1.00000001");
    Uri uri = PublicShareProvider.write(c, bytes, "png");
    assertEquals("image/png", c.getContentResolver().getType(uri));
    try (InputStream in = c.getContentResolver().openInputStream(uri)) {
      Bitmap b = BitmapFactory.decodeStream(in);
      assertNotNull(b);
      int[] pixels = new int[b.getWidth() * b.getHeight()];
      b.getPixels(pixels, 0, b.getWidth(), 0, 0, b.getWidth(), b.getHeight());
      String decoded =
          new com.google.zxing.MultiFormatReader()
              .decode(
                  new com.google.zxing.BinaryBitmap(
                      new com.google.zxing.common.HybridBinarizer(
                          new com.google.zxing.RGBLuminanceSource(
                              b.getWidth(), b.getHeight(), pixels))))
              .getText();
      assertEquals(payload, decoded);
      b.recycle();
    }
    try (android.database.Cursor cursor =
        c.getContentResolver().query(uri, null, null, null, null)) {
      assertTrue(cursor.moveToFirst());
      assertEquals(
          bytes.length,
          cursor.getLong(cursor.getColumnIndexOrThrow(android.provider.OpenableColumns.SIZE)));
    }
    assertThrows(
        FileNotFoundException.class, () -> c.getContentResolver().openFileDescriptor(uri, "w"));
    assertThrows(
        FileNotFoundException.class,
        () ->
            c.getContentResolver()
                .openInputStream(
                    Uri.parse("content://ai.pearl.wallet.publicshare/../wallet-v1.json")));
    assertThrows(IllegalArgumentException.class, () -> QrImage.png(payload, A, "2"));
    Intent chooser = PublicShareProvider.intent(c, bytes, "png", "QR");
    Intent send = chooser.getParcelableExtra(Intent.EXTRA_INTENT);
    assertNotNull(send.getClipData());
    assertTrue((send.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0);
  }

  @Test
  public void liveMinerPublicApiHasValidatedWorkerAndRewardRecords() throws Exception {
    MinerAccount s =
        MinerAccount.fetch("prl1pavapvy5w8qv7jly9la3xwzueatc87k76e0ewhjjdacmtd07mrnuq0czz8j");
    assertTrue(s.found);
    assertTrue(s.balance.signum() >= 0);
    assertTrue(s.paid.signum() > 0);
    assertFalse(s.payouts.isEmpty());
    assertTrue(System.currentTimeMillis() / 1000 - s.receivedAt < 30);
    assertFalse(MinerAccount.fetch(A).found);
  }

  @Test
  public void widgetManualBroadcastFetchesQuoteAndNativeLayoutDisplaysPublicPrice()
      throws Exception {
    Context c = context();
    c.getSharedPreferences("price_widget", 0).edit().clear().commit();
    c.sendBroadcast(new Intent(c, PriceWidget.class).setAction("ai.pearl.wallet.WIDGET_REFRESH"));
    long end = System.currentTimeMillis() + 45000;
    while (c.getSharedPreferences("price_widget", 0).getLong("at", 0) == 0
        && System.currentTimeMillis() < end) Thread.sleep(200);
    assertTrue(c.getSharedPreferences("price_widget", 0).getLong("at", 0) > 0);
    assertTrue(
        new BigDecimal(c.getSharedPreferences("price_widget", 0).getString("usdt", "0")).signum()
            > 0);
    InstrumentationRegistry.getInstrumentation()
        .runOnMainSync(
            () -> {
              android.view.View layout =
                  new RemoteViews(c.getPackageName(), R.layout.price_widget).apply(c, null);
              assertNotNull(layout.findViewById(R.id.widget_refresh));
              assertNotNull(layout.findViewById(R.id.widget_price));
            });
  }

  @Test
  public void receiptJobBaselinesThenRealAndroidNotificationsAreDeduplicatedWithoutUnlock()
      throws Exception {
    Context c = context();
    assertEquals("1", device().executeShellCommand("getprop ro.kernel.qemu").trim());
    device().pressHome();
    InstrumentationRegistry.getInstrumentation()
        .getUiAutomation()
        .grantRuntimePermission(c.getPackageName(), android.Manifest.permission.POST_NOTIFICATIONS);
    c.getSharedPreferences("public_preferences", 0)
        .edit()
        .putString("receipt_signer", A)
        .putBoolean("receipt_notifications", true)
        .commit();
    c.getSharedPreferences("public_tools", 0)
        .edit()
        .remove("receipt_states")
        .remove("watch_addresses")
        .commit();
    NotificationManager manager = c.getSystemService(NotificationManager.class);
    manager.cancelAll();
    try {
      ReceiptNotifications.schedule(c);
      assertNotNull(
          c.getSystemService(android.app.job.JobScheduler.class)
              .getPendingJob(ReceiptNotifications.JOB));
      assertTrue(
          device()
              .executeShellCommand("cmd jobscheduler run -f ai.pearl.wallet 808277")
              .contains("Running job"));
      long end = System.currentTimeMillis() + 45000;
      while (new org.json.JSONArray(PublicStore.of(c).read("receipt_states")).length() == 0
          && System.currentTimeMillis() < end) Thread.sleep(200);
      assertTrue(new org.json.JSONArray(PublicStore.of(c).read("receipt_states")).length() > 0);
      assertEquals(0, manager.getActiveNotifications().length);
      long now = System.currentTimeMillis() / 1000;
      PearlApi.Transaction tx = new PearlApi.Transaction("ab".repeat(32), BigInteger.ONE, now, 0);
      PearlApi.Account account =
          new PearlApi.Account(A, BigInteger.ZERO, BigInteger.ONE, 1, List.of(tx));
      ReceiptNotifications.check(c, A, account);
      Thread.sleep(300);
      assertEquals(1, receiptCount(manager, A + tx.id));
      ReceiptNotifications.check(c, A, account);
      assertEquals(1, receiptCount(manager, A + tx.id));
      PearlApi.Transaction confirmed = new PearlApi.Transaction(tx.id, tx.netGrains, now, 1);
      ReceiptNotifications.check(
          c, A, new PearlApi.Account(A, BigInteger.ONE, BigInteger.ZERO, 1, List.of(confirmed)));
      Thread.sleep(300);
      assertEquals(2, receiptCount(manager, A + tx.id));
    } finally {
      c.getSharedPreferences("public_preferences", 0)
          .edit()
          .putBoolean("receipt_notifications", false)
          .commit();
      ReceiptNotifications.schedule(c);
      manager.cancelAll();
    }
  }

  @Test
  public void pagedHistorySearchNotesAndCompleteCsvUseNativeUi() throws Exception {
    Context c = context();
    Instrumentation instrument = InstrumentationRegistry.getInstrumentation();
    Instrumentation.ActivityMonitor monitor =
        instrument.addMonitor(MainActivity.class.getName(), null, false);
    c.startActivity(
        new Intent(c, MainActivity.class)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
    Activity activity = instrument.waitForMonitorWithTimeout(monitor, 10000);
    instrument.removeMonitor(monitor);
    assertNotNull(activity);
    PearlApi api =
        new PearlApi(
            url -> {
              if (url.equals(PearlApi.BLOCKBOOK))
                return "{\"blockbook\":{\"coin\":\"Pearl\",\"decimals\":8,\"bestHeight\":10,\"inSync\":true,\"initialSync\":false},\"backend\":{\"chain\":\"mainnet\"}}";
              boolean whole = url.contains("pageSize=100"), second = url.contains("page=2");
              org.json.JSONArray rows = new org.json.JSONArray();
              for (int i = whole ? 0 : second ? 25 : 0; i < (whole || second ? 26 : 25); i++) {
                rows.put(
                    new org.json.JSONObject()
                        .put("txid", String.format(Locale.ROOT, "%064x", i + 1))
                        .put("confirmations", 1)
                        .put("blockTime", 100 + i)
                        .put("vin", new org.json.JSONArray())
                        .put(
                            "vout",
                            new org.json.JSONArray()
                                .put(
                                    new org.json.JSONObject()
                                        .put("addresses", new org.json.JSONArray().put(A))
                                        .put("value", "100000001"))));
              }
              return new org.json.JSONObject()
                  .put("address", A)
                  .put("balance", "2600000026")
                  .put("unconfirmedBalance", "0")
                  .put("txs", 26)
                  .put("page", second ? 2 : 1)
                  .put("totalPages", whole ? 1 : 2)
                  .put("transactions", rows)
                  .toString();
            });
    HistoryTools tools = new HistoryTools(activity, api, () -> false);
    instrument.runOnMainSync(() -> tools.show(A));
    assertTrue(device().wait(Until.hasObject(By.textContains("第 1 / 2 页")), 10000));
    tap(By.text("下一页"));
    assertTrue(device().wait(Until.hasObject(By.textContains("第 2 / 2 页")), 10000));
    tap(By.text("上一页"));
    assertTrue(device().wait(Until.hasObject(By.textContains("第 1 / 2 页")), 10000));
    text("搜索交易 ID、地址、金额或备注").setText("00001a");
    hideKeyboard();
    tap(By.text("下一页"));
    assertTrue(device().wait(Until.hasObject(By.textContains("匹配 1 笔 · 第 2 / 2 页")), 10000));
    for (int i = 0; i < 5 && !device().hasObject(By.text("查看 / 备注 00000000")); i++)
      device().swipe(500, 1500, 500, 700, 20);
    tap(By.text("查看 / 备注 00000000"));
    text("本机交易备注（最多 160 字）").setText("Emulator invoice");
    hideKeyboard();
    tap(By.text("保存备注"));
    assertEquals(
        "Emulator invoice",
        new TransactionLedger(PublicStore.of(c)).note(A, String.format(Locale.ROOT, "%064x", 26)));
    instrument.runOnMainSync(tools::pause);
    instrument.runOnMainSync(() -> tools.show(A));
    assertTrue(device().wait(Until.hasObject(By.textContains("第 1 / 2 页")), 10000));
    IntentFilter filter = new IntentFilter(Intent.ACTION_CHOOSER);
    Instrumentation.ActivityMonitor share =
        instrument.addMonitor(
            filter, new Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null), true);
    try {
      tap(By.text("导出全部历史 CSV（最多 5000 笔）"));
      tap(By.text("导出"));
      long until = System.currentTimeMillis() + 10000;
      while (share.getHits() == 0 && System.currentTimeMillis() < until) Thread.sleep(100);
      assertEquals(1, share.getHits());
      File[] exports =
          new File(c.getCacheDir(), "public-share").listFiles((d, n) -> n.endsWith(".csv"));
      assertNotNull(exports);
      File latest =
          Arrays.stream(exports).max(Comparator.comparingLong(File::lastModified)).orElseThrow();
      String csv =
          new String(
              java.nio.file.Files.readAllBytes(latest.toPath()),
              java.nio.charset.StandardCharsets.UTF_8);
      assertEquals(27, csv.lines().count());
      assertTrue(csv.contains("Emulator invoice"));
    } finally {
      instrument.removeMonitor(share);
      instrument.runOnMainSync(tools::close);
    }
  }

  @Test
  public void watchModeBlocksSigningAndDarkModeSurvivesRestart() throws Exception {
    Context c = context();
    assertEquals("1", device().executeShellCommand("getprop ro.kernel.qemu").trim());
    c.getSharedPreferences("public_preferences", 0)
        .edit()
        .remove("observed_address")
        .putBoolean("dark_mode", false)
        .commit();
    c.getSharedPreferences("public_tools", 0).edit().remove("watch_addresses").commit();
    new WatchBook(PublicStore.of(c)).save("Public fixture", A);
    WalletVault fixture = new WalletVault(c);
    if (!fixture.exists()) {
      Intent auth =
          c.getSystemService(KeyguardManager.class)
              .createConfirmDeviceCredentialIntent(
                  "Public watch test", "Disposable emulator fixture");
      c.startActivity(auth.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
      UiObject2 pin = device().wait(Until.findObject(By.clazz("android.widget.EditText")), 15000);
      assertNotNull(pin);
      pin.setText("24682468");
      device().pressEnter();
      Thread.sleep(500);
      byte[] entropy = new byte[28];
      char[] password = "Emulator-wallet-test-42".toCharArray();
      try {
        fixture.create(entropy, password, NativeCore.address(entropy));
      } finally {
        Arrays.fill(entropy, (byte) 0);
        Arrays.fill(password, '\0');
      }
    }
    String signer = new WalletVault(c).metadata().getString("address");
    c.startActivity(
        new Intent(c, MainActivity.class)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
    tap(By.desc("切换钱包或观察地址"));
    tap(By.text("观察 Public fixture"));
    assertNotNull(device().wait(Until.findObject(By.text("只读观察 ▾")), 15000));
    tap(By.text("发送"));
    text("只读观察地址");
    tap(By.text("知道了"));
    assertEquals(signer, new WalletVault(c).metadata().getString("address"));
    tap(By.text("接收"));
    text("Pearl 主网收款地址");assertNotNull(device().wait(Until.findObject(By.text(A)),10000));
    tap(By.text("关闭"));
    tap(By.text("设置"));
    tap(By.text("切换为深色模式"));
    text("切换为浅色模式");
    assertTrue(c.getSharedPreferences("public_preferences", 0).getBoolean("dark_mode", false));
    device().pressHome();
    c.startActivity(
        new Intent(c, MainActivity.class)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
    assertNotNull(device().wait(Until.findObject(By.text("只读观察 ▾")), 15000));
    tap(By.text("设置"));
    tap(By.text("切换为浅色模式"));
    tap(By.text("钱包"));
    tap(By.desc("切换钱包或观察地址"));
    tap(By.text("使用手机钱包"));
    assertEquals(signer, new WalletVault(c).metadata().getString("address"));
  }
}
