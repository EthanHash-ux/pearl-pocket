package ai.pearl.wallet;

import static org.junit.Assert.*;

import android.app.*;
import android.app.job.*;
import android.appwidget.*;
import android.content.*;
import android.os.*;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.*;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class FortuneWidgetTest {
  private static final String ADDRESS =
      "prl1pr6yuq8u2r95wjzzgpdy8cpnncpl7l8zgy6x5q0367pnc53s2famqg7pt74";

  @Test
  public void livePoolAndPublicMinerHaveSeparateEstimatedAndConnectionData() throws Exception {
    FortuneApi.Snapshot pool = new FortuneApi().fetch("");
    assertTrue(pool.address.isEmpty());
    assertNotNull(pool.window(1).rate);
    assertNotNull(pool.window(8).rate);
    assertNotNull(pool.window(24).rate);
    FortuneApi.Snapshot miner = new FortuneApi().fetch(ADDRESS);
    assertEquals(ADDRESS, miner.address);
    assertNotNull(miner.window(1).rate);
    assertNotNull(miner.online);
    assertEquals("—", miner.window(1).acceptance());
    assertFalse(miner.online);
  }

  @Test
  public void actualLauncherPinRefreshConfigurationAndStaleResponseIsolation() throws Exception {
    Instrumentation instrument = InstrumentationRegistry.getInstrumentation();
    Context c = instrument.getTargetContext();
    UiDevice d = UiDevice.getInstance(instrument);
    assertEquals("1", d.executeShellCommand("getprop ro.kernel.qemu").trim());
    Activity main = launch(instrument, c);
    MiningWidget.Config pool = new MiningWidget.Config("PearlFortune 矿池", "");
    int[] before = MiningWidget.ids(c);
    MiningWidget.Config pinInput = pool;
    instrument.runOnMainSync(() -> MiningWidget.pin(main, pinInput));
    UiObject2 add =
        d.wait(
            Until.findObject(
                By.clazz("android.widget.Button")
                    .text(java.util.regex.Pattern.compile("(?i)add.*|添加.*"))),
            10000);
    if (add == null) { // Pixel Launcher uses a TextView action on some builds.
      add =
          d.wait(
              Until.findObject(
                  By.text(
                      java.util.regex.Pattern.compile(
                          "(?i)add automatically|add to home screen|添加到主屏幕|自动添加"))),
              5000);
    }
    assertNotNull("Launcher must show its pin confirmation", add);
    add.click();
    int id = -1;
    for (int attempt = 0; attempt < 80 && id < 0; attempt++) {
      for (int v : MiningWidget.ids(c)) {
        boolean old = false;
        for (int b : before) old |= b == v;
        if (!old && MiningWidget.config(c, v) != null) id = v;
      }
      if (id < 0) Thread.sleep(250);
    }
    assertTrue("Launcher pin callback must configure the allocated ID", id >= 0);
    pool = MiningWidget.config(c, id);
    assertEquals("PearlFortune 矿池", pool.name);
    assertTrue(MiningWidget.config(c, id).address.isEmpty());
    // Exercise the actual Android background service, with the wallet activity paused.
    d.pressHome();
    UiObject2 refresh = d.wait(Until.findObject(By.res(c.getPackageName(), "mw_refresh")), 10000);
    assertNotNull(refresh);
    refresh.click();
    String forced =
        d.executeShellCommand("cmd jobscheduler run -f ai.pearl.wallet " + MiningWidgetJob.MANUAL);
    assertTrue(forced, forced.contains("Running job"));
    FortuneApi.Snapshot live = null;
    for (int attempt = 0; attempt < 120; attempt++) {
      live = MiningWidget.snapshot(c, id, MiningWidget.config(c, id));
      if (live != null) break;
      Thread.sleep(500);
    }
    assertNotNull("Background job fetches public pool metrics", live);
    assertNotNull(live.window(1).rate);
    assertNotNull(
        ((JobScheduler) c.getSystemService(Context.JOB_SCHEDULER_SERVICE))
            .getPendingJob(MiningWidgetJob.PERIODIC));
    final int widget = id;
    Intent setup =
        new Intent(c, MiningWidgetConfigActivity.class)
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    c.startActivity(setup);
    assertNotNull(d.wait(Until.findObject(By.text("算力与价格小组件")), 10000));
    UiObject2 personal = d.findObject(By.text("我的矿工算力"));
    assertNotNull(personal);
    personal.click();
    UiObject2 address = null;
    // UiAutomator cannot inspect hints consistently across Android versions; fall back to the form
    // order.
    if (address == null) {
      java.util.List<UiObject2> fields = d.findObjects(By.clazz("android.widget.EditText"));
      assertEquals(2, fields.size());
      address = fields.get(1);
    }
    address.setText(ADDRESS);
    d.findObject(By.text("保存小组件")).click();
    for (int attempt = 0;
        attempt < 30 && !ADDRESS.equals(MiningWidget.config(c, id).address);
        attempt++) Thread.sleep(200);
    MiningWidget.Config next = MiningWidget.config(c, id);
    assertEquals(ADDRESS, next.address);
    MiningWidgetJob.cancel(c);
    Thread.sleep(1000);
    MiningWidget.result(c, widget, pool, live, "");
    assertNull(
        "Old pool request cannot overwrite new personal config",
        MiningWidget.snapshot(c, widget, next));
    FortuneApi.Snapshot miner = new FortuneApi().fetch(ADDRESS);
    MiningWidget.result(c, widget, next, miner, "");
    MiningWidget.result(c, widget, next, null, "offline");
    assertNotNull(MiningWidget.snapshot(c, widget, next));
    assertTrue(MiningWidget.prefs(c).getBoolean("error_" + widget, false));
    d.pressHome();
    // Return to the real launcher widget and open precisely the configured miner.
    UiObject2 label = d.wait(Until.findObject(By.res(c.getPackageName(), "mw_name")), 10000);
    assertNotNull("Real launcher widget must be visible", label);
    label.click();
    assertTrue(d.wait(Until.hasObject(By.text("PearlFortune 矿工详情")), 10000));
    instrument.runOnMainSync(() -> main.finish());
  }

  @Test
  public void invalidLauncherConfigurationCannotWriteState() throws Exception {
    Instrumentation i = InstrumentationRegistry.getInstrumentation();
    Context c = i.getTargetContext();
    String before = MiningWidget.prefs(c).getAll().toString();
    Instrumentation.ActivityMonitor monitor =
        i.addMonitor(MiningWidgetConfigActivity.class.getName(), null, false);
    c.startActivity(
        new Intent(c, MiningWidgetConfigActivity.class)
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -42)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    Activity a = i.waitForMonitorWithTimeout(monitor, 5000);
    i.removeMonitor(monitor);
    assertNotNull(a);
    Thread.sleep(250);
    assertTrue(a.isFinishing());
    assertEquals(before, MiningWidget.prefs(c).getAll().toString());
  }

  private Activity launch(Instrumentation i, Context c) {
    Instrumentation.ActivityMonitor monitor =
        i.addMonitor(MainActivity.class.getName(), null, false);
    c.startActivity(
        new Intent(c, MainActivity.class)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
    Activity a = i.waitForMonitorWithTimeout(monitor, 15000);
    i.removeMonitor(monitor);
    assertNotNull(a);
    i.waitForIdleSync();
    return a;
  }

  @Test
  public void nativeMinerFormShowsPublicContributionWithoutChangingSigningAddress()
      throws Exception {
    Instrumentation i = InstrumentationRegistry.getInstrumentation();
    Context c = i.getTargetContext();
    UiDevice d = UiDevice.getInstance(i);
    String wallet = new WalletVault(c).metadata().getString("address");
    WatchBook book = new WatchBook(PublicStore.of(c), "fortune_miner_addresses");
    book.remove(ADDRESS);
    Activity main = launch(i, c);
    try {
      tap(d, By.text("挖矿"));
      tap(d, By.text("PearlFortune 我的矿工"));
      tap(d, By.text("添加 PearlFortune 矿工"));
      java.util.List<UiObject2> fields = d.findObjects(By.clazz("android.widget.EditText"));
      assertEquals(2, fields.size());
      fields.get(0).setText("公开测试矿工");
      fields.get(1).setText(ADDRESS);
      d.findObject(By.text("保存并查看矿工")).click();
      assertTrue(d.wait(Until.hasObject(By.text("PearlFortune 矿工详情")), 10000));
      assertTrue(d.wait(Until.hasObject(By.textContains("算力更新")), 40000));
      assertTrue(d.hasObject(By.textContains("接受率 —")));
      assertTrue(
          book.list().stream().anyMatch(e -> e.address.equals(ADDRESS) && e.name.equals("公开测试矿工")));
      assertEquals(wallet, new WalletVault(c).metadata().getString("address"));
    } finally {
      book.remove(ADDRESS);
      i.runOnMainSync(main::finish);
    }
  }

  private void tap(UiDevice device, BySelector selector) {
    for (int attempt = 0; attempt < 3; attempt++) {
      device.waitForIdle();
      UiObject2 target = device.wait(Until.findObject(selector), 10000);
      assertNotNull(target);
      try {
        target.click();
        return;
      } catch (StaleObjectException redraw) {
        if (attempt == 2) throw redraw;
      }
    }
  }
}
