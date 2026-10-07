package ai.pearl.wallet;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.MultiFormatWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.EncodeHintType;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.SimpleDateFormat;
import java.util.Collections;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Native Android UI with an operation-scoped local signing wallet. */
public final class MainActivity extends Activity {
    private static final int INK = Color.rgb(20, 45, 37), GREEN = Color.rgb(12, 97, 85),
            MUTED = Color.rgb(111, 126, 115), BG = Color.rgb(246, 246, 240),
            PALE = Color.rgb(232, 239, 226), WHITE = Color.WHITE;
    private final ExecutorService executor = Executors.newFixedThreadPool(3);
    private final PearlApi api = new PearlApi();
    private MarketFeed marketFeed;
    private String priceState = "实时行情连接中";
    private boolean foreground, cachedPrice;
    private long cacheWrittenAt;
    private java.util.List<double[]> chartData;
    private int chartDataDays;
    private boolean chartUsesMarket;
    private String chartDataCurrency = "usd", chartShownCurrency = "";
    private BigDecimal chartShownRate;
    private TextView chartSource;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable poll = new Runnable() {
        @Override public void run() { refreshStatus(); if (!address.isEmpty()) refreshAccount(); handler.postDelayed(this, 90_000); }
    };
    private final Runnable priceClock = new Runnable() {
        @Override public void run() { if (foreground) { updateLabels(); handler.postDelayed(this, 1_000); } }
    };
    private final Runnable chartPoll = new Runnable() {
        @Override public void run() { if (foreground) { if (chart != null) refreshChart(); handler.postDelayed(this, 60_000); } }
    };
    private SharedPreferences preferences;
    private WalletFlow walletFlow;
    private LinearLayout root, content, navigation;
    private TextView networkText, balanceText, fiatText, priceText, changeText, priceStatus, balanceStatus;
    private PriceChartView chart;
    private String address = "", currency = "usd", page = "wallet";
    private PearlApi.Price price;
    private PearlApi.Account account;
    private PearlApi.Status status;
    private String priceError = "", accountError = "", networkError = "";
    private boolean loadingPrice, loadingAccount, loadingStatus;
    private long accountUpdatedAt;
    private int days = 1, chartGeneration, accountGeneration;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        preferences = getSharedPreferences("public_preferences", MODE_PRIVATE);
        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE);
        walletFlow = new WalletFlow(this, new WalletFlow.Host() {
            @Override public void changed() { loadWallet(); render(); refresh(); }
            @Override public String address() { return address; }
        }, api);
        loadWallet();
        readPriceCache();
        marketFeed = new MarketFeed(api, new MarketFeed.Listener() {
            @Override public void quote(PearlApi.Price incoming) { ui(() -> {
                if (!foreground) return;
                price = incoming; cachedPrice = false; priceError = ""; loadingPrice = false;
                writePriceCache(); updateLabels(); displayChart();
            }); }
            @Override public void state(String text) { ui(() -> { if (foreground) { priceState = text; updateLabels(); } }); }
        });
        currency = preferences.getString("currency", "usd");
        if (!currency.equals("usd") && !currency.equals("cny") && !currency.equals("usdt")) currency = "usd";
        root = column(); root.setBackgroundColor(BG);
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            int top, bottom;
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
                top = bars.top; bottom = bars.bottom;
            } else {
                top = insets.getSystemWindowInsetTop(); bottom = insets.getSystemWindowInsetBottom();
            }
            v.setPadding(0, top, 0, bottom); return insets;
        });
        if (android.os.Build.VERSION.SDK_INT >= 27) getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true);
        content = column(); content.setPadding(dp(24), dp(20), dp(24), dp(22)); scroll.addView(content);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        navigation = row(); navigation.setPadding(dp(12), dp(6), dp(12), dp(8)); navigation.setBackgroundColor(WHITE);
        root.addView(navigation); setContentView(root);
        render(); refresh();
    }

    private int dp(float n) { return Math.round(n * getResources().getDisplayMetrics().density); }
    private LinearLayout column() { LinearLayout l = new LinearLayout(this); l.setOrientation(LinearLayout.VERTICAL); return l; }
    private LinearLayout row() { LinearLayout l = new LinearLayout(this); l.setOrientation(LinearLayout.HORIZONTAL); l.setGravity(Gravity.CENTER_VERTICAL); return l; }
    private TextView text(String value, int size, int color, boolean bold) {
        TextView t = new TextView(this); t.setText(value); t.setTextSize(size); t.setTextColor(color);
        t.setFontFeatureSettings("tnum"); t.setIncludeFontPadding(false); t.setLineSpacing(dp(3), 1);
        if (bold) t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL)); return t;
    }
    private GradientDrawable background(int color, int border, int radius) {
        GradientDrawable d = new GradientDrawable(); d.setColor(color); d.setCornerRadius(dp(radius));
        if (border != 0) d.setStroke(dp(1), border); return d;
    }
    private void gap(LinearLayout target, int size) { View v = new View(this); target.addView(v, new LinearLayout.LayoutParams(1, dp(size))); }
    private Button button(String title, int color, int fill, View.OnClickListener action) {
        Button b = new Button(this); b.setText(title); b.setTextSize(14); b.setTextColor(color); b.setAllCaps(false);
        b.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        b.setMinHeight(dp(46)); b.setMinimumHeight(dp(46)); b.setPadding(dp(12), dp(8), dp(12), dp(8));
        b.setBackground(background(fill, fill == WHITE ? PALE : 0, 14)); b.setOnClickListener(action); return b;
    }
    private LinearLayout card(int color) {
        LinearLayout l = column(); l.setBackground(background(color, color == WHITE ? PALE : 0, 22));
        l.setPadding(dp(20), dp(20), dp(20), dp(20)); return l;
    }
    private void weighted(LinearLayout row, View v) { row.addView(v, new LinearLayout.LayoutParams(0, -2, 1)); }

    private void render() {
        content.removeAllViews(); priceText = null; changeText = null; priceStatus = null; balanceText = null;
        fiatText = null; balanceStatus = null; chart = null; chartSource = null; chartShownCurrency = ""; chartShownRate = null;
        LinearLayout header = row();
        ImageView logo = new ImageView(this); logo.setImageResource(R.drawable.ic_pearl);
        logo.setBackground(background(GREEN, 0, 12)); header.addView(logo, new LinearLayout.LayoutParams(dp(42), dp(42)));
        LinearLayout branding = column(); branding.setPadding(dp(11), 0, 0, 0);
        branding.addView(text("pearl", 25, INK, true)); branding.addView(text("你的 Pearl，随身而行", 11, MUTED, false)); weighted(header, branding);
        Button reload = button("↻", GREEN, PALE, v -> refresh()); reload.setContentDescription("刷新行情和链上数据");
        header.addView(reload, new LinearLayout.LayoutParams(dp(42), dp(42))); content.addView(header); gap(content, 22);
        LinearLayout info = row(); networkText = text("●  Pearl 主网", 12, GREEN, true); weighted(info, networkText);
        TextView mode = text(address.isEmpty() ? "手机独立钱包" : "本地签名 · 密钥已锁定", 10, MUTED, false); info.addView(mode); content.addView(info); gap(content, 18);
        switch (page) {
            case "market": renderMarket(); break;
            case "activity": renderActivity(); break;
            case "settings": renderSettings(); break;
            default: renderWallet();
        }
        renderNavigation(); updateLabels();
        if (chart != null) refreshChart();
    }

    private void renderWallet() {
        LinearLayout balance = card(GREEN);
        balance.addView(text("已确认余额", 12, Color.rgb(196, 224, 202), false)); gap(balance, 15);
        balanceText = text("—", 34, WHITE, true); balanceText.setAutoSizeTextTypeUniformWithConfiguration(18, 34, 1, android.util.TypedValue.COMPLEX_UNIT_SP);
        balanceText.setMaxLines(1); balance.addView(balanceText); gap(balance, 8);
        fiatText = text("创建钱包后查看资产", 13, Color.rgb(211, 232, 209), false); balance.addView(fiatText); gap(balance, 20);
        LinearLayout actions = row(); weighted(actions, button("↗  发送", GREEN, PALE, v -> walletFlow.send()));
        View spacer = new View(this); actions.addView(spacer, new LinearLayout.LayoutParams(dp(12), 1));
        weighted(actions, button("↙  接收", GREEN, WHITE, v -> receive())); balance.addView(actions); gap(balance, 12);
        balanceStatus = text(address.isEmpty() ? "创建或恢复后即可收款" : "密钥加密保存在本机", 10, Color.rgb(196, 224, 202), false);
        balance.addView(balanceStatus); content.addView(balance); gap(content, 16);
        if (address.isEmpty()) {
            LinearLayout setup = card(WHITE); setup.addView(text("开始使用 Pearl", 17, INK, true)); gap(setup, 7);
            setup.addView(text("私钥由手机保存，转账在本机签名。先创建新钱包，或恢复本应用的助记词。", 12, MUTED, false)); gap(setup, 14);
            setup.addView(button("创建手机钱包", WHITE, GREEN, v -> walletFlow.setup(false))); gap(setup, 10);
            setup.addView(button("恢复已有手机钱包", GREEN, PALE, v -> walletFlow.setup(true))); content.addView(setup); gap(content, 16);
        } else {
            LinearLayout a = row(); weighted(a, text(PearlAddress.shortLabel(address), 12, MUTED, false));
            Button change = button(walletFlow.backedUp() ? "离线备份 ✓" : "备份助记词", GREEN, BG, v -> walletFlow.backup()); a.addView(change); content.addView(a); gap(content, 12);
        }
        renderPriceCard(false); gap(content, 22);
        LinearLayout heading = row(); weighted(heading, text("最近交易", 18, INK, true));
        Button all = button("查看全部 →", GREEN, BG, v -> { page = "activity"; render(); }); heading.addView(all); content.addView(heading); gap(content, 12);
        renderTransactions(3);
    }

    private void renderPriceCard(boolean large) {
        LinearLayout market = card(WHITE); LinearLayout top = row();
        TextView icon = text("P", 20, GREEN, true); icon.setGravity(Gravity.CENTER); icon.setBackground(background(PALE, 0, 14));
        top.addView(icon, new LinearLayout.LayoutParams(dp(44), dp(44)));
        LinearLayout name = column(); name.setPadding(dp(12), 0, 0, 0); name.addView(text("Pearl", 16, INK, true)); name.addView(text("PRL · 主网原生币", 11, MUTED, false)); weighted(top, name);
        if (!large) top.addView(button("↗", GREEN, BG, v -> { page = "market"; render(); })); market.addView(top); gap(market, 18);
        LinearLayout numbers = row(); priceText = text("—", large ? 32 : 24, INK, true); weighted(numbers, priceText);
        changeText = text("—", 12, GREEN, true); numbers.addView(changeText); market.addView(numbers); gap(market, 6);
        priceStatus = text("实时行情连接中", 10, MUTED, false); market.addView(priceStatus);
        if (large) {
            gap(market, 15); LinearLayout times = row(); weighted(times, button("24 小时", days == 1 ? WHITE : GREEN, days == 1 ? GREEN : PALE, v -> { days = 1; render(); }));
            View spacer = new View(this); times.addView(spacer, new LinearLayout.LayoutParams(dp(10), 1));
            weighted(times, button("7 天", days == 7 ? WHITE : GREEN, days == 7 ? GREEN : PALE, v -> { days = 7; render(); })); market.addView(times);
            chart = new PriceChartView(this); market.addView(chart, new LinearLayout.LayoutParams(-1, dp(215)));
            chartSource = text("历史行情加载中", 10, MUTED, false); market.addView(chartSource); displayChart();
        }
        content.addView(market);
    }

    private void renderMarket() {
        content.addView(text("Pearl 行情", 26, INK, true)); gap(content, 8);
        content.addView(text("关注价格，也了解你的资产。", 12, MUTED, false)); gap(content, 22); renderPriceCard(true); gap(content, 18);
        LinearLayout about = card(PALE); about.addView(text("让计算创造价值", 16, GREEN, true)); gap(about, 8);
        about.addView(text("Pearl 是采用 Proof-of-Useful-Work 的独立 L1 网络。PRL 是其原生资产。", 12, INK, false)); gap(about, 14);
        about.addView(button("了解 Pearl →", GREEN, WHITE, v -> open("https://pearlresearch.ai"))); content.addView(about);
    }

    private void renderActivity() {
        content.addView(text("交易记录", 26, INK, true)); gap(content, 8);
        content.addView(text(address.isEmpty() ? "创建钱包后展示真实链上记录。" : PearlAddress.shortLabel(address) + " · 最近 10 笔", 12, MUTED, false)); gap(content, 22);
        renderTransactions(10);
        if (account != null && account.transactionCount > 10) {
            gap(content, 16); content.addView(button("在浏览器查看完整记录", GREEN, PALE,
                    v -> open("https://explorer.pearlresearch.ai/address/" + address + "?network=mainnet")));
        }
    }

    private void renderTransactions(int limit) {
        if (account == null || account.transactions.isEmpty()) {
            LinearLayout empty = card(WHITE); gap(empty, 8); TextView mark = text("↙  ↗", 25, GREEN, true); mark.setGravity(Gravity.CENTER); empty.addView(mark); gap(empty, 12);
            TextView title = text(account == null ? (loadingAccount ? "链上数据加载中" : "暂无交易数据") : "这个地址还没有交易", 14, INK, true); title.setGravity(Gravity.CENTER); empty.addView(title); gap(empty, 8);
            TextView detail = text(accountError.isEmpty() ? "收发记录将在这里显示" : accountError, 11, MUTED, false); detail.setGravity(Gravity.CENTER); empty.addView(detail); gap(empty, 8); content.addView(empty); return;
        }
        LinearLayout list = card(WHITE); int n = Math.min(limit, account.transactions.size());
        for (int i = 0; i < n; i++) {
            PearlApi.Transaction tx = account.transactions.get(i); boolean received = tx.netGrains.signum() >= 0;
            LinearLayout entry = row(); entry.setPadding(0, dp(10), 0, dp(10));
            TextView direction = text(received ? "↙" : "↗", 22, GREEN, false); direction.setGravity(Gravity.CENTER); direction.setBackground(background(PALE, 0, 12));
            entry.addView(direction, new LinearLayout.LayoutParams(dp(38), dp(38)));
            LinearLayout details = column(); details.setPadding(dp(12), 0, dp(5), 0);
            details.addView(text(received ? "接收 PRL" : "发送 PRL", 13, INK, true));
            details.addView(text(tx.confirmations == 0 ? "待确认" : tx.confirmations + " 次确认 · " + time(tx.time), 10, MUTED, false)); weighted(entry, details);
            LinearLayout amount = column(); amount.setGravity(Gravity.END);
            amount.addView(text((received ? "+" : "") + PearlAmount.format(tx.netGrains), 12, received ? GREEN : INK, true));
            amount.addView(text("PRL  ›", 10, MUTED, false)); entry.addView(amount);
            entry.setContentDescription((received ? "接收 " : "发送 ") + PearlAmount.format(tx.netGrains.abs()) + " PRL，查看交易详情");
            entry.setOnClickListener(v -> open("https://explorer.pearlresearch.ai/tx/" + tx.id + "?network=mainnet")); list.addView(entry);
            if (i < n - 1) { View divider = new View(this); divider.setBackgroundColor(PALE); list.addView(divider, new LinearLayout.LayoutParams(-1, dp(1))); }
        }
        content.addView(list);
    }

    private void renderSettings() {
        content.addView(text("设置", 26, INK, true)); gap(content, 22);
        LinearLayout settings = card(WHITE); settings.addView(text("计价货币", 14, INK, true)); gap(settings, 12);
        LinearLayout currencies = row(); weighted(currencies, button("USDT", currency.equals("usdt") ? WHITE : GREEN, currency.equals("usdt") ? GREEN : PALE, v -> changeCurrency("usdt")));
        weighted(currencies, button("USD 美元", currency.equals("usd") ? WHITE : GREEN, currency.equals("usd") ? GREEN : PALE, v -> changeCurrency("usd")));
        View spacer = new View(this); currencies.addView(spacer, new LinearLayout.LayoutParams(dp(10), 1));
        weighted(currencies, button("CNY 人民币", currency.equals("cny") ? WHITE : GREEN, currency.equals("cny") ? GREEN : PALE, v -> changeCurrency("cny"))); settings.addView(currencies); content.addView(settings); gap(content, 16);
        LinearLayout wallet = card(WHITE); wallet.addView(text("手机独立钱包", 14, INK, true)); gap(wallet, 8);
        wallet.addView(text(address.isEmpty() ? "在本机创建钱包，私钥加密保存。" : "每次签名、查看备份均需手机解锁验证和钱包密码。切到后台会关闭敏感页面。", 12, MUTED, false)); gap(wallet, 12);
        if (address.isEmpty()) {
            wallet.addView(button("创建手机钱包", WHITE, GREEN, v -> walletFlow.setup(false))); gap(wallet, 10);
            wallet.addView(button("恢复已有手机钱包", GREEN, PALE, v -> walletFlow.setup(true)));
        } else {
            wallet.addView(button(walletFlow.backedUp() ? "查看离线备份" : "完成助记词备份", GREEN, PALE, v -> walletFlow.backup())); gap(wallet, 10);
            wallet.addView(button("查询或重发待确认交易", GREEN, BG, v -> walletFlow.pendingStatus())); gap(wallet, 10);
            wallet.addView(text("备份适用于本应用。采用 Pearl 主网 Taproot 地址，固定收款与找零地址。此版本不支持 Oyster XMSS 钱包导入。", 11, MUTED, false));
        }
        content.addView(wallet); gap(content, 16);
        LinearLayout sources = card(PALE); sources.addView(text("数据来源", 14, GREEN, true)); gap(sources, 8);
        sources.addView(text("链上：Pearl 官方 Blockbook\n行情：BigONE PRL/USDT 实时推送\n汇率 / 备用参考：CoinGecko\n网络：Pearl Mainnet\n版本：0.3.1 手机钱包", 12, INK, false)); gap(sources, 12);
        sources.addView(button("查看项目源码说明", GREEN, WHITE, v -> open("https://github.com/pearl-research-labs/pearl"))); content.addView(sources);
    }

    private void changeCurrency(String value) { currency = value; preferences.edit().putString("currency", value).apply(); render(); }
    private void renderNavigation() {
        navigation.removeAllViews(); String[] pages = {"wallet", "market", "activity", "settings"};
        String[] titles = {"◉\n钱包", "⌁\n行情", "⇄\n记录", "⚙\n设置"};
        for (int i = 0; i < pages.length; i++) {
            String target = pages[i]; Button b = button(titles[i], page.equals(target) ? GREEN : MUTED, page.equals(target) ? PALE : WHITE,
                    v -> { if (!page.equals(target)) { page = target; render(); } }); b.setTextSize(11); b.setGravity(Gravity.CENTER); weighted(navigation, b);
        }
    }

    private void updateLabels() {
        if (networkText != null) {
            networkText.setText(status != null && networkError.isEmpty() ? (status.synced ? "●" : "○") + "  主网 · #" + status.height : "○  主网 · " + (loadingStatus ? "连接中" : "暂未连接"));
        }
        if (balanceText != null) {
            balanceText.setText(account == null ? "— PRL" : PearlAmount.format(account.balance) + " PRL");
            String value = account == null ? "创建钱包后查看余额" : "已确认余额";
            if (account != null && price != null && price.isFresh(System.currentTimeMillis() / 1000) && priceError.isEmpty()) value = "≈ " + PearlAmount.fiat(account.balance, price.value(price.displayCurrency(currency)), price.displayCurrency(currency));
            if (account != null && account.unconfirmed.signum() != 0) value += " · 未确认 " + PearlAmount.format(account.unconfirmed) + " PRL";
            fiatText.setText(value);
            balanceStatus.setText(address.isEmpty() ? "创建或恢复后即可收款" : !accountError.isEmpty() ? "数据刷新失败 · " + accountError
                    : account == null ? "本地钱包 · " + (loadingAccount ? "加载中" : "未获取数据") : "密钥已锁定 · 更新于 " + time(accountUpdatedAt));
        }
        if (priceText != null) {
            String actual = price == null ? currency : price.displayCurrency(currency);
            BigDecimal amount = price == null ? null : price.value(actual);
            priceText.setText(amount == null ? "—" : (actual.equals("cny") ? "¥" : actual.equals("usd") ? "$" : "") + amount.setScale(4, RoundingMode.HALF_UP).toPlainString() + (actual.equals("usdt") ? " USDT" : ""));
            boolean fresh = price != null && price.isFresh(System.currentTimeMillis() / 1000);
            changeText.setText(price == null ? "—" : (price.change24h.signum() >= 0 ? "+" : "") + price.change24h.setScale(2, RoundingMode.HALF_UP) + "%  24h");
            changeText.setTextColor(price != null && price.change24h.signum() < 0 ? Color.rgb(165, 75, 68) : GREEN);
            String status = price == null ? priceState : price.source + " · " + (cachedPrice ? "上次行情 · " : "")
                    + (!fresh ? "数据已过期 · " : "") + priceState + " · " + (price.usdt != null ? "接收 " : "更新 ") + priceTime(price.updatedAt);
            if (price != null && !actual.equals(currency)) status += "\n计价汇率暂不可用，显示 USDT 报价";
            priceStatus.setText(status); priceStatus.setTextColor(fresh && price != null && price.live && !cachedPrice ? GREEN : MUTED);
        }
    }
    private String priceTime(long seconds) { return new SimpleDateFormat("HH:mm:ss", Locale.CHINA).format(new Date(seconds * 1000)); }
    private void readPriceCache() {
        try {
            org.json.JSONObject p = new org.json.JSONObject(preferences.getString("cached_quote", ""));
            price = new PearlApi.Price(p.has("usd") ? new BigDecimal(p.getString("usd")) : null,
                    p.has("cny") ? new BigDecimal(p.getString("cny")) : null, p.has("usdt") ? new BigDecimal(p.getString("usdt")) : null,
                    new BigDecimal(p.getString("change")), p.getLong("at"), p.getString("source"), false);
            cachedPrice = true;
        } catch (Exception invalid) { price = null; }
    }
    private void writePriceCache() {
        if (price == null || System.currentTimeMillis() - cacheWrittenAt < 30_000) return;
        try {
            org.json.JSONObject p = new org.json.JSONObject().put("change", price.change24h.toPlainString()).put("at", price.updatedAt).put("source", price.source);
            if (price.usd != null) p.put("usd", price.usd.toPlainString()); if (price.cny != null) p.put("cny", price.cny.toPlainString()); if (price.usdt != null) p.put("usdt", price.usdt.toPlainString());
            preferences.edit().putString("cached_quote", p.toString()).apply(); cacheWrittenAt = System.currentTimeMillis();
        } catch (Exception ignored) { /* Cache only public market data; it does not affect wallet operations. */ }
    }

    private void refresh() {
        refreshPrice(); refreshStatus(); if (!address.isEmpty()) refreshAccount(); if (chart != null) refreshChart();
    }
    private void refreshPrice() { if (marketFeed != null) marketFeed.refresh(); }
    private void refreshStatus() {
        if (loadingStatus) return; loadingStatus = true;
        executor.execute(() -> {
            try { PearlApi.Status result = api.status(); ui(() -> { status = result; networkError = ""; loadingStatus = false; updateLabels(); }); }
            catch (Exception e) { ui(() -> { networkError = message(e); loadingStatus = false; updateLabels(); }); }
        });
    }
    private void refreshAccount() {
        if (loadingAccount) return; loadingAccount = true;
        String requestAddress = address; int generation = ++accountGeneration; updateLabels();
        executor.execute(() -> {
            try { PearlApi.Account result = api.account(requestAddress); ui(() -> {
                if (generation != accountGeneration || !requestAddress.equals(address)) return;
                account = result; accountError = ""; loadingAccount = false; accountUpdatedAt = System.currentTimeMillis() / 1000;
                if (page.equals("wallet") || page.equals("activity")) render(); else updateLabels();
            }); }
            catch (Exception e) { ui(() -> {
                if (generation != accountGeneration || !requestAddress.equals(address)) return;
                loadingAccount = false; accountError = message(e); if (page.equals("wallet") || page.equals("activity")) render(); else updateLabels();
            }); }
        });
    }
    private void refreshChart() {
        PriceChartView view = chart; int generation = ++chartGeneration, requestedDays = days; String requestedCurrency = currency;
        if (view == null) return;
        executor.execute(() -> {
            java.util.List<double[]> points; boolean primary = true; String dataCurrency = "usdt";
            try { points = api.marketChart(requestedDays); }
            catch (Exception marketUnavailable) {
                try { dataCurrency = requestedCurrency.equals("usdt") ? "usd" : requestedCurrency; points = api.chart(requestedDays, dataCurrency); primary = false; }
                catch (Exception e) { ui(() -> { if (generation == chartGeneration && chart == view) { view.setEmpty("历史行情暂不可用"); if (chartSource != null) chartSource.setText("历史行情加载失败，可点击刷新重试"); } }); return; }
            }
            java.util.List<double[]> result = points; boolean market = primary; String usedCurrency = dataCurrency;
            ui(() -> { if (generation == chartGeneration && chart == view) { chartData = result; chartDataDays = requestedDays; chartUsesMarket = market; chartDataCurrency = usedCurrency; chartShownCurrency = ""; displayChart(); } });
        });
    }
    private void displayChart() {
        if (chart == null || chartData == null || chartDataDays != days) return;
        String actual = chartDataCurrency; BigDecimal rate = BigDecimal.ONE;
        if (chartUsesMarket && price != null && price.usdt != null) {
            actual = price.displayCurrency(currency);
            if (!actual.equals("usdt")) rate = price.value(actual).divide(price.usdt, java.math.MathContext.DECIMAL64);
        }
        if (!actual.equals(chartShownCurrency) || chartShownRate == null || rate.compareTo(chartShownRate) != 0) {
            java.util.List<double[]> converted = new java.util.ArrayList<>();
            for (double[] point : chartData) converted.add(new double[]{point[0], point[1] * rate.doubleValue()});
            chart.setPoints(converted, actual); chartShownCurrency = actual; chartShownRate = rate;
        }
        if (chartSource != null) chartSource.setText(chartUsesMarket ? "BigONE · PRL/USDT · " + (days == 1 ? "5 分钟" : "1 小时") + "收盘价" + (!actual.equals("usdt") ? " · " + actual.toUpperCase(Locale.ROOT) + "折算" : "") : "CoinGecko · 参考历史行情");
        if (chartUsesMarket && price != null && price.usdt != null && price.isFresh(System.currentTimeMillis()/1000)) chart.livePrice(price.updatedAt * 1000, price.usdt.multiply(rate).doubleValue());
    }
    private void ui(Runnable action) { runOnUiThread(() -> { if (!isFinishing() && !isDestroyed()) action.run(); }); }
    private String message(Exception e) { return e instanceof IllegalArgumentException || e instanceof java.io.IOException ? e.getMessage() : "数据格式不兼容，请稍后重试"; }
    private String time(long seconds) { return seconds <= 0 ? "待确认" : new SimpleDateFormat("MM/dd HH:mm", Locale.CHINA).format(new Date(seconds * 1000)); }

    private void loadWallet() {
        try {
            String next = walletFlow.address();
            if (!next.equals(address)) { address = next; account = null; accountGeneration++; loadingAccount = false; }
        } catch (Exception e) { accountError = "钱包文件无法读取，请使用离线备份恢复"; }
    }

    private void receive() {
        if (address.isEmpty()) { walletFlow.notice("先创建钱包", "创建或恢复手机钱包后，会生成你的 Pearl 主网收款地址。"); return; }
        LinearLayout receive = column(); receive.setPadding(dp(24), dp(8), dp(24), dp(12)); receive.setGravity(Gravity.CENTER_HORIZONTAL);
        receive.addView(text("仅接收 Pearl 主网 PRL，请核对完整地址。", 12, MUTED, false)); gap(receive, 18);
        try {
            BitMatrix bits = new MultiFormatWriter().encode(address, BarcodeFormat.QR_CODE, 600, 600,
                    Collections.singletonMap(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M));
            Bitmap image = Bitmap.createBitmap(600, 600, Bitmap.Config.ARGB_8888);
            int[] pixels = new int[600 * 600];
            for (int y = 0; y < 600; y++) for (int x = 0; x < 600; x++) pixels[y * 600 + x] = bits.get(x, y) ? GREEN : WHITE;
            image.setPixels(pixels, 0, 600, 0, 0, 600, 600);
            ImageView qr = new ImageView(this); qr.setImageBitmap(image); qr.setContentDescription("Pearl 地址收款二维码"); receive.addView(qr, new LinearLayout.LayoutParams(dp(225), dp(225)));
        } catch (Exception e) { receive.addView(text("二维码生成失败，请复制完整地址", 12, MUTED, false)); }
        gap(receive, 14); TextView full = text(address, 13, INK, true); full.setTextIsSelectable(true); full.setGravity(Gravity.CENTER); receive.addView(full); gap(receive, 14);
        receive.addView(button("复制地址", WHITE, GREEN, v -> {
            ((ClipboardManager) getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("Pearl 地址", address));
            Toast.makeText(this, "已复制地址", Toast.LENGTH_SHORT).show();
        })); gap(receive, 8); receive.addView(button("分享地址", GREEN, PALE, v -> {
            Intent share = new Intent(Intent.ACTION_SEND); share.setType("text/plain"); share.putExtra(Intent.EXTRA_TEXT, address); startActivity(Intent.createChooser(share, "分享 Pearl 地址"));
        }));
        new AlertDialog.Builder(this).setTitle("Pearl 主网收款地址").setView(receive).setNegativeButton("关闭", null).show();
    }

    @Override protected void onActivityResult(int request, int result, Intent data) { super.onActivityResult(request, result, data); if (request == WalletFlow.AUTH) walletFlow.authResult(result); }
    private void open(String url) { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))); }
    @Override public void onResume() { super.onResume(); foreground = true; if (marketFeed != null) marketFeed.start(); handler.removeCallbacks(priceClock); handler.post(priceClock); handler.removeCallbacks(chartPoll); handler.postDelayed(chartPoll, 60_000); if (walletFlow != null) { walletFlow.resume(); loadWallet(); render(); } handler.removeCallbacks(poll); handler.postDelayed(poll, 90_000); updateLabels(); }
    @Override public void onPause() { foreground = false; if (marketFeed != null) marketFeed.stop(); handler.removeCallbacks(priceClock); handler.removeCallbacks(chartPoll); if (walletFlow != null) walletFlow.pause(); handler.removeCallbacks(poll); super.onPause(); }
    @Override public void onDestroy() { if (marketFeed != null) marketFeed.close(); handler.removeCallbacks(priceClock); handler.removeCallbacks(chartPoll); if (walletFlow != null) walletFlow.close(); handler.removeCallbacks(poll); accountGeneration++; chartGeneration++; executor.shutdownNow(); super.onDestroy(); }
}
