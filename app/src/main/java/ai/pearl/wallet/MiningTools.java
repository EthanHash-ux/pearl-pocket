package ai.pearl.wallet;

import android.app.*;
import android.content.*;
import android.widget.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;

final class MiningTools {
  private final Activity activity;
  private final WatchBook book;
  private final BooleanSupplier hidden;
  private final ExecutorService worker = Executors.newSingleThreadExecutor();
  private AlertDialog dialog;
  private volatile int generation;

  MiningTools(Activity a, BooleanSupplier h) {
    activity = a;
    hidden = h;
    book = new WatchBook(PublicStore.of(a), "miner_addresses");
  }

  private Button button(String s, Runnable r) {
    return PearlDesign.button(activity, s, PearlDesign.TEAL, PearlDesign.PALE, v -> r.run());
  }

  private void show(String title, LinearLayout form) {
    pause();
    ScrollView scroll = new ScrollView(activity);
    scroll.addView(form);
    dialog =
        new AlertDialog.Builder(activity)
            .setTitle(title)
            .setView(scroll)
            .setNegativeButton("关闭", null)
            .create();
    dialog.show();
    PearlDesign.dialog(dialog);
  }

  void list() {
    try {
      LinearLayout form = PearlDesign.form(activity);
      form.addView(PearlDesign.note(activity, "HeroMiners 普通挖矿模式 · 按公开收款地址查看。查询会把该地址发送给矿池；不需要私钥。"));
      for (WatchBook.Entry e : book.list()) {
        form.addView(button("查看矿工 " + e.name, () -> load(e)));
        form.addView(
            button(
                "移除监控 " + e.name,
                () -> {
                  try {
                    book.remove(e.address);
                    list();
                  } catch (Exception x) {
                    notice(x.getMessage());
                  }
                }));
      }
      form.addView(button("添加矿工地址", this::add));
      show("个人矿工监控", form);
    } catch (Exception e) {
      notice(e.getMessage());
    }
  }

  private void add() {
    LinearLayout form = PearlDesign.form(activity);
    EditText name = new EditText(activity), address = new EditText(activity);
    name.setHint("矿工名称");
    address.setHint("矿工收款地址");
    PearlDesign.input(name);
    PearlDesign.input(address);
    form.addView(name);
    form.addView(address);
    show("添加矿工地址", form);
    form.addView(
        button(
            "保存矿工地址",
            () -> {
              try {
                book.save(name.getText().toString(), address.getText().toString());
                list();
              } catch (Exception e) {
                address.setError(e.getMessage());
              }
            }));
  }

  private void load(WatchBook.Entry e) {
    LinearLayout form = PearlDesign.form(activity);
    form.addView(PearlDesign.note(activity, e.name + "\n" + e.address));
    TextView status = PearlDesign.note(activity, "正在读取矿池…");
    form.addView(status);
    show("矿工详情", form);
    int token = ++generation;
    worker.execute(
        () -> {
          try {
            MinerAccount s = MinerAccount.fetch(e.address);
            activity.runOnUiThread(
                () -> {
                  if (token != generation) return;
                  if (!s.found) {
                    status.setText("矿池未找到该地址的普通模式记录。不会将未知余额显示为零。");
                    return;
                  }
                  status.setText(
                      activity.getString(
                          R.string.miner_received,
                          java.time.Instant.ofEpochSecond(s.receivedAt).toString(),
                          MinerAccount.activity(s.lastShare, s.receivedAt)));
                  form.addView(
                      PearlDesign.note(
                          activity,
                          hidden.getAsBoolean()
                              ? "奖励金额已隐藏"
                              : "可支付余额："
                                  + PearlAmount.format(s.balance)
                                  + " PRL\n待成熟奖励："
                                  + PearlAmount.format(s.pending)
                                  + " PRL\n矿池累计已支付："
                                  + PearlAmount.format(s.paid)
                                  + " PRL"));
                  form.addView(
                      PearlDesign.note(
                          activity,
                          "当前计分速率："
                              + s.rate.toPlainString()
                              + "\n24 小时计分速率："
                              + s.dayRate.toPlainString()
                              + "\n以上为矿池原始单位。此接口未提供可核验的个人物理算力，不换算为 H/s。提交时间仅供判断活动，不保证工作器在线。"));
                  for (MinerAccount.Worker w : s.workers)
                    form.addView(
                        PearlDesign.note(
                            activity,
                            w.name
                                + "\n"
                                + MinerAccount.activity(w.lastShare, s.receivedAt)
                                + " · 计分速率 "
                                + w.scoreRate.toPlainString()));
                  form.addView(PearlDesign.text(activity, "最近矿池付款", 16, PearlDesign.INK, true));
                  for (MinerAccount.Payout payment : s.payouts)
                    form.addView(
                        button(
                            (hidden.getAsBoolean()
                                    ? "•••• PRL"
                                    : PearlAmount.format(payment.grains) + " PRL")
                                + " · "
                                + payment.id.substring(0, 8),
                            () ->
                                activity.startActivity(
                                    new Intent(
                                        Intent.ACTION_VIEW,
                                        android.net.Uri.parse(
                                            "https://explorer.pearlresearch.ai/tx/"
                                                + payment.id
                                                + "?network=mainnet")))));
                  form.addView(button("刷新矿工详情", () -> load(e)));
                });
          } catch (Exception x) {
            activity.runOnUiThread(
                () -> {
                  if (token == generation)
                    status.setText(activity.getString(R.string.miner_read_error, x.getMessage()));
                });
          }
        });
  }

  private void notice(String s) {
    AlertDialog d =
        new AlertDialog.Builder(activity)
            .setTitle("矿工监控")
            .setMessage(s)
            .setPositiveButton("知道了", null)
            .create();
    d.show();
    PearlDesign.dialog(d);
  }

  void pause() {
    generation++;
    if (dialog != null) {
      dialog.dismiss();
      dialog = null;
    }
  }

  void close() {
    pause();
    worker.shutdownNow();
  }
}
