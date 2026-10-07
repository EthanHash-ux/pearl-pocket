package ai.pearl.wallet;

import android.app.Activity;
import android.app.AlertDialog;
import android.widget.*;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Public address selection stays separate from WalletFlow's signing identity. */
final class WatchTools {
  private final Activity activity;
  private final WatchBook book;
  private final Consumer<String> selected;
  private final Supplier<String> current;
  private final Runnable wallets;
  private AlertDialog dialog;

  WatchTools(Activity a, Consumer<String> selected, Supplier<String> current) {
    this(a, selected, current, null);
  }

  WatchTools(Activity a, Consumer<String> selected, Supplier<String> current, Runnable wallets) {
    activity = a;
    book = new WatchBook(PublicStore.of(a));
    this.selected = selected;
    this.current = current;
    this.wallets = wallets;
  }

  private Button button(String label, Runnable r) {
    return PearlDesign.button(activity, label, PearlDesign.TEAL, PearlDesign.PALE, v -> r.run());
  }

  void show() {
    try {
      pause();
      LinearLayout form = PearlDesign.form(activity);
      if (wallets != null) form.addView(button("管理手机钱包", () -> { pause(); wallets.run(); }));
      form.addView(PearlDesign.note(activity, "观察地址只查询公开余额和交易，不导入私钥，不能发送资金。查询服务会看到你选择的地址。"));
      form.addView(
          button(
              "使用手机钱包",
              () -> {
                pause();
                selected.accept("");
              }));
      for (WatchBook.Entry e : book.list()) {
        form.addView(PearlDesign.note(activity, e.name + " · 只读\n" + e.address));
        form.addView(
            button(
                "观察 " + e.name,
                () -> {
                  pause();
                  selected.accept(e.address);
                }));
        form.addView(
            button(
                "移除 " + e.name,
                () -> {
                  AlertDialog d =
                      new AlertDialog.Builder(activity)
                          .setTitle("移除观察地址？")
                          .setMessage(e.address + "\n移除只影响本机列表。")
                          .setNegativeButton("取消", null)
                          .setPositiveButton(
                              "移除",
                              (x, w) -> {
                                try {
                                  book.remove(e.address);
                                  if (e.address.equals(current.get())) selected.accept("");
                                  show();
                                } catch (Exception err) {
                                  notice(err.getMessage());
                                }
                              })
                          .create();
                  d.show();
                  PearlDesign.dialog(d);
                }));
      }
      form.addView(button("添加观察地址", this::add));
      ScrollView scroll = new ScrollView(activity);
      scroll.addView(form);
      dialog =
          new AlertDialog.Builder(activity)
              .setTitle("钱包与观察地址")
              .setView(scroll)
              .setNegativeButton("关闭", null)
              .create();
      dialog.show();
      PearlDesign.dialog(dialog);
    } catch (Exception e) {
      notice(e.getMessage());
    }
  }

  private void add() {
    LinearLayout form = PearlDesign.form(activity);
    EditText name = new EditText(activity), address = new EditText(activity);
    name.setHint("观察地址名称");
    address.setHint("完整 Pearl 主网观察地址");
    name.setContentDescription("观察地址名称");
    address.setContentDescription("完整 Pearl 主网观察地址");
    PearlDesign.input(name);
    PearlDesign.input(address);
    form.addView(name);
    form.addView(address);
    pause();
    dialog =
        new AlertDialog.Builder(activity)
            .setTitle("添加观察地址")
            .setView(form)
            .setNegativeButton("取消", (d, w) -> show())
            .setPositiveButton("保存观察地址", null)
            .create();
    dialog.show();
    PearlDesign.dialog(dialog);
    dialog
        .getButton(-1)
        .setOnClickListener(
            v -> {
              try {
                book.save(name.getText().toString(), address.getText().toString());
                show();
              } catch (Exception e) {
                address.setError(e.getMessage());
              }
            });
  }

  private void notice(String m) {
    AlertDialog d =
        new AlertDialog.Builder(activity)
            .setTitle("观察地址")
            .setMessage(m)
            .setPositiveButton("知道了", null)
            .create();
    d.show();
    PearlDesign.dialog(d);
  }

  void pause() {
    if (dialog != null) {
      dialog.dismiss();
      dialog = null;
    }
  }
}
