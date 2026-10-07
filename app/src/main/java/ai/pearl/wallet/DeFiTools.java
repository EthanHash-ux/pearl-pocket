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
import android.widget.TextView;
import java.math.BigInteger;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import org.json.JSONObject;

/** Native, operation-scoped lending flow. Each approval and deposit requires its own review. */
final class DeFiTools {
    private final Activity activity;
    private final Supplier<WalletFlow> wallet;
    private final Supplier<String> slot, name;
    private final BooleanSupplier observing, hidden;
    private final DeFiApi api;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private AlertDialog dialog;
    private volatile boolean active, busy;
    private volatile int generation;
    DeFiTools(Activity a, Supplier<WalletFlow> w, Supplier<String> s, Supplier<String> n, BooleanSupplier o, BooleanSupplier h) {
        this(a, w, s, n, o, h, new DeFiApi());
    }
    DeFiTools(Activity a, Supplier<WalletFlow> w, Supplier<String> s, Supplier<String> n, BooleanSupplier o, BooleanSupplier h, DeFiApi api) {
        activity = a; wallet = w; slot = s; name = n; observing = o; hidden = h; this.api = api;
    }
    boolean canLeave() { return !busy; }
    void resume() { active = true; }
    void pause() { active = false; generation++; if (dialog != null) { dialog.dismiss(); dialog = null; } }
    void close() { pause(); worker.shutdown(); }
    private void ui(int token, Runnable r) { activity.runOnUiThread(() -> { if (active && token == generation && !activity.isDestroyed()) r.run(); }); }
    private void display(String title, LinearLayout view) {
        if (dialog != null) dialog.dismiss();
        ScrollView scroll = new ScrollView(activity); scroll.addView(view);
        dialog = new AlertDialog.Builder(activity).setTitle(title).setView(scroll).setNegativeButton("关闭", (d,w) -> generation++).create();
        dialog.setOnCancelListener(d -> generation++);
        dialog.show(); PearlDesign.dialog(dialog);
    }
    private void note(LinearLayout view, String s) { view.addView(PearlDesign.note(activity, s)); }
    private void button(LinearLayout view, String title, Runnable action) { view.addView(PearlDesign.button(activity, title, PearlDesign.TEAL, PearlDesign.PALE, v -> action.run())); }
    private void error(Exception e) { wallet.get().notice("DeFi 操作未完成", e.getMessage() == null ? "请稍后重试，已签名交易会保留" : e.getMessage()); }
    void show() {
        if (!active || busy) return;
        if (observing.getAsBoolean() || !wallet.get().exists()) { wallet.get().notice("选择手机钱包", "切换到已创建或恢复的手机钱包，再打开 DeFi。"); return; }
        try {
            WalletFlow flow = wallet.get(); String selected = slot.get(), pearl = flow.address();
            String eth = new EthereumAccount(activity, selected).load(pearl);
            if (eth.isEmpty()) {
                LinearLayout view = PearlDesign.form(activity);
                note(view, "Aave V3 · Ethereum 主网 · USDC\n提供借贷流动性，获得随利息增长的 aUSDC 凭证。这是单资产借贷存款。利率浮动，存在协议合约风险。");
                note(view, "Ethereum 地址与 Pearl 地址不同，来自当前钱包的同一份助记词，路径 " + EthereumAccount.PATH + "。请先生成地址，再向该地址转入 Ethereum 主网 USDC 和用于手续费的 ETH。\n本版支持此借贷流程，普通 ETH / USDC 转账需在兼容钱包恢复同一助记词后管理。");
                button(view, "验证并生成 Ethereum 地址", () -> {
                    if (dialog != null) dialog.dismiss();
                    flow.ethereumAddress(result -> { if (selected.equals(slot.get())) show(); });
                });
                display("DeFi 借贷流动性", view); return;
            }
            load(selected, eth);
        } catch (Exception e) { error(e); }
    }
    private void load(String selected, String eth) {
        busy = true; int token = generation;
        LinearLayout loading = PearlDesign.form(activity); note(loading, "正在核验 Aave 市场、USDC 精度和当前仓位…"); display("DeFi 借贷流动性", loading);
        worker.execute(() -> {
            try {
                JSONObject pending = new PendingEthereum(activity, selected, eth).load();
                if (pending != null) {
                    ui(token, () -> pendingSummary(selected, eth, pending));
                    return;
                }
                DeFiApi.Position p = api.position(eth);
                ui(token, () -> position(selected, eth, p, pending));
            } catch (Exception e) { ui(token, () -> error(e)); }
            finally { busy = false; }
        });
    }
    private String amount(BigInteger value) { return hidden.getAsBoolean() ? "••••" : DeFiAmount.usdc(value); }
    private void pendingSummary(String selected, String eth, JSONObject signed) {
        LinearLayout view = PearlDesign.form(activity);
        note(view, name.get() + " · Ethereum 主网\n" + eth + "\n\nDeFi 交易待确认\n" + signed.optString("hash") + "\n\n原签名已保留。处理这笔交易后，再刷新仓位或签署新交易。");
        button(view, "查询或重发待确认 DeFi 交易", () -> pending(selected, eth, signed));
        display("DeFi 借贷流动性", view);
    }
    private void position(String selected, String eth, DeFiApi.Position p, JSONObject pending) {
        LinearLayout view = PearlDesign.form(activity);
        note(view, name.get() + " · Ethereum 主网\nAave V3 / USDC · 区块 " + DeFiApi.quantity(p.block));
        TextView address = PearlDesign.note(activity, eth); address.setTextIsSelectable(true); view.addView(address);
        button(view, "复制 Ethereum 收款地址", () -> ((ClipboardManager)activity.getSystemService(Activity.CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("Ethereum 主网地址", eth)));
        note(view, "当前存款（含已计入利息）\n" + amount(p.supplied) + " USDC\n浮动存款年利率 APR · " + p.apr() + "\n可用 USDC · " + amount(p.usdc) + "\n手续费余额 · " + (hidden.getAsBoolean() ? "••••" : DeFiAmount.eth(p.wei)) + " ETH");
        note(view, "aUSDC 为存款凭证，余额包含累计利息；未单独统计投入本金和净收益。赎回受池内流动性限制。" + (!p.active() ? "\n当前储备暂停或未启用。" : "") + (p.debt.signum() > 0 ? "\n此地址存在借款，本版暂停赎回，请到 Aave 管理抵押和借款。" : ""));
        if (pending != null) button(view, "查询或重发待确认 DeFi 交易", () -> pending(selected, eth, pending));
        else {
            button(view, "存入 USDC", () -> editor(selected, eth, false));
            button(view, "赎回 USDC", () -> editor(selected, eth, true));
        }
        button(view, "刷新仓位", () -> { if (!busy) load(selected, eth); });
        button(view, "查看 Aave 协议", () -> activity.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://app.aave.com/"))));
        note(view, "存款可能按 Aave 规则启用为抵押品。每次授权、存款、赎回分别确认并支付 ETH 手续费；本版不提供借款或杠杆操作。PRL 和 WPRL 不在此存款列表中。\n普通 ETH / USDC 转账需在兼容钱包恢复同一助记词后管理。");
        display("DeFi 借贷流动性", view);
    }
    private void editor(String selected, String eth, boolean withdraw) {
        if (!wallet.get().backedUp()) { wallet.get().notice("先完成离线备份", "在设置中验证助记词备份后再进行 DeFi 交易。"); return; }
        LinearLayout view = PearlDesign.form(activity);
        note(view, "Ethereum 主网 · USDC\n" + (withdraw ? "USDC 将赎回到当前钱包的 Ethereum 地址。仅支持没有借款的仓位。" : "如授权不足，先确认本次金额的 USDC 授权；确认到账后，再单独预览并确认存款。"));
        EditText input = new EditText(activity); input.setHint("USDC 金额（最多 6 位小数）"); input.setSingleLine(true); input.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL); PearlDesign.input(input); view.addView(input);
        button(view, "核验金额并预览手续费", () -> {
            if (busy) return;
            try {
                BigInteger value = DeFiAmount.parse(input.getText().toString());
                busy = true; int token = generation; if (dialog != null) dialog.dismiss();
                worker.execute(() -> {
                    try {
                        if (new PendingEthereum(activity, selected, eth).load() != null) throw new IllegalArgumentException("已有交易等待确认");
                        DeFiApi.Position p = api.position(eth);
                        String op = withdraw ? "withdraw" : p.allowance.compareTo(value) < 0 ? "approve" : "supply";
                        JSONObject plan = api.prepare(eth, op, value);
                        ui(token, () -> review(selected, eth, plan));
                    } catch (Exception e) { ui(token, () -> error(e)); }
                    finally { busy = false; }
                });
            } catch (Exception e) { input.setError(e.getMessage()); }
        });
        display(withdraw ? "赎回 USDC" : "存入 USDC", view);
    }
    private void review(String selected, String eth, JSONObject plan) {
        try {
            String op = plan.getString("operation"), title = op.equals("approve") ? "授权 USDC" : op.equals("supply") ? "存入 Aave" : "赎回 USDC";
            BigInteger value = new BigInteger(plan.getString("amount")), max = new BigInteger(plan.getString("gas")).multiply(new BigInteger(plan.getString("maxFeePerGas")));
            String message = "付款钱包 · " + name.get() + "\n" + eth + "\n\nEthereum 主网 · chain ID 1\n" + title + " · " + DeFiAmount.usdc(value) + " USDC\n" +
                (op.equals("approve") ? "授权对象 · Aave V3 Pool\n" + DeFiApi.POOL + "\n本次只授权上述金额；授权完成后，需要另行确认存款。" : "协议合约\n" + DeFiApi.POOL + "\n" + (op.equals("supply") ? "存款凭证将归此钱包，协议可能启用为抵押品。" : "USDC 赎回到上述付款钱包地址。")) +
                "\n\n最高网络手续费 · " + DeFiAmount.eth(max) + " ETH\n实际费用由执行时 gas 消耗决定，失败也可能扣费。\n预览有效期 5 分钟；签名前需手机解锁和钱包密码。";
            if (dialog != null) dialog.dismiss();
            dialog = new AlertDialog.Builder(activity).setTitle("确认 " + title).setMessage(message).setNegativeButton("取消", null)
                .setPositiveButton("验证并签名", (d, w) -> {
                    if (!selected.equals(slot.get()) || observing.getAsBoolean()) return;
                    wallet.get().ethereumSign(plan, signed -> submit(selected, eth, signed));
                }).create(); dialog.show(); PearlDesign.dialog(dialog);
        } catch (Exception e) { error(e); }
    }
    private void submit(String selected, String eth, JSONObject signed) {
        if (busy) return; busy = true; int token = generation;
        worker.execute(() -> {
            try {
                JSONObject stored = new PendingEthereum(activity, selected, eth).load();
                if (stored == null || !stored.getString("hash").equals(signed.getString("hash"))) throw new IllegalArgumentException("待确认签名交易不匹配");
                int receipt = api.receipt(stored);
                if (receipt != 0) { finish(selected, eth, stored, receipt, token); return; }
                api.broadcast(stored);
                ui(token, () -> wallet.get().notice("DeFi 交易已提交", "等待链上确认。仓位是否变化以成功收据为准。\n\n交易哈希\n" + stored.optString("hash") + "\n\n可以重新打开 DeFi 查询；原签名交易已保留。"));
            } catch (Exception e) { ui(token, () -> wallet.get().notice("DeFi 结果待确认", "原签名交易已保留。网络超时可能发生在节点接收之后，请查询结果或重发同一交易。\n\n" + e.getMessage())); }
            finally { busy = false; }
        });
    }
    private void pending(String selected, String eth, JSONObject signed) {
        if (dialog != null) dialog.dismiss();
        dialog = new AlertDialog.Builder(activity).setTitle("DeFi 结果待确认").setMessage("Ethereum 主网\n" + signed.optString("hash") + "\n\n检查至少 3 个区块确认后更新状态。重发使用同一签名与 nonce。")
            .setNegativeButton("关闭", null).setNeutralButton("重发同一交易", (d,w) -> submit(selected, eth, signed))
            .setPositiveButton("查询结果", (d,w) -> {
                if (busy) return; busy = true; int token = generation;
                worker.execute(() -> {
                    try { int receipt = api.receipt(signed); if (receipt == 0) ui(token, () -> wallet.get().notice("DeFi 仍待确认", "暂未获得足够链上确认，请稍后重查。原交易继续保留。")); else finish(selected, eth, signed, receipt, token); }
                    catch (Exception e) { ui(token, () -> error(e)); }
                    finally { busy = false; }
                });
            }).create(); dialog.show(); PearlDesign.dialog(dialog);
    }
    private void finish(String selected, String eth, JSONObject signed, int receipt, int token) throws Exception {
        PendingEthereum p = new PendingEthereum(activity, selected, eth); JSONObject stored = p.load();
        if (stored == null || !stored.getString("hash").equals(signed.getString("hash"))) throw new IllegalArgumentException("待确认交易已变化");
        p.clear();
        ui(token, () -> wallet.get().notice(receipt > 0 ? "DeFi 交易已确认" : "DeFi 交易执行失败", signed.optString("hash") + (receipt > 0 ? "\n\n请刷新仓位。授权成功后仍需再次预览并确认存款。" : "\n\n链上执行回滚，网络手续费可能已扣除。请刷新后重新检查操作。")));
    }
}
