package ai.pearl.wallet;

import static org.junit.Assert.*;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.view.WindowManager;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.By;
import androidx.test.uiautomator.UiDevice;
import androidx.test.uiautomator.UiObject2;
import androidx.test.uiautomator.Until;
import java.io.File;
import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Opt-in public-screen captures on the disposable emulator; absent from release APKs. */
@RunWith(AndroidJUnit4.class)
public class DesignCaptureTest {
  @Test
  public void capturePublicScreensOfAKnownPublicFixtureWallet() throws Exception {
    Assume.assumeTrue(
        "Explicit design-capture run only",
        "true".equals(InstrumentationRegistry.getArguments().getString("capture_design")));
    Instrumentation instrument = InstrumentationRegistry.getInstrumentation();
    UiDevice device = UiDevice.getInstance(instrument);
    Context context = instrument.getTargetContext();
    assertEquals("1", device.executeShellCommand("getprop ro.kernel.qemu").trim());
    String address = new WalletVault(context).metadata().getString("address");
    boolean publicFixture = false;
    for (int bytes : new int[] {16, 20, 24, 28, 32})
      if (address.equals(NativeCore.address(new byte[bytes]))) publicFixture = true;
    assertTrue("Never capture an unknown wallet", publicFixture);
    context
        .getSharedPreferences("public_preferences", Context.MODE_PRIVATE)
        .edit()
        .putBoolean("hide_balances", false)
        .putBoolean("dark_mode", false)
        .remove("observed_address")
        .commit();
    Instrumentation.ActivityMonitor monitor =
        instrument.addMonitor(MainActivity.class.getName(), null, false);
    context.startActivity(
        new Intent(context, MainActivity.class)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
    Activity activity = instrument.waitForMonitorWithTimeout(monitor, 15000);
    instrument.removeMonitor(monitor);
    assertNotNull(activity);
    assertTrue(device.wait(Until.hasObject(By.text("掌珠钱包")), 10000));
    assertTrue(
        (activity.getWindow().getAttributes().flags & WindowManager.LayoutParams.FLAG_SECURE) != 0);
    File output = context.getExternalFilesDir(null);
    assertNotNull(output);
    try {
      // Instrumentation temporarily permits captures of these verified public screens only.
      // The shipping app always keeps FLAG_SECURE; no intent or preference can bypass it.
      instrument.runOnMainSync(
          () -> activity.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SECURE));
      device.wait(Until.hasObject(By.textContains("BigONE ·")), 40000);
      Thread.sleep(900);
      capture(device, new File(output, "ui-wallet.png"));
      tap(device, "行情");
      device.wait(Until.hasObject(By.textContains("收盘价")), 30000);
      Thread.sleep(600);
      capture(device, new File(output, "ui-market.png"));
      captureCrossChain(instrument, activity, device, new File(output, "ui-cross-chain.png"));
      tap(device, "挖矿");
      device.wait(Until.hasObject(By.textContains("接收 ")), 30000);
      Thread.sleep(600);
      capture(device, new File(output, "ui-mining.png"));
      captureFortune(instrument, activity, device, new File(output, "ui-fortune.png"));
      tap(device, "设置");
      Thread.sleep(400);
      capture(device, new File(output, "ui-settings.png"));
    } finally {
      instrument.runOnMainSync(
          () -> {
            activity.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
            activity.finish();
          });
    }
    context
        .getSharedPreferences("public_preferences", Context.MODE_PRIVATE)
        .edit()
        .putBoolean("dark_mode", true)
        .commit();
    Instrumentation.ActivityMonitor nightMonitor =
        instrument.addMonitor(MainActivity.class.getName(), null, false);
    context.startActivity(
        new Intent(context, MainActivity.class)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
    Activity night = instrument.waitForMonitorWithTimeout(nightMonitor, 15000);
    instrument.removeMonitor(nightMonitor);
    assertNotNull(night);
    assertTrue(
        (night.getWindow().getAttributes().flags & WindowManager.LayoutParams.FLAG_SECURE) != 0);
    try {
      instrument.runOnMainSync(
          () -> night.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SECURE));
      device.wait(Until.hasObject(By.textContains("BigONE ·")), 40000);
      Thread.sleep(600);
      capture(device, new File(output, "ui-wallet-dark.png"));
      tap(device, "行情");
      device.wait(Until.hasObject(By.textContains("收盘价")), 30000);
      Thread.sleep(600);
      capture(device, new File(output, "ui-market-dark.png"));
    } finally {
      instrument.runOnMainSync(
          () -> {
            night.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
            night.finish();
          });
      context
          .getSharedPreferences("public_preferences", Context.MODE_PRIVATE)
          .edit()
          .putBoolean("dark_mode", false)
          .commit();
    }
  }

  private void tap(UiDevice device, String name) {
    UiObject2 item = device.wait(Until.findObject(By.text(name)), 10000);
    assertNotNull(item);
    item.click();
  }

  private void captureCrossChain(
      Instrumentation instrument, Activity activity, UiDevice device, File output)
      throws Exception {
    new androidx.test.uiautomator.UiScrollable(
            new androidx.test.uiautomator.UiSelector().scrollable(true))
        .scrollIntoView(new androidx.test.uiautomator.UiSelector().text("打开跨链与价差工作台"));
    tap(device, "打开跨链与价差工作台");
    assertTrue(device.wait(Until.hasObject(By.text("跨链与价差")), 15000));
    java.lang.reflect.Field toolsField = MainActivity.class.getDeclaredField("crossChainTools");
    toolsField.setAccessible(true);
    CrossChainTools tools = (CrossChainTools) toolsField.get(activity);
    java.lang.reflect.Field dialogField = CrossChainTools.class.getDeclaredField("dialog");
    dialogField.setAccessible(true);
    android.app.AlertDialog dialog = (android.app.AlertDialog) dialogField.get(tools);
    assertNotNull(dialog);
    try {
      assertTrue(
          (dialog.getWindow().getAttributes().flags & WindowManager.LayoutParams.FLAG_SECURE) != 0);
      instrument.runOnMainSync(
          () -> dialog.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SECURE));
      assertTrue(device.wait(Until.hasObject(By.textContains("转入 100 PRL")), 40000));
      assertTrue(device.wait(Until.hasObject(By.textContains("买一 ")), 40000));
      assertTrue(device.wait(Until.hasObject(By.textContains("卖 1 WPRL →")), 40000));
      Thread.sleep(500);
      capture(device, output);
    } finally {
      instrument.runOnMainSync(
          () -> {
            dialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
            dialog.dismiss();
          });
    }
  }

  private void capture(UiDevice device, File output) {
    assertFalse(
        "Never capture a secret form", device.hasObject(By.clazz("android.widget.EditText")));
    assertFalse(device.hasObject(By.text("离线助记词备份")));
    assertFalse(device.hasObject(By.text("恢复手机钱包")));
    assertTrue(device.takeScreenshot(output));
  }

  private void captureFortune(
      Instrumentation instrument, Activity activity, UiDevice device, File output)
      throws Exception {
    java.lang.reflect.Field field = MainActivity.class.getDeclaredField("fortuneTools");
    field.setAccessible(true);
    FortuneTools tools = (FortuneTools) field.get(activity);
    instrument.runOnMainSync(() -> tools.detail("PearlFortune 矿池", ""));
    java.lang.reflect.Field df = FortuneTools.class.getDeclaredField("dialog");
    df.setAccessible(true);
    android.app.AlertDialog dialog = (android.app.AlertDialog) df.get(tools);
    assertNotNull(dialog);
    try {
      assertTrue(
          (dialog.getWindow().getAttributes().flags & WindowManager.LayoutParams.FLAG_SECURE) != 0);
      instrument.runOnMainSync(
          () -> dialog.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SECURE));
      assertTrue(device.wait(Until.hasObject(By.textContains("算力更新")), 40000));
      Thread.sleep(400);
      capture(device, output);
    } finally {
      instrument.runOnMainSync(
          () -> {
            dialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
            dialog.dismiss();
          });
    }
    int[] ids = MiningWidget.ids(activity);
    assertTrue("Capture an actual launcher widget", ids.length > 0);
    MiningWidget.Config config = new MiningWidget.Config("PearlFortune · 矿池", "");
    MiningWidget.save(activity, ids[0], config);
    MiningWidgetJob.cancel(activity);
    Thread.sleep(500);
    FortuneApi.Snapshot data = new FortuneApi().fetch("");
    MiningWidget.result(activity, ids[0], config, data, "");
    MarketQuote q = new PearlApi().marketQuote();
    PriceWidget.cache(activity, q.usdt, q.change24h, q.receivedAt, true);
    device.pressHome();
    UiObject2 widget =
        device.wait(Until.findObject(By.res(activity.getPackageName(), "mw_name")), 10000);
    for (int i = 0; i < 4 && widget == null; i++) {
      device.swipe(950, 1200, 150, 1200, 20);
      widget = device.wait(Until.findObject(By.res(activity.getPackageName(), "mw_name")), 2000);
    }
    assertNotNull("Launcher widget is displayed", widget);
    Thread.sleep(400);
    capture(device, new File(output.getParentFile(), "ui-mining-widget.png"));
    activity.startActivity(
        new Intent(activity, MainActivity.class)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP));
    assertTrue(device.wait(Until.hasObject(By.text("掌珠钱包")), 10000));
  }
}
