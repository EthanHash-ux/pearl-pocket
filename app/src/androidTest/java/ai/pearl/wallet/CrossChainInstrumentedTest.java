package ai.pearl.wallet;

import static org.junit.Assert.*;

import android.app.*;
import android.content.*;
import android.view.WindowManager;
import android.widget.*;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.*;
import java.math.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class CrossChainInstrumentedTest {
  @Test
  public void widgetIgnoresDollarOnlyFallbackAndPreservesUsdtCache() {
    Context c = context();
    long at = System.currentTimeMillis() / 1000;
    PriceWidget.cache(c, new BigDecimal("1.23"), BigDecimal.ZERO, at, true);
    PriceWidget.cache(c, null, BigDecimal.ONE, at + 1, true);
    assertEquals("1.23", c.getSharedPreferences("price_widget", 0).getString("usdt", ""));
    assertEquals(at, c.getSharedPreferences("price_widget", 0).getLong("at", 0));
  }

  static final String ETH = "0x0000000000000000000000000000000000000001";
  final Instrumentation instrument = InstrumentationRegistry.getInstrumentation();

  Context context() {
    return instrument.getTargetContext();
  }

  UiDevice device() {
    return UiDevice.getInstance(instrument);
  }

  UiObject2 text(String s) {
    UiObject2 v = device().wait(Until.findObject(By.text(s)), 15000);
    assertNotNull("Missing " + s, v);
    return v;
  }

  String publicTexts() {
    StringBuilder b = new StringBuilder();
    for (UiObject2 v : device().findObjects(By.clazz("android.widget.TextView")))
      b.append(v.getText()).append(" | ");
    return b.toString();
  }

  void hideKeyboard() throws Exception {
    if (device().executeShellCommand("dumpsys input_method").contains("mInputShown=true"))
      device().pressBack();
  }

  void tap(String s) throws Exception {
    if (!device().wait(Until.hasObject(By.text(s)), 1500))
      new UiScrollable(new UiSelector().scrollable(true)).scrollIntoView(new UiSelector().text(s));
    text(s).click();
  }

  Activity launch() throws Exception {
    assertEquals("1", device().executeShellCommand("getprop ro.kernel.qemu").trim());
    Instrumentation.ActivityMonitor m =
        instrument.addMonitor(MainActivity.class.getName(), null, false);
    context()
        .startActivity(
            new Intent(context(), MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
    Activity a = instrument.waitForMonitorWithTimeout(m, 15000);
    instrument.removeMonitor(m);
    assertNotNull(a);
    assertTrue((a.getWindow().getAttributes().flags & WindowManager.LayoutParams.FLAG_SECURE) != 0);
    return a;
  }

  static class Memory implements PublicStore.Storage {
    final Map<String, String> map = new HashMap<>();

    public String read(String k) {
      return map.getOrDefault(k, "[]");
    }

    public void write(String k, String v) {
      map.put(k, v);
    }
  }

  @Test
  public void externalEthereumChecksumRunsLocallyThroughJni() throws Exception {
    assertEquals(
        "0xfB6916095ca1df60bB79Ce92cE3Ea74c37c5d359",
        EvmPublicApi.checkedAddress("0xfb6916095ca1df60bb79ce92ce3ea74c37c5d359"));
    assertThrows(
        IllegalArgumentException.class,
        () -> EvmPublicApi.checkedAddress("0xfB6916095ca1df60bB79Ce92cE3Ea74c37c5d358"));
  }

  @Test
  public void liveBridgeQuotesAndDepositBindingUsePublicInputsOnly() throws Exception {
    BridgeApi api = new BridgeApi();
    BridgeApi.Status s = api.status();
    assertFalse("Bridge currently paused", s.paused);
    BigInteger q = new BigInteger("10000000000");
    BridgeApi.Quote mint = api.quote(true, q), burn = api.quote(false, q);
    assertEquals(q, mint.fee.add(mint.net));
    assertEquals(q, burn.fee.add(burn.net));
    assertTrue(mint.confirmations > 0);
    String deposit = api.deposit(ETH);
    assertEquals(deposit, PearlAddress.normalize(deposit));
    BridgeBook b = new BridgeBook(new Memory());
    BridgeApi.MintPlan plan = api.prepare(q, ETH, b);
    api.recheck(plan, b);
    assertEquals(deposit, plan.deposit);
  }

  @Test
  public void liveEthereumBalanceAndDexQuotesVerifyMainnetPoolAndPrecision() throws Exception {
    EvmPublicApi api = new EvmPublicApi();
    EvmPublicApi.Balance balance = api.balance(ETH);
    assertTrue(balance.grains.signum() >= 0);
    assertTrue(balance.wei.signum() >= 0);
    String block = api.block();
    api.verifyPool(block);
    EvmPublicApi.DexQuote sell = api.quote(BigInteger.valueOf(100000000), true, block),
        buy = api.quote(BigInteger.valueOf(100000000), false, block);
    assertTrue(sell.usdt.signum() > 0);
    assertTrue(buy.usdt.compareTo(sell.usdt) > 0);
    assertEquals(block, sell.block);
    assertEquals(block, buy.block);
  }

  @Test
  public void liveLighterUsesPinnedPerpAndRejectsSubLotAmount() throws Exception {
    LighterMarket m = LighterMarket.fetch(PearlApi::httpsGet);
    TradeBook.Fill f = m.shortFill(new BigDecimal("100"));
    assertTrue(f.quote.signum() > 0);
    assertThrows(IllegalArgumentException.class, () -> m.shortFill(new BigDecimal("100.001")));
    assertTrue(
        LighterMarket.settledFunding(PearlApi::httpsGet, System.currentTimeMillis() / 1000)
            .contains("历史"));
  }

  @Test
  public void nativeBridgeReviewRequiresConsentAndDoesNotMoveFunds() throws Exception {
    Activity a = launch();
    AtomicReference<BridgeApi.MintPlan> accepted = new AtomicReference<>();
    CrossChainTools tools =
        new CrossChainTools(a, () -> "", () -> false, () -> false, accepted::set);
    try {
      instrument.runOnMainSync(tools::show);
      tap("PRL → WPRL 转入桥");
      device().wait(Until.findObject(By.desc("转入数量（PRL）")), 10000).setText("100");
      device().wait(Until.findObject(By.desc("Ethereum 收款地址")), 10000).setText(ETH);
      hideKeyboard();
      tap("获取桥报价");
      boolean reviewed = device().wait(Until.hasObject(By.text("核对跨链收款人")), 45000);
      assertTrue("Bridge public screen: " + publicTexts(), reviewed);
      if (!device().hasObject(By.text("继续预览 PRL 转账")))
        new UiScrollable(new UiSelector().scrollable(true))
            .scrollIntoView(new UiSelector().text("继续预览 PRL 转账"));
      assertFalse(text("继续预览 PRL 转账").isEnabled());
      assertNull(accepted.get());
      device().findObject(By.clazz("android.widget.CheckBox")).click();
      tap("继续预览 PRL 转账");
      assertNotNull(accepted.get());
      assertEquals(new BigInteger("10000000000"), accepted.get().quote.amount);
      assertEquals(ETH, accepted.get().eth);
    } finally {
      instrument.runOnMainSync(
          () -> {
            tools.close();
            a.finish();
          });
    }
  }

  @Test
  public void observationModeCannotOpenNativeWrapSigningFlow() throws Exception {
    Activity a = launch();
    CrossChainTools tools =
        new CrossChainTools(
            a,
            () -> "",
            () -> true,
            () -> false,
            p -> {
              throw new AssertionError("Watch mode must not sign");
            });
    try {
      instrument.runOnMainSync(tools::show);
      tap("PRL → WPRL 转入桥");
      assertTrue(device().wait(Until.hasObject(By.text("观察地址不能转入桥")), 10000));
      assertFalse(device().hasObject(By.clazz("android.widget.EditText")));
    } finally {
      instrument.runOnMainSync(
          () -> {
            tools.close();
            a.finish();
          });
    }
  }

  @Test
  public void nativeCostCalculatorRequiresBudgetAndShowsBothRealRoutes() throws Exception {
    Activity a = launch();
    CrossChainTools tools =
        new CrossChainTools(
            a,
            () -> "",
            () -> false,
            () -> false,
            p -> {
              throw new AssertionError("Calculator cannot send");
            });
    try {
      instrument.runOnMainSync(tools::show);
      tap("跨链现货成本试算");
      tap("获取新报价并试算");
      assertTrue(device().wait(Until.hasObject(By.textContains("请填写完整的成本")), 5000));
      device().findObject(By.desc("其它总成本（USDT，含 gas、提币、Pearl 网络费；必填）")).setText("3");
      hideKeyboard();
      tap("获取新报价并试算");
      boolean calculated = device().wait(Until.hasObject(By.text("跨链路线成本")), 45000);
      assertTrue("Cost public screen: " + publicTexts(), calculated);
      assertTrue(device().hasObject(By.text("PRL 买入 → 桥 → WPRL 卖出")));
      assertFalse(device().hasObject(By.text("验证并发送")));
      tap("重新填写并报价");
      assertTrue(device().hasObject(By.text("跨链现货测算")));
    } finally {
      instrument.runOnMainSync(
          () -> {
            tools.close();
            a.finish();
          });
    }
  }
}
