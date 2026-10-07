package ai.pearl.wallet;

import android.app.*;
import android.content.*;
import android.net.Uri;
import android.widget.*;
import java.util.concurrent.*;

/** Native read-only PearlFortune screens, separate from the wallet's signing address. */
final class FortuneTools {
  private final Activity activity;
  private final WatchBook book;
  private final ExecutorService worker = Executors.newSingleThreadExecutor();
  private AlertDialog dialog;
  private volatile int generation;

  FortuneTools(Activity a) {
    activity = a;
    book = new WatchBook(PublicStore.of(a), "fortune_miner_addresses");
  }

  private Button button(String name, Runnable r) {
    Button b = PearlDesign.button(activity, name, PearlDesign.TEAL, PearlDesign.PALE, v -> r.run());
    LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
    p.topMargin = PearlDesign.dp(activity, 8);
    b.setLayoutParams(p);
    return b;
  }

  private void show(String title, LinearLayout form) {
    pause();
    ScrollView scroll = new ScrollView(activity);
    scroll.addView(form);
    AlertDialog next =
        new AlertDialog.Builder(activity)
            .setTitle(title)
            .setView(scroll)
            .setNegativeButton("关闭", null)
            .create();
    dialog = next;
    next.setOnDismissListener(
        d -> {
          if (dialog == next) {
            generation++;
            dialog = null;
          }
        });
    next.show();
    PearlDesign.dialog(next);
  }

  void list() {
    try {
      LinearLayout form = PearlDesign.form(activity);
      form.addView(
          PearlDesign.note(
              activity, "PEARLFORTUNE · 我的挖矿工作台\n用你的公开 PRL 收款地址监控矿池估算贡献和 worker 连接。无需解锁钱包。"));
      form.addView(button("矿池总算力", () -> detail("PearlFortune", "")));
      for (WatchBook.Entry e : book.list()) {
        form.addView(button("我的矿工 · " + e.name, () -> detail(e.name, e.address)));
        form.addView(
            button(
                "移除地址 · " + e.name,
                () -> {
                  try {
                    book.remove(e.address);
                    list();
                  } catch (Exception x) {
                    notice(x.getMessage());
                  }
                }));
      }
      form.addView(button("添加 PearlFortune 矿工", this::add));
      form.addView(button("打开 PearlFortune 矿工网页", () -> website("")));
      form.addView(button("添加矿池算力与价格小组件", () -> pin("PearlFortune", "")));
      form.addView(
          PearlDesign.note(activity, "已保存地址仅用于监控，不会替换钱包的收发地址。桌面组件可以分别绑定不同矿工；长按组件可调整大小和配置。"));
      show("PearlFortune 我的矿工", form);
    } catch (Exception e) {
      notice(e.getMessage());
    }
  }

  private void add() {
    LinearLayout form = PearlDesign.form(activity);
    EditText name = new EditText(activity), address = new EditText(activity);
    name.setHint("矿工名称，如 家里的矿机");
    address.setHint("PRL 挖矿收款地址");
    PearlDesign.input(name);
    PearlDesign.input(address);
    address.setInputType(
        android.text.InputType.TYPE_CLASS_TEXT
            | android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
    form.addView(PearlDesign.note(activity, "查询会将公开地址发送给 pearlfortune.org。请填写矿机配置中的 PRL 收款地址。"));
    form.addView(name);
    form.addView(address);
    form.addView(
        button(
            "保存并查看矿工",
            () -> {
              try {
                book.save(name.getText().toString(), address.getText().toString());
                detail(
                    AddressBook.name(name.getText().toString()),
                    PearlAddress.normalize(address.getText().toString()));
              } catch (Exception e) {
                address.setError(e.getMessage());
              }
            }));
    show("添加 PearlFortune 矿工", form);
  }

  void detail(String name, String input) {
    final String address;
    try {
      address = input.isEmpty() ? "" : PearlAddress.normalize(input);
    } catch (Exception e) {
      notice(e.getMessage());
      return;
    }
    LinearLayout form = PearlDesign.form(activity);
    form.addView(PearlDesign.text(activity, name, 22, PearlDesign.INK, true));
    if (!address.isEmpty()) form.addView(PearlDesign.note(activity, FortuneApi.mask(address)));
    TextView status = PearlDesign.note(activity, "正在读取 PearlFortune 公开数据…");
    form.addView(status);
    LinearLayout metrics = PearlDesign.column(activity);
    form.addView(metrics);
    form.addView(button("刷新 PearlFortune 数据", () -> detail(name, address)));
    form.addView(button("添加算力与价格小组件", () -> pin(name, address)));
    form.addView(button("查看 PearlFortune 完整页面", () -> website(address)));
    form.addView(
        PearlDesign.note(
            activity, "估算算力根据矿池接受的贡献统计，自报算力由矿机上报，两者含义和单位可能不同。沿用矿池单位；不完整的窗口显示 —。在线连接数不等于独立矿机数量。"));
    show(address.isEmpty() ? "PearlFortune 矿池总览" : "PearlFortune 矿工详情", form);
    int token = ++generation;
    worker.execute(
        () -> {
          try {
            FortuneApi.Snapshot s =
                new FortuneApi(
                        url -> {
                          if (token != generation) throw new InterruptedException();
                          return PearlApi.httpsGet(url);
                        })
                    .fetch(address);
            activity.runOnUiThread(
                () -> {
                  if (token != generation || dialog == null) return;
                  status.setText(
                      activity.getString(
                          R.string.fortune_received,
                          MiningWidget.time(s.at, System.currentTimeMillis() / 1000, "算力更新")));
                  for (int hours : new int[] {1, 8, 24}) {
                    FortuneApi.Window w = s.window(hours);
                    metric(metrics, hours + " 小时 · 矿池估算", w.display());
                    if (!address.isEmpty())
                      metrics.addView(
                          PearlDesign.note(
                              activity,
                              "接受率 "
                                  + w.acceptance()
                                  + " · 已接受 "
                                  + (w.accepted == null ? "—" : w.accepted)
                                  + " / 已拒绝 "
                                  + (w.rejected == null ? "—" : w.rejected)
                                  + (w.complete ? "" : " · 统计不完整")));
                  }
                  if (!address.isEmpty()) {
                    metrics.addView(
                        PearlDesign.text(activity, s.connections(), 17, PearlDesign.INK, true));
                    if (!s.connectionError.isEmpty())
                      metrics.addView(PearlDesign.note(activity, s.connectionError));
                    for (FortuneApi.Worker w : s.workers) {
                      LinearLayout cell = PearlDesign.form(activity);
                      cell.setBackground(
                          PearlDesign.surface(activity, PearlDesign.BG, PearlDesign.LINE, 16));
                      cell.addView(PearlDesign.text(activity, w.name, 16, PearlDesign.INK, true));
                      cell.addView(
                          PearlDesign.note(
                              activity,
                              "矿机自报 "
                                  + (w.reported == null
                                      ? "—"
                                      : FortuneApi.formatRate(w.reported, "H/s"))
                                  + " · GPU "
                                  + (w.gpus == null ? "—" : w.gpus)
                                  + "\n"
                                  + (w.stale ? "自报数据已旧 / 未提供" : "自报数据已接收")
                                  + "\n"
                                  + MiningWidget.time(
                                      w.lastStats, System.currentTimeMillis() / 1000, "自报")));
                      metrics.addView(cell);
                    }
                  }
                });
          } catch (Exception e) {
            activity.runOnUiThread(
                () -> {
                  if (token == generation && dialog != null)
                    status.setText(activity.getString(R.string.fortune_error, e.getMessage()));
                });
          }
        });
  }

  private void metric(LinearLayout parent, String label, String value) {
    LinearLayout row = PearlDesign.form(activity);
    row.setBackground(PearlDesign.surface(activity, PearlDesign.BG, 0, 14));
    row.addView(PearlDesign.text(activity, label, 12, PearlDesign.MUTED, false));
    row.addView(PearlDesign.text(activity, value, 24, PearlDesign.INK, true));
    LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
    p.bottomMargin = PearlDesign.dp(activity, 8);
    parent.addView(row, p);
  }

  private void pin(String n, String a) {
    try {
      MiningWidget.pin(activity, new MiningWidget.Config(n, a));
    } catch (Exception e) {
      notice(e.getMessage());
    }
  }

  private void website(String address) {
    try {
      activity.startActivity(
          new Intent(Intent.ACTION_VIEW, Uri.parse(FortuneApi.website(address))));
    } catch (Exception e) {
      notice("无法打开浏览器，请访问 pearlfortune.org");
    }
  }

  private void notice(String s) {
    Toast.makeText(activity, s, Toast.LENGTH_LONG).show();
  }

  void pause() {
    generation++;
    if (dialog != null) {
      AlertDialog old = dialog;
      dialog = null;
      old.dismiss();
    }
  }

  void close() {
    pause();
    worker.shutdownNow();
  }
}
