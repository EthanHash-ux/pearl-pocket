package ai.pearl.wallet;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.net.Uri;
import android.text.InputType;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import java.math.BigInteger;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import org.json.JSONArray;
import org.json.JSONObject;

/** PRL-priced model catalogue, invoices, quota and explicit text inference. */
final class InferenceTools {
  private final Activity a;
  private final Supplier<WalletFlow> wallet;
  private final Supplier<String> slot;
  private final BooleanSupplier observing, hidden;
  private final InferenceApi api;
  private final ExecutorService worker = Executors.newSingleThreadExecutor();
  private volatile boolean active, busy;
  private volatile int generation;
  private AlertDialog dialog;

  InferenceTools(
      Activity a,
      Supplier<WalletFlow> w,
      Supplier<String> s,
      BooleanSupplier o,
      BooleanSupplier h) {
    this(a, w, s, o, h, new InferenceApi());
  }

  InferenceTools(
      Activity a,
      Supplier<WalletFlow> w,
      Supplier<String> s,
      BooleanSupplier o,
      BooleanSupplier h,
      InferenceApi api) {
    this.a = a;
    wallet = w;
    slot = s;
    observing = o;
    hidden = h;
    this.api = api;
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
    a.runOnUiThread(
        () -> {
          if (active && token == generation && !a.isDestroyed()) r.run();
        });
  }

  private interface Work {
    void run(int token) throws Exception;
  }

  private void work(Work w) {
    if (!active || busy) return;
    busy = true;
    int token = generation;
    worker.execute(
        () -> {
          try {
            w.run(token);
          } catch (Exception e) {
            ui(
                token,
                () -> notice("操作未完成", e.getMessage() == null ? "请查询原账单或调用记录" : e.getMessage()));
          } finally {
            busy = false;
          }
        });
  }

  private void notice(String title, String text) {
    wallet.get().notice(title, text);
  }

  private LinearLayout form() {
    return PearlDesign.form(a);
  }

  private void note(LinearLayout f, String s) {
    f.addView(PearlDesign.note(a, s));
  }

  private void button(LinearLayout f, String s, Runnable r) {
    f.addView(
        PearlDesign.button(
            a,
            s,
            PearlDesign.TEAL,
            PearlDesign.PALE,
            v -> {
              if (!busy) r.run();
            }));
  }

  private EditText field(LinearLayout f, String title, boolean secret) {
    EditText x = new EditText(a);
    x.setHint(title);
    x.setContentDescription(title);
    x.setTextColor(PearlDesign.INK);
    x.setHintTextColor(PearlDesign.MUTED);
    x.setInputType(
        InputType.TYPE_CLASS_TEXT
            | (secret
                ? InputType.TYPE_TEXT_VARIATION_PASSWORD
                : InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS));
    x.setImportantForAutofill(android.view.View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
    x.setImeOptions(android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING);
    PearlDesign.input(x);
    f.addView(x);
    return x;
  }

  private void display(String title, LinearLayout f) {
    if (dialog != null) dialog.dismiss();
    ScrollView scroll = new ScrollView(a);
    scroll.addView(f);
    dialog =
        new AlertDialog.Builder(a)
            .setTitle(title)
            .setView(scroll)
            .setNegativeButton("关闭", null)
            .create();
    dialog.show();
    PearlDesign.dialog(dialog);
  }

  private InferenceStore store() throws Exception {
    return new InferenceStore(a, slot.get(), wallet.get().address());
  }

  void show() {
    if (observing.getAsBoolean() || !wallet.get().exists()) {
      notice("选择手机钱包", "AI 付款与 API 账户绑定在手机钱包中。请先创建或选择钱包。");
      return;
    }
    try {
      InferenceStore s = store();
      JSONObject data = s.read();
      if (data == null) {
        connect();
        return;
      }
      home(s, data);
    } catch (Exception e) {
      notice("无法读取推理账户", e.getMessage());
    }
  }

  private void connect() {
    LinearLayout f = form();
    note(
        f,
        "用 PRL 购买模型调用次数，并在钱包内试用 API。\n"
            + "当前版本尚未配置公共收款服务。请输入你信任的 PRL 推理服务商域名及其邀请码，或导入已有 API Key。\n"
            + "Pearl 官方推理平台目前使用美元余额计费；此处是独立商家的 PRL 支付协议。钱包助记词和私钥只保留在手机。");
    EditText base = field(f, "服务商 HTTPS 域名", false),
        code = field(f, "服务商邀请码（创建账户时填写）", true),
        key = field(f, "已有 PRL 推理 API Key（可选）", true);
    button(
        f,
        "连接并核对服务商",
        () -> {
          try {
            String url = InferenceApi.base(base.getText().toString()),
                invite = code.getText().toString(),
                existing = key.getText().toString().trim();
            if (!existing.isEmpty()) InferenceApi.validateKey(existing);
            InferenceStore s = store();
            work(
                t -> {
                  JSONObject catalog = api.catalog(url);
                  ui(
                      t,
                      () -> {
                        LinearLayout review = form();
                        note(
                            review,
                            "服务商 · "
                                + catalog.optString("merchant")
                                + "\n"
                                + url
                                + "\n收款扩展公钥\n"
                                + catalog.optString("account_xpub")
                                + "\n"
                                + "请向商家独立核对域名和公钥。此服务会接收你的公开付款交易、API 提示词与调用请求。API Key"
                                + " 将独立加密保存于本机。");
                        button(
                            review,
                            "确认绑定此服务商",
                            () ->
                                work(
                                    k -> {
                                      String token =
                                          existing.isEmpty() ? api.register(url, invite) : existing;
                                      s.connect(url, token, catalog);
                                      ui(
                                          k,
                                          () -> {
                                            code.setText("");
                                            key.setText("");
                                            show();
                                          });
                                    }));
                        display("核对 AI 服务商", review);
                      });
                });
          } catch (Exception e) {
            base.setError(e.getMessage());
          }
        });
    button(
        f,
        "查看 Pearl 官方推理平台",
        () ->
            a.startActivity(
                new Intent(
                    Intent.ACTION_VIEW, Uri.parse("https://platform.pearlresearch.ai/docs"))));
    display("AI 推理 · PRL 支付", f);
  }

  private void home(InferenceStore s, JSONObject data) throws Exception {
    LinearLayout f = form();
    JSONObject catalog = data.getJSONObject("catalog");
    note(
        f,
        catalog.getString("merchant")
            + "\n"
            + data.getString("base")
            + "\nPRL 支付 · 每模型按调用次数计费\n账单开通后 API Key 仅授予推理权限，不授予钱包转账权限。");
    button(
        f,
        "模型与 PRL 价格",
        () ->
            work(
                t -> {
                  JSONObject fresh = api.catalog(data.getString("base"));
                  if (!catalog.getString("account_xpub").equals(fresh.getString("account_xpub")))
                    throw new IllegalArgumentException("服务商收款公钥已变化，停止付款");
                  ui(t, () -> models(s, data, fresh));
                }));
    button(
        f,
        "我的账单与 API 额度",
        () -> {
          try {
            invoices(s);
          } catch (Exception e) {
            notice("无法读取账单", e.getMessage());
          }
        });
    button(
        f,
        "调用记录 / 查询未完成请求",
        () -> {
          try {
            calls(s);
          } catch (Exception e) {
            notice("无法读取记录", e.getMessage());
          }
        });
    button(f, "查看 API 接入信息", () -> apiInfo(data));
    display("AI 推理 · PRL 支付", f);
  }

  private void models(InferenceStore s, JSONObject data, JSONObject catalog) {
    try {
      LinearLayout f = form();
      JSONArray rows = catalog.getJSONArray("models");
      for (int i = 0; i < rows.length(); i++) {
        JSONObject m = rows.getJSONObject(i);
        note(
            f,
            m.getString("id")
                + "\n每次 "
                + PearlAmount.format(new BigInteger(m.getString("price_per_request_units")))
                + " PRL · 输出最多 "
                + m.getInt("max_tokens")
                + " token · 输入最多 "
                + m.getInt("max_input_bytes")
                + " 字节");
        button(f, "购买调用次数 · " + m.getString("id"), () -> quantity(s, data, catalog, m));
      }
      display("模型与 PRL 价格", f);
    } catch (Exception e) {
      notice("模型数据无效", e.getMessage());
    }
  }

  private void quantity(InferenceStore s, JSONObject data, JSONObject catalog, JSONObject m) {
    LinearLayout f = form();
    note(f, "模型 · " + m.optString("id") + "\n按次数计费，输出 token 上限包含模型推理消耗。网络费另列，下一步先生成账单，随后单独确认付款。");
    EditText count = field(f, "购买调用次数（1–1000）", false);
    count.setInputType(InputType.TYPE_CLASS_NUMBER);
    count.setText(String.valueOf(10));
    button(
        f,
        "生成 PRL 账单",
        () -> {
          try {
            int n = Integer.parseInt(count.getText().toString());
            if (n < 1 || n > 1000) throw new IllegalArgumentException("次数应为 1–1000");
            work(
                t -> {
                  JSONObject inv =
                      api.create(data.getString("base"), data.getString("key"), catalog, m, n);
                  s.invoice(inv);
                  ui(
                      t,
                      () -> {
                        try {
                          detail(s, inv);
                        } catch (Exception e) {
                          notice("账单已保存", e.getMessage());
                        }
                      });
                });
          } catch (Exception e) {
            count.setError(e.getMessage());
          }
        });
    display("购买推理调用次数", f);
  }

  private void invoices(InferenceStore s) throws Exception {
    LinearLayout f = form();
    JSONArray rows = s.read().getJSONArray("invoices");
    if (rows.length() == 0) note(f, "暂无账单。先选择模型并生成账单。");
    for (int i = rows.length() - 1; i >= 0; i--) {
      JSONObject inv = rows.getJSONObject(i);
      button(
          f,
          inv.getJSONObject("model").getString("id")
              + " · "
              + inv.getString("status")
              + " · "
              + inv.getString("id").substring(0, 8),
          () -> {
            try {
              detail(s, s.invoice(inv.getString("id")));
            } catch (Exception e) {
              notice("账单读取失败", e.getMessage());
            }
          });
    }
    display("我的账单与额度", f);
  }

  private void detail(InferenceStore s, JSONObject inv) throws Exception {
    JSONObject account = s.read();
    InferenceApi.validateInvoice(inv, account.getJSONObject("catalog"));
    LinearLayout f = form();
    note(
        f,
        "账单 · "
            + inv.getString("id")
            + "\n模型 · "
            + inv.getJSONObject("model").getString("id")
            + "\n购买 "
            + inv.getInt("request_count")
            + " 次 · 总价 "
            + (hidden.getAsBoolean()
                ? "••••"
                : PearlAmount.format(new BigInteger(inv.getString("amount_units"))))
            + " PRL\n状态 · "
            + inv.getString("status")
            + "\n剩余 "
            + (hidden.getAsBoolean() ? "••••" : inv.getInt("remaining_requests"))
            + " 次\n收款地址\n"
            + inv.getString("address")
            + "\n付款期限 · "
            + new java.util.Date(inv.getLong("expires_at") * 1000)
            + "\n付款后须等 6 个 Pearl 确认，点击查询开通额度。超时、付错金额或商家停服需联系商家处理。");
    if (inv.has("local_txid")) note(f, "本机付款交易\n" + inv.getString("local_txid"));
    if ("unpaid".equals(inv.getString("status"))
        && !inv.has("local_txid")
        && inv.getLong("expires_at") > System.currentTimeMillis() / 1000 + 120)
      button(
          f,
          "用当前钱包支付 PRL",
          () -> {
            try {
              wallet
                  .get()
                  .inferencePay(
                      new InferencePayment(slot.get(), wallet.get().address(), s, api, inv));
            } catch (Exception e) {
              notice("无法付款", e.getMessage());
            }
          });
    button(
        f,
        "查询付款并开通额度",
        () ->
            work(
                t -> {
                  JSONObject fresh =
                      api.invoice(
                          account.getString("base"), account.getString("key"), inv.getString("id"));
                  InferenceApi.unchanged(inv, fresh);
                  if (!"paid".equals(fresh.getString("status")) && inv.has("local_txid"))
                    fresh =
                        api.claim(
                            account.getString("base"),
                            account.getString("key"),
                            inv.getString("id"),
                            inv.getString("local_txid"));
                  InferenceApi.validateInvoice(fresh, account.getJSONObject("catalog"));
                  InferenceApi.unchanged(inv, fresh);
                  if ("paid".equals(fresh.getString("status"))
                      && inv.has("local_txid")
                      && !inv.getString("local_txid").equals(fresh.getString("txid")))
                    throw new IllegalArgumentException("服务商确认的付款与本机交易不同");
                  s.invoice(fresh);
                  JSONObject result = fresh;
                  ui(
                      t,
                      () -> {
                        try {
                          detail(s, result);
                        } catch (Exception e) {
                          notice("查询结果已保存", e.getMessage());
                        }
                      });
                }));
    if ("paid".equals(inv.getString("status")) && inv.getInt("remaining_requests") > 0)
      button(f, "试调用 AI API", () -> prompt(s, inv));
    display("PRL 推理账单", f);
  }

  private void prompt(InferenceStore s, JSONObject inv) {
    try {
      JSONArray calls = s.read().getJSONArray("calls");
      for (int i = 0; i < calls.length(); i++) {
        JSONObject c = calls.getJSONObject(i);
        if (inv.getString("id").equals(c.getString("invoice"))
            && ("pending".equals(c.getString("status"))
                || "unknown".equals(c.getString("status")))) {
          notice("先查询上一条请求", "这张账单已有结果不明的调用。请先查询原请求或联系商家核对，避免再次消耗额度。");
          return;
        }
      }
    } catch (Exception e) {
      notice("调用记录读取失败", e.getMessage());
      return;
    }
    LinearLayout f = form();
    note(f, "提示词会发送给商家及其推理服务。点击发送后消耗 1 次额度。钱包不发送助记词、私钥和密码。请求结果不明时请查询原调用记录。");
    EditText input = field(f, "发送给 AI 的提示词", false);
    input.setSingleLine(false);
    input.setMinLines(3);
    input.setInputType(
        InputType.TYPE_CLASS_TEXT
            | InputType.TYPE_TEXT_FLAG_MULTI_LINE
            | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
    button(
        f,
        "发送请求 · 消耗 1 次",
        () -> {
          String text = input.getText().toString();
          if (text.trim().isEmpty()
              || text.getBytes(java.nio.charset.StandardCharsets.UTF_8).length
                  > inv.optJSONObject("model").optInt("max_input_bytes")) {
            input.setError("请填写提示词，并保持在商家输入上限内");
            return;
          }
          work(
              t -> {
                JSONObject account = s.read();
                String id = InferenceApi.newRequestId();
                s.call(id, inv.getString("id"), "pending");
                try {
                  JSONObject response =
                      api.chat(account.getString("base"), account.getString("key"), inv, text, id);
                  String answer =
                      response
                          .getJSONArray("choices")
                          .getJSONObject(0)
                          .getJSONObject("message")
                          .getString("content");
                  s.call(id, inv.getString("id"), "complete");
                  ui(
                      t,
                      () -> {
                        input.setText("");
                        notice("AI 回复", answer + "\n\n调用 ID · " + id);
                      });
                } catch (Exception e) {
                  s.call(id, inv.getString("id"), "unknown");
                  throw e;
                }
              });
        });
    display("试调用推理 API", f);
  }

  private void calls(InferenceStore s) throws Exception {
    LinearLayout f = form();
    JSONObject account = s.read();
    JSONArray rows = account.getJSONArray("calls");
    if (rows.length() == 0) note(f, "暂无调用记录。提示词和回复不保存在钱包调用记录中。");
    for (int i = rows.length() - 1; i >= 0; i--) {
      JSONObject c = rows.getJSONObject(i);
      note(f, c.getString("id") + "\n" + c.getString("status"));
      if (!"complete".equals(c.getString("status")))
        button(
            f,
            "查询原请求 · " + c.getString("id").substring(0, 8),
            () ->
                work(
                    t -> {
                      JSONObject result =
                          api.requestStatus(
                              account.getString("base"),
                              account.getString("key"),
                              c.getString("id"));
                      String status = result.getString("status");
                      if (!java.util.Arrays.asList("pending", "unknown", "complete", "refunded")
                          .contains(status)) throw new IllegalArgumentException("调用状态无效");
                      s.call(c.getString("id"), c.getString("invoice"), status);
                      ui(
                          t,
                          () ->
                              notice(
                                  "调用查询",
                                  status
                                      + (result.has("response")
                                          ? "\n\n"
                                              + result
                                                  .optJSONObject("response")
                                                  .optJSONArray("choices")
                                                  .optJSONObject(0)
                                                  .optJSONObject("message")
                                                  .optString("content")
                                          : "\n未完成时请向商家提供调用 ID，钱包不会自动再次调用。")));
                    }));
    }
    display("AI 调用记录", f);
  }

  private void apiInfo(JSONObject data) {
    LinearLayout f = form();
    note(
        f,
        "API Base URL\n"
            + data.optString("base")
            + "/v1\n"
            + "Authorization: Bearer <API Key>\n"
            + "POST /chat/completions\n"
            + "额外请求头：X-Pearl-Invoice 为已付款账单 ID；Idempotency-Key 为每次新调用的唯一 ID，查询或重试时保持相同。\n"
            + "仅支持文本、非流式请求。请保留 API Key，重装钱包不会由助记词恢复它。");
    button(
        f,
        "复制推理 API Key",
        () ->
            new AlertDialog.Builder(a)
                .setTitle("复制 API Key")
                .setMessage("持有此 Key 可消耗商家账户内的推理额度。请只粘贴到你信任的应用，系统剪贴板可能向其它应用提供它。")
                .setNegativeButton("取消", null)
                .setPositiveButton(
                    "复制",
                    (d, w) -> {
                      ClipData clip = ClipData.newPlainText("推理 API Key", data.optString("key"));
                      if (android.os.Build.VERSION.SDK_INT >= 33) {
                        android.os.PersistableBundle extra = new android.os.PersistableBundle();
                        extra.putBoolean("android.content.extra.IS_SENSITIVE", true);
                        clip.getDescription().setExtras(extra);
                      }
                      ((ClipboardManager) a.getSystemService(Activity.CLIPBOARD_SERVICE))
                          .setPrimaryClip(clip);
                    })
                .show());
    display("推理 API 接入", f);
  }
}
