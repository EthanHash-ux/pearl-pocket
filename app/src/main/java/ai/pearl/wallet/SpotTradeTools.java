package ai.pearl.wallet;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.net.Uri;
import android.text.InputType;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import org.json.JSONArray;
import org.json.JSONObject;

/** Native funding, price-bound spot orders and separately reviewed withdrawals. */
final class SpotTradeTools {
  private final Activity activity;
  private final Supplier<WalletFlow> wallet;
  private final Supplier<String> slot, name;
  private final BooleanSupplier observing, hidden;
  private final SpotTradeApi api;
  private final SpotUsdcApi usdc;
  private final ExecutorService worker = Executors.newSingleThreadExecutor();
  private AlertDialog dialog;
  private volatile boolean active, busy;
  private volatile int generation;

  SpotTradeTools(
      Activity a,
      Supplier<WalletFlow> w,
      Supplier<String> s,
      Supplier<String> n,
      BooleanSupplier o,
      BooleanSupplier h) {
    this(a, w, s, n, o, h, new SpotTradeApi(), new SpotUsdcApi());
  }

  SpotTradeTools(
      Activity a,
      Supplier<WalletFlow> w,
      Supplier<String> s,
      Supplier<String> n,
      BooleanSupplier o,
      BooleanSupplier h,
      SpotTradeApi api,
      SpotUsdcApi usdc) {
    activity = a;
    wallet = w;
    slot = s;
    name = n;
    observing = o;
    hidden = h;
    this.api = api;
    this.usdc = usdc;
  }

  boolean canLeave() {
    return !busy;
  }

  void resume() {
    active = true;
  }

  void pause() {
    active = false;
    generation++;
    if (dialog != null) {
      dialog.dismiss();
      dialog = null;
    }
  }

  void close() {
    pause();
    worker.shutdown();
  }

  private void ui(int token, Runnable r) {
    activity.runOnUiThread(
        () -> {
          if (active && token == generation && !activity.isDestroyed()) r.run();
        });
  }

  private interface Work {
    void run(int token) throws Exception;
  }

  private void work(Work task) {
    if (busy || !active) return;
    busy = true;
    int token = generation;
    worker.execute(
        () -> {
          try {
            task.run(token);
          } catch (Exception e) {
            ui(token, () -> error(e));
          } finally {
            busy = false;
          }
        });
  }

  private void error(Exception e) {
    wallet.get().notice("现货操作未完成", e.getMessage() == null ? "请检查平台记录；已签名请求会保留。" : e.getMessage());
  }

  private void note(LinearLayout v, String s) {
    v.addView(PearlDesign.note(activity, s));
  }

  private void button(LinearLayout v, String s, Runnable r) {
    v.addView(
        PearlDesign.button(
            activity,
            s,
            PearlDesign.TEAL,
            PearlDesign.PALE,
            x -> {
              if (!busy) r.run();
            }));
  }

  private void display(String title, LinearLayout v) {
    if (dialog != null) dialog.dismiss();
    ScrollView scroll = new ScrollView(activity);
    scroll.addView(v);
    dialog =
        new AlertDialog.Builder(activity)
            .setTitle(title)
            .setView(scroll)
            .setNegativeButton("关闭", (d, w) -> generation++)
            .create();
    dialog.setOnCancelListener(d -> generation++);
    dialog.show();
    PearlDesign.dialog(dialog);
  }

  private void browser() {
    activity.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://pearl-trade.com/")));
  }

  private String amount(BigInteger n, int dp) {
    return hidden.getAsBoolean() ? "••••" : SpotTradeApi.display(n, dp);
  }

  private static String checksum(String eth) {
    try {
      return EvmPublicApi.checkedAddress(eth);
    } catch (Exception e) {
      throw new IllegalArgumentException("交易地址校验失败", e);
    }
  }

  private SpotTradeStore store(String selected, String eth) throws Exception {
    return new SpotTradeStore(activity, selected, eth, wallet.get().address());
  }

  void show() {
    if (!active || busy) return;
    if (observing.getAsBoolean() || !wallet.get().exists()) {
      wallet.get().notice("选择手机钱包", "切换到已创建或恢复的钱包再买卖 PRL。");
      return;
    }
    try {
      String selected = slot.get(),
          eth = new EthereumAccount(activity, selected).load(wallet.get().address());
      if (eth.isEmpty()) {
        LinearLayout v = PearlDesign.form(activity);
        note(
            v,
            "为当前钱包生成 EVM 地址，用于 pearl-trade 账户签名和接收 Arbitrum USDC。与 DeFi"
                + " 使用同一派生地址，网络不同。私钥由当前助记词在本机派生，不会交给平台。");
        button(v, "验证并生成交易账户地址", () -> wallet.get().ethereumAddress(r -> show()));
        display("PRL / USDC 现货", v);
        return;
      }
      JSONObject pending = store(selected, eth).pending();
      if (pending != null) {
        pending(selected, eth, pending);
        return;
      }
      work(
          token -> {
            SpotTradeApi.Balances b = api.balances(eth);
            BigInteger onchain = null;
            try {
              onchain = usdc.balance(eth);
            } catch (Exception ignored) {
              /* Trading remains usable when independent RPC is unavailable. */
            }
            BigInteger balance = onchain;
            ui(token, () -> home(selected, eth, b, balance));
          });
    } catch (Exception e) {
      error(e);
    }
  }

  private void home(String selected, String eth, SpotTradeApi.Balances b, BigInteger onchain) {
    LinearLayout v = PearlDesign.form(activity);
    note(
        v,
        name.get() + " · pearl-trade\nPRL 原生现货 / Arbitrum USDC\n账户与 USDC 收款地址\n" + checksum(eth));
    button(
        v,
        "复制 Arbitrum USDC 收款地址",
        () ->
            ((ClipboardManager) activity.getSystemService(Activity.CLIPBOARD_SERVICE))
                .setPrimaryClip(ClipData.newPlainText("Arbitrum One USDC 地址", checksum(eth))));
    note(
        v,
        "平台可交易余额\n"
            + amount(b.prl, 8)
            + " PRL · "
            + amount(b.usdc, 6)
            + " USDC\n平台冻结余额\n"
            + amount(b.prlReserved, 8)
            + " PRL · "
            + amount(b.usdcReserved, 6)
            + " USDC\n\n钱包链上余额（Arbitrum）\n"
            + (onchain == null ? "查询暂不可用" : amount(onchain, 6) + " USDC"));
    note(
        v,
        "开发测试功能，尚未用实币验证。\n① 将 PRL 充值到平台 → ② 确认卖单 → ③ 成交后申请 USDC 提现 → ④ 核验链上到账。\n"
            + "平台订单簿采用托管余额。充值不是卖出，成交不是钱包到账；提现须运营方处理，时间不能保证。");
    button(v, "充值原生 PRL", () -> deposit(selected, eth));
    button(v, "卖出 PRL，获得 USDC", () -> editor(selected, eth, false));
    button(v, "用平台 USDC 买入 PRL", () -> editor(selected, eth, true));
    button(v, "提现 USDC 到本钱包", () -> withdraw(selected, eth, false));
    button(v, "提现 PRL 到本钱包", () -> withdraw(selected, eth, true));
    button(v, "订单与撤单", () -> orders(selected, eth));
    button(v, "提现与到账查询", () -> withdrawals(selected, eth));
    button(v, "充值与本机操作记录", () -> records(selected, eth));
    button(v, "刷新余额", this::show);
    button(v, "打开 pearl-trade 官网", this::browser);
    note(
        v,
        "USDC 仅为 Arbitrum One 原生 USDC，chain ID 42161，合约\n"
            + SpotTradeApi.USDC
            + "\n"
            + "Ethereum 的 Aave USDC 余额与此分开。当前版本买单使用已有平台余额；USDC 充值及普通 Arbitrum"
            + " 转出需兼容外部钱包。本机不自动卖出、追价或提现。");
    display("PRL / USDC 现货", v);
  }

  private EditText input(LinearLayout v, String hint, String value) {
    EditText e = new EditText(activity);
    e.setHint(hint);
    e.setContentDescription(hint);
    e.setSingleLine(true);
    e.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
    PearlDesign.input(e);
    e.setText(value);
    v.addView(e);
    return e;
  }

  private void deposit(String selected, String eth) {
    work(
        token -> {
          api.market();
          String deposit = api.depositAddress(eth, true);
          SpotTradeStore store = store(selected, eth);
          String old = store.deposit();
          if (!old.isEmpty() && !old.equals(deposit))
            throw new IllegalArgumentException("平台充值地址已变化，已阻止发送");
          store.pin(deposit);
          ui(
              token,
              () -> {
                LinearLayout v = PearlDesign.form(activity);
                note(
                    v,
                    "平台账户\n"
                        + checksum(eth)
                        + "\nPearl 主网充值地址\n"
                        + deposit
                        + "\n\n首次地址来自平台 API，本机无法独立推导。请在官网连接同一 EVM 账户并核对完整地址；后续地址变化会阻止充值。");
                button(v, "打开官网核对充值地址", this::browser);
                EditText quantity = input(v, "充值金额（PRL）", "");
                CheckBox checked = new CheckBox(activity);
                checked.setText(R.string.spot_deposit_verified);
                v.addView(checked);
                button(
                    v,
                    "预览 PRL 充值",
                    () -> {
                      if (!checked.isChecked()) {
                        quantity.setError("先核对平台完整充值地址");
                        return;
                      }
                      try {
                        BigInteger q = PearlAmount.parsePositivePrl(quantity.getText().toString());
                        if (!selected.equals(slot.get()))
                          throw new IllegalArgumentException("钱包已切换");
                        if (dialog != null) dialog.dismiss();
                        wallet
                            .get()
                            .spotDeposit(
                                new SpotTradeStore.Deposit(
                                    selected, eth, wallet.get().address(), deposit, q));
                      } catch (Exception e) {
                        quantity.setError(e.getMessage());
                      }
                    });
                display("充值原生 PRL", v);
              });
        });
  }

  private void editor(String selected, String eth, boolean buy) {
    work(
        token -> {
          SpotTradeApi.Market market = api.market();
          TradeBook book = api.book();
          String best =
              (buy ? book.asks : book.bids).get(0).price.stripTrailingZeros().toPlainString();
          ui(
              token,
              () -> {
                LinearLayout v = PearlDesign.form(activity);
                note(
                    v,
                    (buy ? "最高买入价" : "最低卖出价")
                        + "绑定在签名中。价格满足的部分可立即成交，剩余数量会留在订单簿，可手动撤单。\n平台"
                        + (buy ? "买入" : "卖出")
                        + "费率（普通账户上限估算）· "
                        + BigDecimal.valueOf(buy ? market.buyFee : market.sellFee, 2)
                            .stripTrailingZeros()
                            .toPlainString()
                        + "%\n当前最佳"
                        + (buy ? "卖" : "买")
                        + "价 · "
                        + best
                        + " USDC / PRL");
                EditText quantity = input(v, "交易数量（PRL，最少 1）", ""),
                    price = input(v, buy ? "最高买入价（USDC / PRL）" : "最低卖出价（USDC / PRL）", best);
                button(
                    v,
                    "获取真实盘口并预览订单",
                    () -> {
                      try {
                        BigInteger q = SpotTradeApi.qty(quantity.getText().toString()),
                            p = SpotTradeApi.price(price.getText().toString());
                        if (dialog != null) dialog.dismiss();
                        preview(selected, eth, buy, q, p);
                      } catch (Exception e) {
                        quantity.setError(e.getMessage());
                      }
                    });
                display(buy ? "买入原生 PRL" : "卖出原生 PRL", v);
              });
        });
  }

  static BigInteger notional(BigInteger quantity, BigInteger price, boolean buy) {
    BigInteger[] d = quantity.multiply(price).divideAndRemainder(SpotTradeApi.GRAINS);
    return buy && d[1].signum() > 0 ? d[0].add(BigInteger.ONE) : d[0];
  }

  static BigInteger fee(BigInteger received, int bps) {
    return received
        .multiply(BigInteger.valueOf(bps))
        .add(BigInteger.valueOf(9999))
        .divide(BigInteger.valueOf(10000));
  }

  private void preview(String selected, String eth, boolean buy, BigInteger q, BigInteger price) {
    work(
        token -> {
          long start = android.os.SystemClock.elapsedRealtime();
          SpotTradeApi.Market market = api.market();
          SpotTradeApi.Balances b = api.balances(eth);
          TradeBook book = api.book();
          BigInteger total = notional(q, price, buy);
          if ((buy ? b.usdc.compareTo(total) : b.prl.compareTo(q)) < 0)
            throw new IllegalArgumentException("平台可交易余额不足；钱包余额须先充值到平台");
          String estimate;
          try {
            TradeBook.Fill fill = book.fill(new BigDecimal(q, 8), buy);
            BigDecimal boundary = new BigDecimal(price, 6);
            boolean within = true;
            BigDecimal left = new BigDecimal(q, 8);
            for (TradeBook.Level level : (buy ? book.asks : book.bids)) {
              BigDecimal take = left.min(level.size);
              if (take.signum() > 0
                  && (buy
                      ? level.price.compareTo(boundary) > 0
                      : level.price.compareTo(boundary) < 0)) {
                within = false;
                break;
              }
              left = left.subtract(take);
              if (left.signum() == 0) break;
            }
            estimate =
                within
                    ? "当前完整数量盘口估算 · "
                        + fill.quote
                            .setScale(6, RoundingMode.DOWN)
                            .stripTrailingZeros()
                            .toPlainString()
                        + " USDC（未扣平台费）"
                    : "当前盘口不能在保护价内全部成交；剩余数量会挂单。";
          } catch (IllegalArgumentException e) {
            estimate = "已获取盘口深度不足以成交全部数量；剩余数量会挂单。";
          }
          if (android.os.SystemClock.elapsedRealtime() - start > 30_000)
            throw new IllegalArgumentException("盘口快照读取耗时过长，请刷新");
          JSONObject plan =
              store(selected, eth)
                  .plan("place_order")
                  .put("side", buy ? "buy" : "sell")
                  .put("quantity", q.toString())
                  .put("price", price.toString());
          String message =
              (buy ? "买入" : "卖出")
                  + " · "
                  + SpotTradeApi.display(q, 8)
                  + " PRL\n"
                  + (buy ? "最高买入价" : "最低卖出价")
                  + " · "
                  + SpotTradeApi.display(price, 6)
                  + " USDC / PRL\n"
                  + (buy
                      ? "最大买入成本 · "
                          + SpotTradeApi.display(total, 6)
                          + " USDC\n买入全部成交后预计到账平台 · "
                          + SpotTradeApi.display(q.subtract(fee(q, market.buyFee)), 8)
                          + " PRL"
                      : "按保护价全部成交预计到账平台 · "
                          + SpotTradeApi.display(total.subtract(fee(total, market.sellFee)), 6)
                          + " USDC")
                  + "\n\n"
                  + estimate
                  + "\n\n报价随盘口变化，限价保证每笔成交的价格边界，不保证成交数量或完成时间。费率以平台成交时规则为准；USDC 将先到平台余额，之后需单独申请提现。";
          ui(token, () -> review(selected, eth, plan, market, message));
        });
  }

  private void withdraw(String selected, String eth, boolean pearl) {
    work(
        token -> {
          SpotTradeApi.Balances b = api.balances(eth);
          String pearlAddress = wallet.get().address();
          ui(
              token,
              () -> {
                LinearLayout v = PearlDesign.form(activity);
                note(
                    v,
                    (pearl ? "Pearl 主网 · PRL" : "Arbitrum One · USDC")
                        + "\n收款地址固定为本钱包\n"
                        + (pearl ? pearlAddress : checksum(eth))
                        + "\n平台可用 · "
                        + amount(pearl ? b.prl : b.usdc, pearl ? 8 : 6));
                EditText quantity = input(v, pearl ? "提现金额（PRL）" : "提现金额（USDC）", "");
                button(
                    v,
                    "核验提现费并预览",
                    () -> {
                      try {
                        BigInteger q =
                            pearl
                                ? PearlAmount.parsePositivePrl(quantity.getText().toString())
                                : DeFiAmount.parse(quantity.getText().toString());
                        if (dialog != null) dialog.dismiss();
                        work(
                            t -> {
                              SpotTradeApi.Market m = api.market();
                              SpotTradeApi.Balances fresh = api.balances(eth);
                              BigInteger fee = pearl ? m.prlWithdrawal : m.usdcWithdrawal;
                              if (q.compareTo(fee) <= 0
                                  || q.compareTo(pearl ? fresh.prl : fresh.usdc) > 0)
                                throw new IllegalArgumentException("提现金额须大于平台提现费，且不能超过可用余额");
                              String asset = pearl ? "PRL" : "USDC-ARB",
                                  dest = pearl ? wallet.get().address() : checksum(eth);
                              JSONObject plan =
                                  store(selected, eth)
                                      .plan("withdraw")
                                      .put("asset", asset)
                                      .put("quantity", q.toString())
                                      .put("destination", dest);
                              String msg =
                                  "从平台余额扣除 · "
                                      + SpotTradeApi.display(q, pearl ? 8 : 6)
                                      + " "
                                      + (pearl ? "PRL" : "USDC")
                                      + "\n平台提现费 · "
                                      + SpotTradeApi.display(fee, pearl ? 8 : 6)
                                      + "\n预计链上收到 · "
                                      + SpotTradeApi.display(q.subtract(fee), pearl ? 8 : 6)
                                      + "\n收款网络 · "
                                      + (pearl ? "Pearl 主网" : "Arbitrum One（42161）")
                                      + "\n收款地址\n"
                                      + dest
                                      + "\n\n"
                                      + "需要平台运营方处理。提交申请不代表到账；请到提现与到账查询核验。提现费未绑定在平台签名格式中，以平台实际处理为准。";
                              ui(t, () -> review(selected, eth, plan, m, msg));
                            });
                      } catch (Exception e) {
                        quantity.setError(e.getMessage());
                      }
                    });
                display(pearl ? "提现 PRL" : "提现 Arbitrum USDC", v);
              });
        });
  }

  private void review(
      String selected, String eth, JSONObject plan, SpotTradeApi.Market market, String msg) {
    if (dialog != null) dialog.dismiss();
    dialog =
        new AlertDialog.Builder(activity)
            .setTitle("确认现货操作")
            .setMessage(
                "钱包 · "
                    + name.get()
                    + "\n平台 · pearl-trade.com\n账户\n"
                    + checksum(eth)
                    + "\n\n"
                    + msg
                    + "\n\n签名预览有效期 5 分钟。仅签署本次平台请求，不授权任意 dApp。")
            .setNegativeButton("取消", null)
            .setPositiveButton(
                "验证并签署现货请求",
                (d, w) -> {
                  if (!selected.equals(slot.get()) || observing.getAsBoolean()) return;
                  work(
                      token -> {
                        if (!market.same(api.market()))
                          throw new IllegalArgumentException("平台费率或提现费变化，请重新预览");
                        validateFunds(eth, plan);
                        if (store(selected, eth).pending() != null)
                          throw new IllegalArgumentException("先处理上一笔现货请求");
                        ui(
                            token,
                            () -> {
                              busy = false;
                              wallet
                                  .get()
                                  .tradeSign(plan, auth -> submit(selected, eth, plan, auth));
                            });
                      });
                })
            .create();
    dialog.show();
    PearlDesign.dialog(dialog);
  }

  private void validateFunds(String eth, JSONObject p) throws Exception {
    String action = p.getString("action");
    if (action.equals("cancel_order")) {
      findOrder(api.orders(eth), eth, p.getString("orderId"), true);
      return;
    }
    SpotTradeApi.Balances b = api.balances(eth);
    BigInteger q = SpotTradeApi.units(p.getString("quantity"));
    BigInteger needed = q, available = b.prl;
    if (action.equals("place_order") && p.getString("side").equals("buy")) {
      needed = notional(q, SpotTradeApi.units(p.getString("price")), true);
      available = b.usdc;
    }
    if (action.equals("withdraw") && p.getString("asset").equals("USDC-ARB")) available = b.usdc;
    if (needed.compareTo(available) > 0) throw new IllegalArgumentException("平台可用余额已变化，请重新预览");
  }

  private void submit(String selected, String eth, JSONObject plan, JSONObject auth) {
    work(
        token -> {
          JSONObject response = api.submit(plan, auth);
          String action = plan.getString("action"), id;
          if (action.equals("place_order")) {
            JSONObject order = response.getJSONObject("order");
            id = SpotTradeApi.id(order.get("id"));
            matchOrder(order, eth, plan);
          } else if (action.equals("withdraw")) {
            id = SpotTradeApi.id(response.get("id"));
            matchWithdrawal(find(api.withdrawals(eth), id), eth, plan);
          } else {
            id = plan.getString("orderId");
            JSONObject order = findOrder(api.orders(eth), eth, id, false);
            if (order.getString("status").equals("open"))
              throw new IllegalArgumentException("平台仍显示订单未撤销，保留原请求");
          }
          store(selected, eth).accepted(id);
          String kind =
              action.equals("place_order")
                  ? "平台订单已接收"
                  : action.equals("withdraw") ? "提现申请已接收" : "撤单状态已更新";
          ui(
              token,
              () ->
                  wallet
                      .get()
                      .notice(
                          kind, "编号 · " + id + "\n\n请刷新订单或提现记录。订单接收不保证完全成交，提现申请不代表 USDC 已回到钱包。"));
        });
  }

  static JSONObject find(JSONArray rows, String id) throws Exception {
    if (rows.length() > 1000) throw new IllegalArgumentException("平台记录过大，请在官网检查");
    JSONObject found = null;
    for (int i = 0; i < rows.length(); i++) {
      JSONObject r = rows.getJSONObject(i);
      if (id.equals(SpotTradeApi.id(r.get("id")))) {
        if (found != null) throw new IllegalArgumentException("平台编号重复");
        found = r;
      }
    }
    if (found == null) throw new IllegalArgumentException("平台未返回此记录");
    return found;
  }

  static JSONObject findOrder(JSONArray rows, String eth, String id, boolean open)
      throws Exception {
    JSONObject o = find(rows, id);
    checkOrder(o, eth);
    if (open && !o.getString("status").equals("open"))
      throw new IllegalArgumentException("订单已结束，请刷新");
    return o;
  }

  static void checkOrder(JSONObject o, String eth) throws Exception {
    if (!SpotTradeApi.address(eth).equals(SpotTradeApi.address(o.getString("eth_address")))
        || !o.getString("market").equals("PRL")
        || !(o.getString("side").equals("buy") || o.getString("side").equals("sell")))
      throw new IllegalArgumentException("订单资产或账户不匹配");
    BigInteger q = SpotTradeApi.units(o.get("qty_sats")),
        filled = SpotTradeApi.units(o.get("filled_sats"));
    if (filled.compareTo(q) > 0) throw new IllegalArgumentException("订单成交量无效");
    SpotTradeApi.units(o.get("price_micro_per_prl"));
    SpotTradeApi.id(o.get("id"));
  }

  static void matchOrder(JSONObject o, String eth, JSONObject p) throws Exception {
    checkOrder(o, eth);
    if (!o.getString("side").equals(p.getString("side"))
        || !SpotTradeApi.units(o.get("qty_sats"))
            .equals(SpotTradeApi.units(p.getString("quantity")))
        || !SpotTradeApi.units(o.get("price_micro_per_prl"))
            .equals(SpotTradeApi.units(p.getString("price"))))
      throw new IllegalArgumentException("平台接收的订单与签名条款不一致");
  }

  static void matchWithdrawal(JSONObject o, String eth, JSONObject p) throws Exception {
    if (!SpotTradeApi.address(eth).equals(SpotTradeApi.address(o.getString("eth_address")))
        || !o.getString("asset").equals(p.getString("asset"))
        || !o.getString("dest_address").equalsIgnoreCase(p.getString("destination"))
        || !SpotTradeApi.units(o.get("amount_units"))
            .add(SpotTradeApi.units(o.get("fee_units")))
            .equals(SpotTradeApi.units(p.getString("quantity"))))
      throw new IllegalArgumentException("平台提现记录与签名不一致");
  }

  private void orders(String selected, String eth) {
    work(
        token -> {
          JSONArray rows = api.orders(eth);
          if (rows.length() > 1000) throw new IllegalArgumentException("订单过多，请在官网检查");
          ui(
              token,
              () -> {
                try {
                  LinearLayout v = PearlDesign.form(activity);
                  note(v, "平台报告的订单状态。已成交的 USDC 先留在平台余额；尚未成交部分可撤销。");
                  int count = 0;
                  for (int i = 0; i < rows.length() && count < 50; i++) {
                    JSONObject o = rows.getJSONObject(i);
                    if (!o.getString("market").equals("PRL")) continue;
                    checkOrder(o, eth);
                    count++;
                    String id = SpotTradeApi.id(o.get("id")), status = o.getString("status");
                    note(
                        v,
                        "#"
                            + id
                            + " · "
                            + (o.getString("side").equals("sell") ? "卖出" : "买入")
                            + " · "
                            + status
                            + "\n限价 · "
                            + SpotTradeApi.display(
                                SpotTradeApi.units(o.get("price_micro_per_prl")), 6)
                            + " USDC\n数量 · "
                            + amount(SpotTradeApi.units(o.get("qty_sats")), 8)
                            + " PRL\n已成交 · "
                            + amount(SpotTradeApi.units(o.get("filled_sats")), 8)
                            + " PRL");
                    if (status.equals("open"))
                      button(
                          v,
                          "撤销订单 #" + id,
                          () ->
                              work(
                                  t -> {
                                    findOrder(api.orders(eth), eth, id, true);
                                    SpotTradeApi.Market market = api.market();
                                    JSONObject plan =
                                        store(selected, eth)
                                            .plan("cancel_order")
                                            .put("orderId", id);
                                    ui(
                                        t,
                                        () ->
                                            review(
                                                selected,
                                                eth,
                                                plan,
                                                market,
                                                "仅撤销 #" + id + " 的未成交部分；已经发生的成交保留。"));
                                  }));
                  }
                  if (count == 0) note(v, "暂无 PRL 订单。最多显示平台返回的前 50 条 PRL 记录。");
                  button(v, "刷新订单", () -> orders(selected, eth));
                  display("订单与撤单", v);
                } catch (Exception e) {
                  error(e);
                }
              });
        });
  }

  private void withdrawals(String selected, String eth) {
    work(
        token -> {
          JSONArray rows = api.withdrawals(eth);
          if (rows.length() > 1000) throw new IllegalArgumentException("提现记录过大");
          ui(
              token,
              () -> {
                try {
                  LinearLayout v = PearlDesign.form(activity);
                  note(v, "平台的 confirmed 状态仍需核验链上交易。USDC 仅核验 Arbitrum 的固定 USDC 合约与本钱包收款地址。");
                  int count = 0;
                  for (int i = 0; i < rows.length() && count < 50; i++) {
                    JSONObject o = rows.getJSONObject(i);
                    if (!SpotTradeApi.address(eth)
                        .equals(SpotTradeApi.address(o.getString("eth_address"))))
                      throw new IllegalArgumentException("提现记录账户不匹配");
                    String asset = o.getString("asset");
                    if (!asset.equals("PRL") && !asset.equals("USDC-ARB")) continue;
                    count++;
                    String id = SpotTradeApi.id(o.get("id"));
                    note(
                        v,
                        "#"
                            + id
                            + " · "
                            + asset
                            + " · 平台状态 "
                            + o.getString("status")
                            + "\n链上净额 · "
                            + amount(
                                SpotTradeApi.units(o.get("amount_units")),
                                asset.equals("PRL") ? 8 : 6)
                            + "\n收款地址\n"
                            + o.getString("dest_address"));
                    String hash = o.optString("tx_hash", "");
                    if (asset.equals("USDC-ARB")
                        && !o.isNull("tx_hash")
                        && !hash.isEmpty()
                        && SpotTradeApi.address(eth)
                            .equals(SpotTradeApi.address(o.getString("dest_address"))))
                      button(v, "核验 USDC 到账 #" + id, () -> verifyWithdrawal(selected, eth, o));
                    if (asset.equals("PRL") && hash.matches("[0-9a-f]{64}"))
                      button(
                          v,
                          "查看 PRL 提现交易 #" + id,
                          () ->
                              activity.startActivity(
                                  new Intent(
                                      Intent.ACTION_VIEW,
                                      Uri.parse("https://blockbook.pearlresearch.ai/tx/" + hash))));
                  }
                  if (count == 0) note(v, "暂无提现记录。");
                  button(v, "刷新提现记录", () -> withdrawals(selected, eth));
                  display("提现与到账查询", v);
                } catch (Exception e) {
                  error(e);
                }
              });
        });
  }

  private void verifyWithdrawal(String selected, String eth, JSONObject row) {
    work(
        token -> {
          if (!"arb".equals(row.getString("chain")))
            throw new IllegalArgumentException("提现网络不是 Arbitrum");
          BigInteger gross = SpotTradeApi.units(row.get("amount_units")),
              charge = SpotTradeApi.units(row.get("fee_units"));
          if (gross.signum() <= 0) throw new IllegalArgumentException("提现金额与费用无效");
          long earliest = (long) row.getDouble("requested_at");
          JSONArray records = store(selected, eth).records();
          for (int i = 0; i < records.length(); i++) {
            JSONObject saved = records.getJSONObject(i);
            if (saved.getString("action").equals("withdraw")
                && saved.getString("id").equals(SpotTradeApi.id(row.get("id")))) {
              JSONObject plan = saved.getJSONObject("plan");
              matchWithdrawal(row, eth, plan);
              earliest = Math.max(earliest, plan.getLong("issuedAt"));
            }
          }
          SpotUsdcApi.Receipt r = usdc.receipt(row.getString("tx_hash"), eth, earliest);
          if (r == null) {
            ui(token, () -> wallet.get().notice("提现尚未找到链上交易", "平台申请仍需处理，请稍后重查。"));
            return;
          }
          if (!r.received.equals(gross))
            throw new IllegalArgumentException("链上收到的 USDC 数量与平台提现净额不一致");
          ui(
              token,
              () ->
                  wallet
                      .get()
                      .notice(
                          r.safe ? "USDC 已在链上收到" : "USDC 已包含在 Arbitrum 区块",
                          amount(r.received, 6)
                              + " USDC\n本钱包 · "
                              + checksum(eth)
                              + "\nArbitrum One\n"
                              + row.optString("tx_hash")
                              + "\n\n"
                              + (r.finalized
                                  ? "对应数据已达到 L1 最终确认。"
                                  : r.safe
                                      ? "对应数据已发布到 L1，等待 L1 最终确认。"
                                      : "对应数据尚未达到 L1 safe 状态，请稍后核验。")));
        });
  }

  private void records(String selected, String eth) {
    try {
      JSONArray rows = store(selected, eth).records();
      LinearLayout v = PearlDesign.form(activity);
      note(v, "本机保存的签名与提交编号。记录存在不代表充值已记账、成交或提现到账。");
      for (int i = rows.length() - 1; i >= Math.max(0, rows.length() - 30); i--) {
        JSONObject r = rows.getJSONObject(i);
        note(v, r.getString("action") + "\n" + r.getString("id"));
      }
      display("本机现货记录", v);
    } catch (Exception e) {
      error(e);
    }
  }

  private void pending(String selected, String eth, JSONObject pending) {
    LinearLayout v = PearlDesign.form(activity);
    JSONObject p = pending.optJSONObject("plan");
    note(
        v,
        "上一笔现货请求结果待确认\n"
            + p.optString("action")
            + " · nonce "
            + p.optLong("nonce")
            + "\n\n平台可能已经接收请求。原签名保存在本机；不会自动重发或重新签署，以免重复下单或提现。请先查询平台记录。");
    button(v, "查询平台订单", () -> orders(selected, eth));
    button(v, "查询平台提现", () -> withdrawals(selected, eth));
    button(v, "打开官网核对结果", this::browser);
    button(
        v,
        "已核对结果，解除本机锁定",
        () -> {
          if (dialog != null) dialog.dismiss();
          dialog =
              new AlertDialog.Builder(activity)
                  .setTitle("确认已核对上一笔请求")
                  .setMessage(
                      "请确认已经在平台找到并核对上一笔请求，或确认它没有执行。解除锁定不会撤单、取消提现或删除平台记录；若请求已成交，重复操作可能再次卖出或提现。")
                  .setNegativeButton("继续保留", null)
                  .setPositiveButton(
                      "记录核对并解除锁定",
                      (d, w) -> {
                        try {
                          store(selected, eth).acknowledgeUnknown();
                          show();
                        } catch (Exception e) {
                          error(e);
                        }
                      })
                  .create();
          dialog.show();
          PearlDesign.dialog(dialog);
        });
    display("现货结果待确认", v);
  }
}
