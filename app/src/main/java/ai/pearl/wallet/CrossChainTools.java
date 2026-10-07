package ai.pearl.wallet;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.InputType;
import android.widget.*;
import java.math.*;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.concurrent.*;
import java.util.function.*;
import org.json.*;

/** Public markets and explicit bridge intents. Ethereum signing stays external. */
@android.annotation.SuppressLint("SetTextI18n")
final class CrossChainTools {
  private final Activity activity;
  private final Supplier<String> signer;
  private final BooleanSupplier observing, hidden;
  private final Consumer<BridgeApi.MintPlan> send;
  private final BridgeApi bridge = new BridgeApi();
  private final EvmPublicApi evm = new EvmPublicApi();
  private final BridgeBook book;
  private final ExecutorService worker = Executors.newFixedThreadPool(3);
  private final Handler handler = new Handler(Looper.getMainLooper());
  private AlertDialog dialog;
  private volatile int generation;
  private LighterMarket lastLighter;

  CrossChainTools(
      Activity a,
      Supplier<String> s,
      BooleanSupplier o,
      BooleanSupplier h,
      Consumer<BridgeApi.MintPlan> send) {
    activity = a;
    signer = s;
    observing = o;
    hidden = h;
    this.send = send;
    book = new BridgeBook(PublicStore.of(a));
  }

  private LinearLayout form() {
    return PearlDesign.form(activity);
  }

  private TextView note(String s) {
    return PearlDesign.note(activity, s);
  }

  private Button button(String s, Runnable r) {
    Button b = PearlDesign.button(activity, s, PearlDesign.TEAL, PearlDesign.PALE, v -> r.run());
    LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
    p.bottomMargin = PearlDesign.dp(activity, 9);
    b.setLayoutParams(p);
    return b;
  }

  private void show(String title, LinearLayout f) {
    pause();
    ScrollView scroll = new ScrollView(activity);
    scroll.setFillViewport(true);
    scroll.setFocusableInTouchMode(true);
    scroll.addView(f);
    dialog =
        new AlertDialog.Builder(activity)
            .setTitle(title)
            .setView(scroll)
            .setNegativeButton("关闭", null)
            .create();
    dialog.show();
    PearlDesign.dialog(dialog);
    dialog
        .getWindow()
        .setLayout(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
    final AlertDialog shown = dialog;
    dialog.setOnDismissListener(
        d -> {
          if (dialog != shown) return;
          generation++;
          dialog = null;
          handler.removeCallbacksAndMessages(null);
        });
    scroll.requestFocus();
  }

  private LinearLayout card(LinearLayout parent, String title) {
    LinearLayout c = PearlDesign.column(activity);
    c.setPadding(18, 18, 18, 18);
    c.setBackground(PearlDesign.surface(activity, PearlDesign.BG, PearlDesign.LINE, 14));
    LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
    p.bottomMargin = PearlDesign.dp(activity, 12);
    parent.addView(c, p);
    c.addView(PearlDesign.text(activity, title, 17, PearlDesign.INK, true));
    return c;
  }

  private EditText field(LinearLayout f, String title, String value, boolean signed) {
    f.addView(note(title));
    EditText e = new EditText(activity);
    e.setContentDescription(title);
    e.setHint(title);
    e.setText(value);
    e.setInputType(
        InputType.TYPE_CLASS_NUMBER
            | InputType.TYPE_NUMBER_FLAG_DECIMAL
            | (signed ? InputType.TYPE_NUMBER_FLAG_SIGNED : 0));
    PearlDesign.input(e);
    f.addView(e);
    return e;
  }

  private void task(int token, TextView state, Callable<Runnable> work) {
    worker.execute(
        () -> {
          try {
            Runnable result = work.call();
            activity.runOnUiThread(
                () -> {
                  if (token == generation && !activity.isDestroyed()) result.run();
                });
          } catch (Exception e) {
            activity.runOnUiThread(
                () -> {
                  if (token == generation && !activity.isDestroyed())
                    state.setText("暂不可用：" + (e.getMessage() == null ? "请稍后重试" : e.getMessage()));
                });
          }
        });
  }

  private void copy(String text) {
    ((ClipboardManager) activity.getSystemService(Activity.CLIPBOARD_SERVICE))
        .setPrimaryClip(ClipData.newPlainText("公开跨链信息", text));
    Toast.makeText(activity, "已复制", Toast.LENGTH_SHORT).show();
  }

  private static String readTime() {
    return DateTimeFormatter.ofPattern("MM-dd HH:mm:ss", Locale.CHINA)
        .withZone(ZoneId.systemDefault())
        .format(Instant.now());
  }

  private void open(String url) {
    try {
      activity.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
    } catch (Exception e) {
      Toast.makeText(activity, "无法打开浏览器", Toast.LENGTH_SHORT).show();
    }
  }

  private void link(String name, String url) {
    show(
        "前往 " + name,
        formWith(
            "将在浏览器打开\n" + url + "\nEthereum 授权与交易由你已有的钱包签名。请核对主网、代币和金额；掌珠钱包不会向网页提供助记词或钱包密码。",
            button("打开 " + name, () -> open(url))));
  }

  private LinearLayout formWith(String text, Button b) {
    LinearLayout f = form();
    f.addView(note(text));
    f.addView(b);
    return f;
  }

  private void actions(LinearLayout parent, Button left, Button right) {
    LinearLayout row = new LinearLayout(activity);
    row.setOrientation(LinearLayout.HORIZONTAL);
    LinearLayout.LayoutParams a = new LinearLayout.LayoutParams(0, -2, 1),
        b = new LinearLayout.LayoutParams(0, -2, 1);
    a.rightMargin = PearlDesign.dp(activity, 5);
    b.leftMargin = PearlDesign.dp(activity, 5);
    a.bottomMargin = b.bottomMargin = PearlDesign.dp(activity, 9);
    row.addView(left, a);
    row.addView(right, b);
    parent.addView(row);
  }

  void show() {
    LinearLayout f = form();
    f.addView(note("PRL · WPRL · Lighter\n真实报价与成本情景，交易由你逐步确认。"));
    actions(
        f,
        button("PRL → WPRL 转入桥", () -> bridgeForm(true)),
        button("WPRL → PRL 赎回报价", () -> bridgeForm(false)));
    actions(
        f,
        button("跨链现货成本试算", () -> calculator(false)),
        button("Lighter 对冲情景", () -> calculator(true)));
    actions(f, button("Ethereum 观察余额", this::ethereum), button("跨链进度与本机记录", this::transfers));
    LinearLayout b = card(f, "PearlBridge · Ethereum 主网");
    TextView bridgeState = note("桥状态读取中…");
    b.addView(bridgeState);
    LinearLayout l = card(f, "Lighter · PRL 永续");
    TextView lighterState = note("盘口读取中…");
    l.addView(lighterState);
    LinearLayout d = card(f, "Uniswap V3 · WPRL / USDT");
    TextView dexState = note("链上 1 WPRL 双向报价读取中…");
    d.addView(dexState);
    f.addView(button("刷新公开报价", this::show));
    f.addView(
        button("前往 Lighter PRL 交易", () -> link("Lighter", "https://app.lighter.xyz/trade/PRL")));
    f.addView(
        button(
            "前往 WPRL 池",
            () ->
                link(
                    "Uniswap",
                    "https://app.uniswap.org/explore/pools/ethereum/" + EvmPublicApi.POOL)));
    f.addView(
        note(
            "PearlBridge 是独立社区实验性桥，PRL 托管与铸造依赖桥运营与合约。WPRL 不能作为 PRL 直接转入 Lighter；Lighter PRL 是 USDC"
                + " 结算的永续。价差、资金费与稳定币汇率会变化，无法保证套利收益。"));
    show("跨链与价差", f);
    int token = generation;
    task(
        token,
        bridgeState,
        () -> {
          BridgeApi.Status s = bridge.status();
          BridgeApi.Quote q = bridge.quote(true, BigInteger.valueOf(10000000000L));
          return () ->
              bridgeState.setText(
                  (s.paused ? "已暂停" : "API 报告运行中")
                      + " · 读取 "
                      + readTime()
                      + "\n转入 100 PRL：桥费 "
                      + PearlAmount.format(q.fee)
                      + " PRL → "
                      + PearlAmount.format(q.net)
                      + " WPRL\n至少 "
                      + q.confirmations
                      + " 个确认 · "
                      + (q.lane.equals("fast") ? "快通道" : "慢通道 +" + q.delay / 3600 + " 小时")
                      + "\n报价会重新核对，网络费另计。");
        });
    task(
        token,
        lighterState,
        () -> {
          LighterMarket m = LighterMarket.fetch(PearlApi::httpsGet);
          String funding;
          try {
            funding =
                LighterMarket.settledFunding(PearlApi::httpsGet, System.currentTimeMillis() / 1000);
          } catch (Exception e) {
            funding = "历史资金费暂不可用";
          }
          String line = funding;
          return () -> {
            lastLighter = m;
            lighterState.setText(
                "买一 "
                    + m.book.bids.get(0).price
                    + " / 卖一 "
                    + m.book.asks.get(0).price
                    + " USDC\n标记 "
                    + m.mark
                    + " · 指数 "
                    + m.index
                    + "\nUSDC 结算 · 更新 "
                    + readTime()
                    + "\n"
                    + line);
          };
        });
    task(
        token,
        dexState,
        () -> {
          String block = evm.block();
          evm.verifyPool(block);
          EvmPublicApi.DexQuote sell = evm.quote(BigInteger.valueOf(100000000), true, block),
              buy = evm.quote(BigInteger.valueOf(100000000), false, block);
          return () ->
              dexState.setText(
                  "卖 1 WPRL → "
                      + money(sell.usdt)
                      + " USDT\n买 1 WPRL 需 "
                      + money(buy.usdt)
                      + " USDT\nEthereum 区块 "
                      + new BigInteger(block.substring(2), 16)
                      + "\n已含池费 1% 与池内价格影响，未含 gas。");
        });
    handler.postDelayed(
        () -> {
          if (token == generation) {
            bridgeState.append("\n快照已超过 30 秒，请刷新。");
            lighterState.append("\n快照已超过 30 秒，请刷新。");
            dexState.append("\n快照已超过 30 秒，请刷新。");
          }
        },
        30_000);
  }

  private String savedEth() {
    return activity
        .getSharedPreferences("public_preferences", Activity.MODE_PRIVATE)
        .getString("evm_observe", "");
  }

  private EditText ethField(LinearLayout f) {
    f.addView(note("你控制的 Ethereum 主网收款地址。只保存公开地址，不导入 Ethereum 私钥。"));
    EditText e = new EditText(activity);
    e.setHint("0x… Ethereum 地址");
    e.setContentDescription("Ethereum 收款地址");
    e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
    e.setText(savedEth());
    PearlDesign.input(e);
    f.addView(e);
    return e;
  }

  private void ethereum() {
    LinearLayout f = form();
    EditText address = ethField(f);
    TextView status = note("输入公开地址后查询；地址会发送给 Ethereum RPC 服务。");
    f.addView(status);
    show("Ethereum 观察余额", f);
    int token = generation;
    f.addView(
        button(
            "查询 WPRL 与 ETH 余额",
            () -> {
              try {
                String a = EvmPublicApi.checkedAddress(address.getText().toString());
                activity
                    .getSharedPreferences("public_preferences", Activity.MODE_PRIVATE)
                    .edit()
                    .putString("evm_observe", a)
                    .apply();
                status.setText("正在查询 Ethereum 主网…");
                task(
                    token,
                    status,
                    () -> {
                      EvmPublicApi.Balance balance = evm.balance(a);
                      return () ->
                          status.setText(
                              a
                                  + "\n"
                                  + (hidden.getAsBoolean()
                                      ? "金额已隐藏"
                                      : "WPRL："
                                          + PearlAmount.format(balance.grains)
                                          + "\nETH："
                                          + new BigDecimal(balance.wei, 18)
                                              .stripTrailingZeros()
                                              .toPlainString())
                                  + "\n区块 "
                                  + new BigInteger(balance.block.substring(2), 16)
                                  + "\n只读观察，签名仍由外部 Ethereum 钱包完成。");
                    });
              } catch (Exception e) {
                address.setError(e.getMessage());
              }
            }));
    f.addView(button("复制 WPRL 合约", () -> copy(EvmPublicApi.WPRL)));
  }

  private void bridgeForm(boolean mint) {
    if (mint && observing.getAsBoolean()) {
      show("观察地址不能转入桥", formWith("切换为手机钱包后才能签名。观察地址没有对应私钥。", button("返回跨链工作台", this::show)));
      return;
    }
    LinearLayout f = form();
    EditText amount = field(f, mint ? "转入数量（PRL）" : "赎回数量（WPRL）", "", false);
    EditText eth = mint ? ethField(f) : null;
    if (!mint)
      f.addView(
          note(
              "Ethereum 合约赎回需由外部钱包执行。本机 Pearl 收款地址：\n"
                  + (signer.get().isEmpty() ? "尚未创建手机钱包" : signer.get())));
    TextView status = note("先查真实桥费、额度与通道，报价不移动资金。");
    f.addView(status);
    show(mint ? "PRL → WPRL" : "WPRL → PRL", f);
    int token = generation;
    f.addView(
        button(
            "获取桥报价",
            () -> {
              try {
                BigInteger q = PearlAmount.parsePositivePrl(amount.getText().toString());
                String e = mint ? EvmPublicApi.checkedAddress(eth.getText().toString()) : "";
                status.setText("正在获取桥状态与报价…");
                task(
                    token,
                    status,
                    () -> {
                      if (mint) {
                        BridgeApi.MintPlan plan = bridge.prepare(q, e, book);
                        return () -> mintReview(plan);
                      }
                      BridgeApi.Status s = bridge.status();
                      BridgeApi.Quote quote = bridge.quote(false, q);
                      return () -> burnReview(quote, s);
                    });
              } catch (Exception e) {
                status.setText(e.getMessage());
              }
            }));
  }

  private void mintReview(BridgeApi.MintPlan plan) {
    LinearLayout f = form();
    f.addView(
        PearlDesign.text(
            activity,
            "预计得到 " + PearlAmount.format(plan.quote.net) + " WPRL",
            22,
            PearlDesign.TEAL,
            true));
    f.addView(note(plan.summary()));
    f.addView(note("桥的 Pearl 充值地址\n" + plan.deposit));
    f.addView(
        note(
            (plan.firstUse ? "首次使用该 Ethereum 地址。" : "充值地址与本机首次记录一致。")
                + "充值地址由桥 API 分配，当前无法在本机独立推导。请到 pearlbridge.xyz 连接同一个 Ethereum"
                + " 地址，核对完整充值地址和报价；本机固定记录无法抵御首次查询时桥服务被控制。"));
    CheckBox checked = new CheckBox(activity);
    checked.setText("我控制此 Ethereum 地址，已在桥网站核对完整充值地址，并接受桥托管与延迟风险");
    checked.setTextColor(PearlDesign.INK);
    f.addView(checked);
    f.addView(button("复制桥充值地址", () -> copy(plan.deposit)));
    f.addView(button("打开 PearlBridge 核对", () -> open("https://pearlbridge.xyz/")));
    Button proceed =
        button(
            "继续预览 PRL 转账",
            () -> {
              if (!checked.isChecked() || observing.getAsBoolean()) return;
              pause();
              send.accept(plan);
            });
    proceed.setEnabled(false);
    checked.setOnCheckedChangeListener(
        (b, v) -> proceed.setEnabled(v && !observing.getAsBoolean()));
    f.addView(proceed);
    show("核对跨链收款人", f);
  }

  private void burnReview(BridgeApi.Quote q, BridgeApi.Status s) {
    LinearLayout f = form();
    f.addView(
        PearlDesign.text(
            activity, "预计得到 " + PearlAmount.format(q.net) + " PRL", 22, PearlDesign.TEAL, true));
    f.addView(
        note(
            "赎回 "
                + PearlAmount.format(q.amount)
                + " WPRL\n桥费 "
                + PearlAmount.format(q.fee)
                + " WPRL\n"
                + (q.paused || s.paused ? "桥已暂停" : "桥未暂停")
                + " · "
                + (q.withinCap ? "额度内" : "额度不足")
                + "\nEthereum gas 另付。报价不代表已授权或已赎回。"));
    f.addView(
        note(
            "Ethereum 主网 · Chain ID 1\nWPRL（8 位精度）\n"
                + EvmPublicApi.WPRL
                + "\n赎回控制器\n"
                + EvmPublicApi.CONTROLLER));
    if (!signer.get().isEmpty()) f.addView(button("复制本机 Pearl 收款地址", () -> copy(signer.get())));
    f.addView(
        note(
            "打开 PearlBridge，在外部 Ethereum 钱包核对收款地址和限额授权，再签署赎回交易。回到掌珠钱包，用 Ethereum 交易哈希跟踪；不会将 PRL"
                + " 助记词交给网页。"));
    Button go = button("前往 PearlBridge 赎回", () -> link("PearlBridge", "https://pearlbridge.xyz/"));
    go.setEnabled(!q.paused && !s.paused && q.withinCap);
    f.addView(go);
    f.addView(button("跟踪已有赎回交易", () -> trackForm(false)));
    show("WPRL 赎回核对", f);
  }

  private void transfers() {
    LinearLayout f = form();
    f.addView(note("PRL 转入时在广播之前保存本机记录。仅有记录不代表已广播或已完成；超时请先处理原交易，避免重复转入。桥状态由其 API 报告，请用对应链浏览器核对。"));
    try {
      for (BridgeBook.Entry e : book.list())
        f.addView(button((e.mint ? "转入 · " : "赎回 · ") + e.hash.substring(0, 12), () -> track(e)));
    } catch (Exception e) {
      f.addView(note(e.getMessage()));
    }
    f.addView(button("按 Pearl 转入交易 ID 查询", () -> trackForm(true)));
    f.addView(button("按 Ethereum 赎回哈希查询", () -> trackForm(false)));
    show("跨链进度", f);
  }

  private void trackForm(boolean mint) {
    LinearLayout f = form();
    EditText hash = new EditText(activity);
    hash.setHint(mint ? "Pearl 交易 ID（64 位）" : "Ethereum 交易哈希（0x…）");
    hash.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
    PearlDesign.input(hash);
    f.addView(hash);
    f.addView(
        button(
            "查询跨链进度",
            () -> {
              try {
                String h = BridgeBook.hash(mint, hash.getText().toString().trim());
                book.save(mint, h, "");
                track(new BridgeBook.Entry(mint, h, ""));
              } catch (Exception e) {
                hash.setError(e.getMessage());
              }
            }));
    show("查询跨链交易", f);
  }

  private void track(BridgeBook.Entry e) {
    LinearLayout f = form();
    f.addView(note(e.hash));
    TextView state = note("正在查询桥进度…");
    f.addView(state);
    f.addView(button("刷新此笔进度", () -> track(e)));
    f.addView(
        button(
            "查看原始链上交易",
            () ->
                open(
                    e.mint
                        ? "https://explorer.pearlresearch.ai/tx/" + e.hash + "?network=mainnet"
                        : "https://etherscan.io/tx/" + e.hash)));
    show("跨链交易详情", f);
    int token = generation;
    task(
        token,
        state,
        () -> {
          BridgeApi.Transfer t = bridge.transfer(e.mint, e.hash);
          return () -> {
            state.setText(t.label(e.mint) + "\n若桥尚未索引或未知，不能据此再次转入。查询时间 " + readTime());
            if (!t.otherHash.isEmpty())
              f.addView(
                  button(
                      "查看桥报告的目标交易",
                      () ->
                          open(
                              t.refunded || !e.mint
                                  ? "https://explorer.pearlresearch.ai/tx/"
                                      + t.otherHash
                                      + "?network=mainnet"
                                  : "https://etherscan.io/tx/" + t.otherHash)));
          };
        });
  }

  private void calculator(boolean hedge) {
    LinearLayout f = form();
    f.addView(
        note(
            hedge
                ? "现货买入 + Lighter 做空。平仓价和资金费是你填写的情景，结果不是已经赚到的钱。需要在两端预置资产，留足保证金。"
                : "两条路线使用当前数量的盘口与链上报价。桥等待期间价格会变化，且需确认两端充值 / 提币开放；结果是成本情景。"));
    EditText quantity = field(f, "数量（PRL / WPRL）", "100", false),
        fee = field(f, "现货交易费（%，示例值，按你的账户修改）", "0.2", false),
        slip = field(f, "额外价格变化预留（%）", "0.5", false),
        cost = field(f, "其它总成本（USDT，含 gas、提币、Pearl 网络费；必填）", "", false);
    EditText ff = null, fx = null, exit = null, basis = null, funding = null, hours = null;
    if (hedge) {
      ff = field(f, "Lighter 每次交易费（%，按账户核实）", "0.028", false);
      fx = field(f, "USDC/USDT 汇率（假设值，可修改）", "1", false);
      exit =
          field(
              f,
              "假设平仓现货价格（USDT/PRL）",
              lastLighter == null ? "" : lastLighter.index.toPlainString(),
              false);
      basis = field(f, "假设平仓价差（永续减现货，USDC/PRL）", "0", true);
      funding = field(f, "假设每小时资金费（%，正为空头收入，负为支出）", "0", true);
      hours = field(f, "假设持仓小时数（0–720）", "24", false);
    }
    final EditText futureFee = ff,
        rate = fx,
        exitPrice = exit,
        exitBasis = basis,
        fund = funding,
        hold = hours;
    TextView status = note("填写所有成本后获取新报价。不会下单、授权或转账。");
    f.addView(status);
    show(hedge ? "Lighter 对冲测算" : "跨链现货测算", f);
    int token = generation;
    Button calculate =
        button(
            "获取新报价并试算",
            () -> {
              try {
                BigInteger grains = PearlAmount.parsePositivePrl(quantity.getText().toString());
                BigDecimal q = new BigDecimal(grains, 8),
                    sf = ArbitrageMath.percent(fee.getText().toString()),
                    reserve = ArbitrageMath.percent(slip.getText().toString()),
                    fixed =
                        ArbitrageMath.number(
                            cost.getText().toString(),
                            BigDecimal.ZERO,
                            new BigDecimal("1000000000"));
                BigDecimal
                    future =
                        hedge
                            ? ArbitrageMath.percent(futureFee.getText().toString())
                            : BigDecimal.ZERO,
                    fxv =
                        hedge
                            ? ArbitrageMath.number(
                                rate.getText().toString(),
                                new BigDecimal("0.5"),
                                new BigDecimal("2"))
                            : BigDecimal.ONE,
                    ex =
                        hedge
                            ? ArbitrageMath.number(
                                exitPrice.getText().toString(),
                                new BigDecimal("0.00000001"),
                                new BigDecimal("1000000"))
                            : BigDecimal.ONE,
                    eb =
                        hedge
                            ? ArbitrageMath.number(
                                exitBasis.getText().toString(),
                                new BigDecimal("-1000000"),
                                new BigDecimal("1000000"))
                            : BigDecimal.ZERO,
                    fu =
                        hedge
                            ? ArbitrageMath.number(
                                    fund.getText().toString(),
                                    new BigDecimal("-0.5"),
                                    new BigDecimal("0.5"))
                                .movePointLeft(2)
                            : BigDecimal.ZERO;
                int h =
                    hedge
                        ? ArbitrageMath.number(
                                hold.getText().toString(), BigDecimal.ZERO, BigDecimal.valueOf(720))
                            .intValueExact()
                        : 0;
                status.setText("正在获取完整数量的新盘口与报价…");
                task(
                    token,
                    status,
                    () -> {
                      long start = SystemClock.elapsedRealtime();
                      TradeBook spot = spotBook();
                      if (hedge) {
                        LighterMarket m = LighterMarket.fetch(PearlApi::httpsGet);
                        TradeBook.Fill buy = spot.fill(ArbitrageMath.spotBuyQuantity(q, sf), true),
                            shorted = m.shortFill(q);
                        ArbitrageMath.Hedge result =
                            ArbitrageMath.hedge(
                                q,
                                buy.quote,
                                shorted.quote,
                                m.index,
                                ex,
                                eb,
                                future,
                                sf,
                                fu,
                                h,
                                fixed,
                                buy.quote.multiply(reserve),
                                m.initialMarginBps,
                                fxv);
                        fresh(start);
                        return () ->
                            hedgeResult(
                                result, buy, shorted, fxv, ex, eb, fu, h, future, sf, fixed,
                                reserve, start);
                      }
                      BridgeApi.Status bs = bridge.status();
                      if (bs.paused) throw new IllegalArgumentException("桥已暂停，无法给出可用路线");
                      BridgeApi.Quote mint = bridge.quote(true, grains),
                          burn = bridge.quote(false, grains);
                      mint.available();
                      burn.available();
                      String block = evm.block();
                      evm.verifyPool(block);
                      EvmPublicApi.DexQuote sell = evm.quote(mint.net, true, block),
                          buy = evm.quote(grains, false, block);
                      TradeBook.Fill
                          spotBuy = spot.fill(ArbitrageMath.spotBuyQuantity(q, sf), true),
                          spotSell = spot.fill(new BigDecimal(burn.net, 8), false);
                      BigDecimal
                          forward =
                              ArbitrageMath.bridgeNet(
                                  spotBuy.quote, sell.usdt, reserve, fixed, true),
                          backward =
                              ArbitrageMath.bridgeNet(
                                  buy.usdt,
                                  ArbitrageMath.spotNet(spotSell.quote, sf),
                                  reserve,
                                  fixed,
                                  false);
                      fresh(start);
                      return () ->
                          spotResult(
                              mint, burn, sell, buy, spotBuy, spotSell, forward, backward, sf,
                              reserve, fixed, start);
                    });
              } catch (Exception e) {
                status.setText("请检查输入：" + e.getMessage());
              }
            });
    f.addView(calculate);
  }

  private TradeBook spotBook() throws Exception {
    JSONObject o =
        new JSONObject(PearlApi.httpsGet(PearlApi.BIGONE + "asset_pairs/PRL-USDT/depth?limit=50"));
    if (o.getInt("code") != 0
        || !"PRL-USDT".equals(o.getJSONObject("data").getString("asset_pair_name")))
      throw new IllegalArgumentException("现货交易对不匹配");
    JSONObject d = o.getJSONObject("data");
    return new TradeBook(d.getJSONArray("bids"), d.getJSONArray("asks"), "quantity");
  }

  private static void fresh(long start) {
    if (SystemClock.elapsedRealtime() - start > 30_000)
      throw new IllegalArgumentException("报价获取超过 30 秒，拒绝混用过期盘口，请重试");
  }

  private static String money(BigDecimal n) {
    return n.setScale(6, RoundingMode.HALF_EVEN).stripTrailingZeros().toPlainString();
  }

  private void amount(LinearLayout f, String label, BigDecimal n) {
    f.addView(
        PearlDesign.text(
            activity,
            label + "\n" + money(n) + " USDT",
            22,
            n.signum() >= 0 ? PearlDesign.TEAL : PearlDesign.RED,
            true));
  }

  private void resultExpiry(TextView t, long start) {
    int token = generation;
    handler.postDelayed(
        () -> {
          if (token == generation) t.setText("此快照已超过 30 秒，请重新获取报价。跨链与持仓期间价格和费用还会变化。");
        },
        Math.max(0, 30_000 - (SystemClock.elapsedRealtime() - start)));
  }

  private void spotResult(
      BridgeApi.Quote mint,
      BridgeApi.Quote burn,
      EvmPublicApi.DexQuote sell,
      EvmPublicApi.DexQuote buy,
      TradeBook.Fill spotBuy,
      TradeBook.Fill spotSell,
      BigDecimal forward,
      BigDecimal backward,
      BigDecimal sf,
      BigDecimal slip,
      BigDecimal fixed,
      long start) {
    LinearLayout f = form();
    LinearLayout a = card(f, "PRL 买入 → 桥 → WPRL 卖出");
    amount(a, "情景净额（未成交）", forward);
    a.addView(
        note(
            "BigONE 买入含费预留 "
                + money(spotBuy.quote)
                + " USDT\n转入 "
                + PearlAmount.format(mint.amount)
                + " PRL → "
                + PearlAmount.format(mint.net)
                + " WPRL\nDEX 当前可卖得 "
                + money(sell.usdt)
                + " USDT（另扣价格变化预留）\n桥费 "
                + PearlAmount.format(mint.fee)
                + " PRL · "
                + mint.confirmations
                + " 个确认 · "
                + mint.lane
                + " · 额外延迟 "
                + mint.delay / 3600
                + " 小时"));
    LinearLayout b = card(f, "WPRL 买入 → 赎回 → PRL 卖出");
    amount(b, "情景净额（未成交）", backward);
    b.addView(
        note(
            "DEX 买入成本 "
                + money(buy.usdt)
                + " USDT（另加价格变化预留）\n赎回 "
                + PearlAmount.format(burn.amount)
                + " WPRL → "
                + PearlAmount.format(burn.net)
                + " PRL\nBigONE 当前卖出毛额 "
                + money(spotSell.quote)
                + " USDT\n桥费 "
                + PearlAmount.format(burn.fee)
                + " WPRL"));
    f.addView(
        note(
            "Ethereum 报价区块 "
                + new BigInteger(sell.block.substring(2), 16)
                + "\nUniswap 单池已含 1% 池费与价格影响；买卖数量报价，不使用最后成交价相减。\n用户假设：现货费 "
                + sf.movePointRight(2)
                + "%，价格变化预留 "
                + slip.movePointRight(2)
                + "%，每路线其它成本 "
                + money(fixed)
                + " USDT。买入按币扣费预留，卖出按报价币扣费。gas 与提币预算由你填入，未自动核验充值提现、实际费用与库存。"));
    TextView stale = note("读取完成 " + readTime() + " · 盘口是不同服务先后取得的快照。此结果不能在桥等待期间锁定。路线执行需自己在两端确认。");
    f.addView(stale);
    f.addView(button("重新填写并报价", () -> calculator(false)));
    show("跨链路线成本", f);
    resultExpiry(stale, start);
  }

  private void hedgeResult(
      ArbitrageMath.Hedge r,
      TradeBook.Fill buy,
      TradeBook.Fill shorted,
      BigDecimal fx,
      BigDecimal exit,
      BigDecimal basis,
      BigDecimal funding,
      int hours,
      BigDecimal futureFee,
      BigDecimal spotFee,
      BigDecimal fixed,
      BigDecimal reserve,
      long start) {
    LinearLayout f = form();
    amount(f, "假设平仓后的情景净额（未下单）", r.pnl);
    f.addView(
        note(
            "现货买入含费预留 "
                + money(buy.quote)
                + " USDT\nLighter 开空均价 "
                + money(shorted.average)
                + " USDC\n开仓金额差（扣现货费，尚未兑现）"
                + money(r.entryBasis)
                + " USDT\n资金费情景 "
                + money(r.funding)
                + " USDC\n按指数估算最低初始保证金 "
                + money(r.margin)
                + " USDC；不包含维持保证金缓冲，不是安全仓位建议。"));
    f.addView(
        note(
            "用户假设：USDC/USDT="
                + fx
                + "；平仓现货价 "
                + exit
                + " USDT/PRL；平仓价差 "
                + basis
                + " USDC/PRL；每小时资金费 "
                + funding.movePointRight(2)
                + "%，持仓 "
                + hours
                + " 小时；Lighter 每次费 "
                + futureFee.movePointRight(2)
                + "%，现货费 "
                + spotFee.movePointRight(2)
                + "%；其它成本 "
                + fixed
                + " USDT；额外预留为现货买入成本的 "
                + reserve.movePointRight(2)
                + "%。资金费按当前指数与固定费率计算，会变化。"));
    TextView stale =
        note(
            "读取完成 "
                + readTime()
                + " · 永续没有兑换或保底平仓承诺，价差可能扩大；需要分别预置现货和 USDC 保证金。桥不是对冲订单，WPRL 不是 Lighter"
                + " 保证金。不会自动开仓或按价差发送资金。");
    f.addView(stale);
    f.addView(button("前往 Lighter PRL", () -> link("Lighter", "https://app.lighter.xyz/trade/PRL")));
    f.addView(button("重新填写并报价", () -> calculator(true)));
    show("Lighter 对冲情景结果", f);
    resultExpiry(stale, start);
  }

  void pause() {
    generation++;
    handler.removeCallbacksAndMessages(null);
    if (dialog != null) {
      AlertDialog closing = dialog;
      dialog = null;
      closing.dismiss();
    }
  }

  void close() {
    pause();
    worker.shutdownNow();
  }
}
