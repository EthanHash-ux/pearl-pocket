package ai.pearl.wallet;

import android.app.Activity;
import android.app.AlertDialog;
import android.widget.*;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;

/** Paged public history with local search, annotations and explicit CSV export. */
final class HistoryTools {
  private final Activity activity;
  private final PearlApi api;
  private final TransactionLedger ledger;
  private final BooleanSupplier hidden;
  private final ExecutorService worker = Executors.newSingleThreadExecutor();
  private AlertDialog dialog;
  private volatile int generation;

  HistoryTools(Activity a, PearlApi p, BooleanSupplier h) {
    activity = a;
    api = p;
    hidden = h;
    ledger = new TransactionLedger(PublicStore.of(a));
  }

  private EditText field(String hint) {
    EditText e = new EditText(activity);
    e.setHint(hint);
    e.setContentDescription(hint);
    e.setSingleLine(true);
    PearlDesign.input(e);
    return e;
  }

  private Button button(String label, Runnable r) {
    return PearlDesign.button(activity, label, PearlDesign.TEAL, PearlDesign.PALE, v -> r.run());
  }

  void show(String input) {
    final String address;
    try {
      address = PearlAddress.normalize(input);
    } catch (Exception e) {
      notice("先选择钱包或观察地址");
      return;
    }
    pause();
    int token = ++generation;
    List<PearlApi.Transaction> loaded = new ArrayList<>();
    Set<String> ids = new HashSet<>();
    int[] next = {1}, pages = {1};
    boolean[] busy = {false};
    String[] direction = {"all"};
    LinearLayout form = PearlDesign.form(activity);
    form.addView(
        PearlDesign.note(
            activity, "分页查看全部历史。搜索和日期筛选作用于当前页；使用上一页 / 下一页浏览更早记录。金额是本地址净变化，转出包含费用与找零。"));
    EditText query = field("搜索交易 ID、地址、金额或备注"),
        from = field("开始日期 YYYY-MM-DD（可选）"),
        to = field("结束日期 YYYY-MM-DD（可选）");
    form.addView(query);
    form.addView(from);
    form.addView(to);
    Button previous = button("上一页", () -> {});
    TextView status = PearlDesign.note(activity, "历史加载中");
    form.addView(status);
    LinearLayout rows = PearlDesign.column(activity);
    form.addView(rows);
    Button more = button("下一页", () -> {});
    form.addView(more);
    Runnable[] refreshView = new Runnable[1];
    Runnable render =
        () -> {
          rows.removeAllViews();
          try {
            long start = day(from.getText().toString(), false),
                end = day(to.getText().toString(), true);
            if (start > 0 && end > 0 && start >= end)
              throw new IllegalArgumentException("结束日期不能早于开始日期");
            int count = 0;
            for (PearlApi.Transaction tx : loaded)
              if (ledger.matches(
                  address, tx, query.getText().toString(), direction[0], start, end)) {
                count++;
                LinearLayout card = PearlDesign.column(activity);
                card.setPadding(12, 14, 12, 14);
                card.setBackground(PearlDesign.surface(activity, PearlDesign.BG, 0, 12));
                card.addView(
                    PearlDesign.text(
                        activity,
                        (tx.netGrains.signum() >= 0 ? "接收 " : "发送 ")
                            + (hidden.getAsBoolean()
                                ? "•••• PRL"
                                : PearlAmount.format(tx.netGrains) + " PRL"),
                        15,
                        PearlDesign.INK,
                        true));
                card.addView(
                    PearlDesign.note(
                        activity,
                        (tx.confirmations == 0 ? "待确认" : tx.confirmations + " 次确认")
                            + " · "
                            + (tx.time == 0
                                ? "—"
                                : java.time.Instant.ofEpochSecond(tx.time).toString())
                            + "\n"
                            + tx.id
                            + (!ledger.note(address, tx.id).isEmpty()
                                ? "\n备注：" + ledger.note(address, tx.id)
                                : "")));
                card.addView(
                    button(
                        "查看 / 备注 " + tx.id.substring(0, 8),
                        () ->
                            detail(
                                address,
                                tx,
                                () -> {
                                  if (refreshView[0] != null) refreshView[0].run();
                                })));
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
                lp.bottomMargin = 12;
                rows.addView(card, lp);
              }
            if (count == 0)
              rows.addView(PearlDesign.note(activity, loaded.isEmpty() ? "暂无交易记录" : "当前页没有匹配项"));
            status.setText(
                activity.getString(
                    R.string.history_status,
                    loaded.size(),
                    count,
                    Math.max(0, next[0] - 1),
                    pages[0],
                    next[0] > pages[0] ? " · 已到末页" : ""));
          } catch (Exception e) {
            status.setText(e.getMessage());
          }
          more.setEnabled(!busy[0] && next[0] <= pages[0]);
          previous.setEnabled(!busy[0] && next[0] > 2);
        };
    refreshView[0] = render;
    LinearLayout choices = new LinearLayout(activity);
    for (String label : new String[] {"全部", "接收", "发送"})
      choices.addView(
          button(
              label,
              () -> {
                direction[0] =
                    label.equals("全部") ? "all" : label.equals("接收") ? "received" : "sent";
                render.run();
              }),
          new LinearLayout.LayoutParams(0, -2, 1));
    form.addView(choices, 4);
    android.text.TextWatcher watcher =
        new android.text.TextWatcher() {
          public void beforeTextChanged(CharSequence s, int a, int c, int f) {}

          public void onTextChanged(CharSequence s, int a, int b, int c) {
            render.run();
          }

          public void afterTextChanged(android.text.Editable e) {}
        };
    query.addTextChangedListener(watcher);
    from.addTextChangedListener(watcher);
    to.addTextChangedListener(watcher);
    more.setOnClickListener(
        v -> {
          if (busy[0] || next[0] > pages[0]) return;
          busy[0] = true;
          more.setEnabled(false);
          status.setText(activity.getString(R.string.history_loading, next[0]));
          int requested = next[0];
          worker.execute(
              () -> {
                try {
                  PearlApi.HistoryPage result = api.history(address, requested, 25);
                  activity.runOnUiThread(
                      () -> {
                        if (token != generation) return;
                        loaded.clear();
                        ids.clear();
                        for (PearlApi.Transaction tx : result.account.transactions)
                          if (ids.add(tx.id)) loaded.add(tx);
                        pages[0] = result.pages;
                        next[0]++;
                        busy[0] = false;
                        render.run();
                      });
                } catch (Exception e) {
                  activity.runOnUiThread(
                      () -> {
                        if (token == generation) {
                          busy[0] = false;
                          status.setText(
                              activity.getString(R.string.history_load_error, e.getMessage()));
                          more.setEnabled(true);
                        }
                      });
                }
              });
        });
    LinearLayout paging = new LinearLayout(activity);
    form.removeView(more);
    paging.addView(previous, new LinearLayout.LayoutParams(0, -2, 1));
    paging.addView(more, new LinearLayout.LayoutParams(0, -2, 1));
    form.addView(paging, form.indexOfChild(rows));
    previous.setOnClickListener(
        v -> {
          if (!busy[0] && next[0] > 2) {
            next[0] -= 2;
            more.performClick();
          }
        });
    form.addView(
        button(
            "导出当前页 CSV",
            () -> {
              if (loaded.isEmpty()) {
                notice("请先加载交易记录");
                return;
              }
              AlertDialog confirm =
                  new AlertDialog.Builder(activity)
                      .setTitle("导出交易记录？")
                      .setMessage(
                          "将导出当前页的 "
                              + loaded.size()
                              + " 笔交易，包含实际金额、公开地址和本机备注。隐藏余额不会隐藏导出文件中的金额。文件不含助记词或私钥。")
                      .setNegativeButton("取消", null)
                      .setPositiveButton(
                          "导出",
                          (d, w) -> {
                            try {
                              activity.startActivity(
                                  PublicShareProvider.intent(
                                      activity,
                                      ledger
                                          .csv(address, loaded)
                                          .getBytes(java.nio.charset.StandardCharsets.UTF_8),
                                      "csv",
                                      "分享交易记录 CSV"));
                            } catch (Exception e) {
                              notice(e.getMessage());
                            }
                          })
                      .create();
              confirm.show();
              PearlDesign.dialog(confirm);
            }));
    Button exportAll = button("导出全部历史 CSV（最多 5000 笔）", () -> {});
    form.addView(exportAll, form.indexOfChild(rows));
    exportAll.setOnClickListener(
        v -> {
          AlertDialog confirm =
              new AlertDialog.Builder(activity)
                  .setTitle("导出全部历史？")
                  .setMessage(
                      "会读取所有分页，并导出实际金额、地址和本机备注。最多 5000 笔；期间历史发生变化会停止，请重试。文件不含私钥。导出期间请保持窗口打开。")
                  .setNegativeButton("取消", null)
                  .setPositiveButton(
                      "导出",
                      (d, w) -> {
                        if (busy[0]) return;
                        busy[0] = true;
                        more.setEnabled(false);
                        previous.setEnabled(false);
                        exportAll.setEnabled(false);
                        status.setText("正在读取全部历史…");
                        worker.execute(
                            () -> {
                              try {
                                List<PearlApi.Transaction> all = new ArrayList<>();
                                Set<String> seen = new HashSet<>();
                                long total = -1;
                                int totalPages = 1;
                                for (int page = 1; page <= totalPages; page++) {
                                  if (token != generation) return;
                                  PearlApi.HistoryPage result = api.history(address, page, 100);
                                  if (page == 1) {
                                    total = result.account.transactionCount;
                                    totalPages = result.pages;
                                    if (total > 5000 || totalPages > 50)
                                      throw new IllegalArgumentException("超过 5000 笔，请分批导出当前页");
                                  }
                                  if (result.account.transactionCount != total
                                      || result.pages != totalPages)
                                    throw new IllegalArgumentException("历史发生变化，请重试导出");
                                  for (PearlApi.Transaction tx : result.account.transactions) {
                                    if (!seen.add(tx.id))
                                      throw new IllegalArgumentException("分页记录发生变化，请重试");
                                    all.add(tx);
                                  }
                                  final int progress = page;
                                  activity.runOnUiThread(
                                      () -> {
                                        if (token == generation)
                                          status.setText(
                                              activity.getString(
                                                  R.string.history_export_progress,
                                                  progress,
                                                  result.pages));
                                      });
                                }
                                if (all.size() != total)
                                  throw new IllegalArgumentException("历史记录不完整，请重试");
                                byte[] csv =
                                    ledger
                                        .csv(address, all)
                                        .getBytes(java.nio.charset.StandardCharsets.UTF_8);
                                android.content.Intent share =
                                    PublicShareProvider.intent(activity, csv, "csv", "分享全部交易历史");
                                activity.runOnUiThread(
                                    () -> {
                                      if (token != generation) return;
                                      busy[0] = false;
                                      exportAll.setEnabled(true);
                                      render.run();
                                      activity.startActivity(share);
                                    });
                              } catch (Exception e) {
                                activity.runOnUiThread(
                                    () -> {
                                      if (token == generation) {
                                        busy[0] = false;
                                        exportAll.setEnabled(true);
                                        render.run();
                                        status.setText(
                                            activity.getString(
                                                R.string.history_export_error, e.getMessage()));
                                      }
                                    });
                              }
                            });
                      })
                  .create();
          confirm.show();
          PearlDesign.dialog(confirm);
        });
    ScrollView scroll = new ScrollView(activity);
    scroll.setFocusableInTouchMode(true);
    scroll.setDescendantFocusability(android.view.ViewGroup.FOCUS_BEFORE_DESCENDANTS);
    scroll.requestFocus();
    scroll.addView(form);
    dialog =
        new AlertDialog.Builder(activity)
            .setTitle("完整交易历史")
            .setView(scroll)
            .setNegativeButton("关闭", null)
            .create();
    dialog.show();
    PearlDesign.dialog(dialog);
    dialog.setOnDismissListener(
        d -> {
          if (generation == token) generation++;
        });
    more.performClick();
  }

  private void detail(String address, PearlApi.Transaction tx, Runnable refresh) {
    try {
      EditText note = field("本机交易备注（最多 160 字）");
      note.setText(ledger.note(address, tx.id));
      AlertDialog d =
          new AlertDialog.Builder(activity)
              .setTitle("交易备注")
              .setMessage(tx.id + "\n备注仅保存在本机。")
              .setView(note)
              .setNegativeButton("取消", null)
              .setNeutralButton(
                  "区块浏览器",
                  (x, w) ->
                      activity.startActivity(
                          new android.content.Intent(
                              android.content.Intent.ACTION_VIEW,
                              android.net.Uri.parse(
                                  "https://explorer.pearlresearch.ai/tx/"
                                      + tx.id
                                      + "?network=mainnet"))))
              .setPositiveButton("保存备注", null)
              .create();
      d.show();
      PearlDesign.dialog(d);
      d.getButton(-1)
          .setOnClickListener(
              v -> {
                try {
                  ledger.note(address, tx.id, note.getText().toString());
                  d.dismiss();
                  refresh.run();
                } catch (Exception e) {
                  note.setError(e.getMessage());
                }
              });
    } catch (Exception e) {
      notice(e.getMessage());
    }
  }

  private static String totalPagesLabel(int pages) {
    return Integer.toString(pages);
  }

  static long day(String input, boolean end) {
    if (input.trim().isEmpty()) return 0;
    try {
      LocalDate d = LocalDate.parse(input.trim());
      return (end ? d.plusDays(1) : d).atStartOfDay(ZoneId.systemDefault()).toEpochSecond();
    } catch (Exception e) {
      throw new IllegalArgumentException("日期需为 YYYY-MM-DD");
    }
  }

  private void notice(String value) {
    AlertDialog d =
        new AlertDialog.Builder(activity)
            .setTitle("交易历史")
            .setMessage(value)
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
