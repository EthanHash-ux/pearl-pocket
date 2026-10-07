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
    private int INK,GREEN,MUTED,BG,PALE,WHITE;
    private boolean darkMode;
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
        @Override public void run() { refreshStatus(); if (!viewAddress().isEmpty()) refreshAccount(); handler.postDelayed(this, 90_000); }
    };
    private final Runnable priceClock = new Runnable() {
        @Override public void run() { if (foreground) { updateLabels();updateMining(); handler.postDelayed(this, 1_000); } }
    };
    private final Runnable chartPoll = new Runnable() {
        @Override public void run() { if (foreground) { if (chart != null) refreshChart(); handler.postDelayed(this, 60_000); } }
    };
    private SharedPreferences preferences;
    private WalletFlow walletFlow;
    private PublicTools publicTools;
    private MiningTools miningTools;private WatchTools watchTools;private HistoryTools historyTools;private String observedAddress="";
    private MiningApi.Stats miningStats;
    private TextView miningText;
    private boolean loadingMining;
    private String miningError="";
    private AlertDialog receiveDialog;
    private boolean hideBalances;
    private String transactionFilter="all", lastRenderedPage="";
    private final java.util.Map<String,Integer> scrollPositions=new java.util.HashMap<>();
    private ScrollView scroll;
    private TextView[] miningValues;
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
        darkMode=getSharedPreferences("public_preferences",MODE_PRIVATE).getBoolean("dark_mode",false);setTheme(darkMode?R.style.Theme_Pearl_Dark:R.style.Theme_Pearl);PearlDesign.dark(darkMode);INK=PearlDesign.INK;GREEN=PearlDesign.TEAL;MUTED=PearlDesign.MUTED;BG=PearlDesign.BG;PALE=PearlDesign.PALE;WHITE=PearlDesign.WHITE;
        super.onCreate(state);
        preferences = getSharedPreferences("public_preferences", MODE_PRIVATE);
        publicTools=new PublicTools(this,()->price);miningTools=new MiningTools(this,()->hideBalances);
        historyTools=new HistoryTools(this,api,()->hideBalances);watchTools=new WatchTools(this,this::selectAddress,()->observedAddress);
        String savedWatch=preferences.getString("observed_address","");try{for(WatchBook.Entry e:new WatchBook(PublicStore.of(this)).list())if(e.address.equals(savedWatch))observedAddress=e.address;}catch(Exception ignored){observedAddress="";}
        hideBalances=preferences.getBoolean("hide_balances",false);
        if(state==null){if(getIntent().getBooleanExtra("show_market",false))page="market";if(getIntent().getBooleanExtra("show_receipts",false))page="activity";}else{page=state.getString("page","wallet");days=state.getInt("days",1);transactionFilter=state.getString("transaction_filter","all");}
        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE);
        walletFlow = new WalletFlow(this, new WalletFlow.Host() {
            @Override public void changed() { loadWallet(); render(); refresh(); }
            @Override public String address() { return address; }
        }, api);
        loadWallet();if(state==null)selectReceiptAddress(getIntent());
        readPriceCache();
        marketFeed = new MarketFeed(api, new MarketFeed.Listener() {
            @Override public void quote(PearlApi.Price incoming) { ui(() -> {
                if (!foreground) return;
                price = incoming; cachedPrice = false; priceError = ""; loadingPrice = false;
                writePriceCache();PriceWidget.cache(getApplicationContext(),incoming.usdt,incoming.change24h,incoming.updatedAt,false); updateLabels(); displayChart();
                executor.execute(()->{try{AlertNotifications.check(getApplicationContext(),incoming.usdt,incoming.updatedAt);}catch(Exception ignored){/* Public alert settings cannot interrupt the wallet or market feed. */}});
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
                darkMode?0:View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        scroll = new ScrollView(this); scroll.setFillViewport(true);scroll.setVerticalScrollBarEnabled(false);scroll.setFocusableInTouchMode(true);scroll.setDescendantFocusability(android.view.ViewGroup.FOCUS_BEFORE_DESCENDANTS);scroll.requestFocus();
        content = column(); content.setPadding(dp(22), dp(20), dp(22), dp(24)); scroll.addView(content);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        navigation = row(); navigation.setPadding(dp(10), dp(8), dp(10), dp(6)); navigation.setBackgroundColor(WHITE);navigation.setElevation(dp(8));
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
    private Button button(String title,int color,int fill,View.OnClickListener action){return PearlDesign.button(this,title,color,fill,action);}
    private View iconButton(String icon,String description,int color,int fill,Runnable action){
        ImageView image=new ImageView(this);image.setImageDrawable(PearlDesign.icon(this,icon,color,22));image.setContentDescription(description);image.setPadding(dp(11),dp(11),dp(11),dp(11));image.setBackground(PearlDesign.touch(this,fill,0,14));image.setOnClickListener(v->action.run());return image;
    }
    private Button shortcut(String title,String icon,Runnable action){
        Button b=button(title,INK,WHITE,v->action.run());b.setCompoundDrawables(null,PearlDesign.icon(this,icon,GREEN,24),null,null);b.setCompoundDrawablePadding(dp(9));b.setPadding(dp(8),dp(15),dp(8),dp(12));return b;
    }
    private void between(LinearLayout row,int width){row.addView(new View(this),new LinearLayout.LayoutParams(dp(width),1));}
    private void heading(String title,String description){content.addView(text(title,27,INK,true));gap(content,8);if(!description.isEmpty()){content.addView(text(description,13,MUTED,false));gap(content,18);}}
    private void chooseCurrency(){new AlertDialog.Builder(this).setTitle("计价货币").setSingleChoiceItems(new String[]{"USDT","USD 美元","CNY 人民币"},currency.equals("usdt")?0:currency.equals("usd")?1:2,(d,w)->{d.dismiss();changeCurrency(new String[]{"usdt","usd","cny"}[w]);}).setNegativeButton("取消",null).show();}
    private void toggleBalances(){hideBalances=!hideBalances;preferences.edit().putBoolean("hide_balances",hideBalances).apply();render();}
    private void segments(LinearLayout parent,String[] labels,String[] values,String selected,java.util.function.Consumer<String> change){
        LinearLayout group=row();group.setPadding(dp(4),dp(4),dp(4),dp(4));group.setBackground(background(PearlDesign.LINE,0,14));
        for(int i=0;i<labels.length;i++){String value=values[i];Button b=button(labels[i],selected.equals(value)?INK:MUTED,selected.equals(value)?WHITE:PearlDesign.LINE,v->change.accept(value));b.setMinHeight(dp(38));b.setMinimumHeight(dp(38));b.setPadding(dp(5),dp(6),dp(5),dp(6));b.setTextSize(13);weighted(group,b);}parent.addView(group);
    }

    private LinearLayout card(int color) {
        LinearLayout l = column(); l.setBackground(background(color, color == WHITE ? PearlDesign.LINE : 0, 22));
        l.setPadding(dp(20), dp(20), dp(20), dp(20)); return l;
    }
    private void weighted(LinearLayout row, View v) { row.addView(v, new LinearLayout.LayoutParams(0, -2, 1)); }

    private void render() {
        if(!lastRenderedPage.isEmpty())scrollPositions.put(lastRenderedPage,scroll.getScrollY());
        lastRenderedPage=page;
        scroll.requestFocus();
        content.removeAllViews(); priceText = null; changeText = null; priceStatus = null; balanceText = null;
        fiatText = null; balanceStatus = null; chart = null; chartSource = null; chartShownCurrency = ""; chartShownRate = null;
        miningText=null;miningValues=null;
        if(page.equals("wallet")){
        LinearLayout header=row();
        ImageView logo=new ImageView(this);logo.setImageResource(R.drawable.ic_pearl_mark);logo.setPadding(dp(8),dp(8),dp(8),dp(8));logo.setBackground(background(Color.rgb(22,43,55),0,14));header.addView(logo,new LinearLayout.LayoutParams(dp(44),dp(44)));
        LinearLayout branding=column();branding.setPadding(dp(12),0,0,0);branding.addView(text("掌珠钱包",21,INK,true));TextView wordmark=text("PEARL POCKET",11,MUTED,true);wordmark.setLetterSpacing(.13f);gap(branding,4);branding.addView(wordmark);weighted(header,branding);
        header.addView(iconButton("refresh","刷新行情和链上数据",INK,WHITE,this::refresh),new LinearLayout.LayoutParams(dp(44),dp(44)));content.addView(header);gap(content,18);
        LinearLayout info=row();networkText=text("Pearl 主网 · 连接中",11,GREEN,true);weighted(info,networkText);TextView mode=text(observing()?"只读观察":address.isEmpty()?"手机独立钱包":"密钥已锁定",11,MUTED,false);mode.append(" ▾");mode.setContentDescription("切换钱包或观察地址");mode.setPadding(dp(8),dp(8),0,dp(8));mode.setOnClickListener(v->watchTools.show());info.addView(mode);content.addView(info);gap(content,20);
        }else networkText=null;
        switch (page) {
            case "market": renderMarket(); break;
            case "activity": renderActivity(); break;
            case "settings": renderSettings(); break;
            case "mining": renderMining(); break;
            default: renderWallet();
        }
        renderNavigation(); updateLabels();
        if (chart != null) refreshChart();
        int position=scrollPositions.getOrDefault(page,0);scroll.post(()->scroll.scrollTo(0,position));
    }

    private String viewAddress(){return observing()?observedAddress:address;}
    private boolean observing(){return !observedAddress.isEmpty();}
    private void selectAddress(String selected){try{if(!selected.isEmpty()){boolean known=false;for(WatchBook.Entry e:new WatchBook(PublicStore.of(this)).list())if(e.address.equals(selected))known=true;if(!known)throw new IllegalArgumentException("观察地址不存在");}observedAddress=selected;preferences.edit().putString("observed_address",selected).apply();account=null;accountError="";accountGeneration++;loadingAccount=false;page="wallet";scrollPositions.clear();render();refresh();}catch(Exception e){walletFlow.notice("无法切换地址",e.getMessage());}}
    private void sendFromWallet(){if(observing()){walletFlow.notice("只读观察地址","该地址的私钥不在观察模式中。切换到手机钱包后才能发送。");return;}walletFlow.send();}
    private void renderWallet(){
        LinearLayout balance=card(INK);balance.setBackground(PearlDesign.assetSurface(this));balance.setPadding(dp(23),dp(21),dp(23),dp(21));
        LinearLayout top=row();weighted(top,text("已确认余额",13,Color.rgb(192,216,221),false));top.addView(iconButton(hideBalances?"eye-off":"eye",hideBalances?"显示余额":"隐藏余额",PearlDesign.MINT,Color.TRANSPARENT,this::toggleBalances),new LinearLayout.LayoutParams(dp(44),dp(44)));balance.addView(top);gap(balance,8);
        balanceText=text("— PRL",37,Color.WHITE,true);balanceText.setMaxLines(1);balanceText.setAutoSizeTextTypeUniformWithConfiguration(22,37,1,android.util.TypedValue.COMPLEX_UNIT_SP);balance.addView(balanceText);gap(balance,10);
        fiatText=text("创建钱包后查看资产",15,Color.rgb(206,225,227),false);balance.addView(fiatText);gap(balance,22);
        LinearLayout footer=row();TextView secure=text(observing()?"只读观察":"本机签名",11,PearlDesign.MINT,true);secure.setCompoundDrawables(PearlDesign.icon(this,"shield",PearlDesign.MINT,15),null,null,null);secure.setCompoundDrawablePadding(dp(5));weighted(footer,secure);
        Button denomination=button(currency.toUpperCase(Locale.ROOT)+" ▾",Color.WHITE,Color.argb(22,255,255,255),v->chooseCurrency());denomination.setMinHeight(dp(32));denomination.setMinimumHeight(dp(32));denomination.setPadding(dp(10),dp(3),dp(10),dp(3));denomination.setTextSize(11);denomination.setContentDescription("切换计价货币");footer.addView(denomination);balance.addView(footer);gap(balance,13);
        balanceStatus=text("密钥加密保存在本机",11,Color.rgb(185,206,211),false);balance.addView(balanceStatus);content.addView(balance);gap(content,14);
        LinearLayout actions=row();weighted(actions,shortcut("发送","send",this::sendFromWallet));between(actions,10);weighted(actions,shortcut("接收","receive",this::receive));between(actions,10);weighted(actions,shortcut("地址簿","contacts",()->publicTools.addressBook(null)));content.addView(actions);gap(content,20);
        if(viewAddress().isEmpty()){
            LinearLayout setup=card(WHITE);setup.addView(text("开启你的 Pearl 钱包",18,INK,true));gap(setup,8);setup.addView(text("创建新钱包，或用助记词找回你的资产。",13,MUTED,false));gap(setup,16);
            setup.addView(button("创建手机钱包",WHITE,GREEN,v->walletFlow.setup(false)));gap(setup,8);setup.addView(button("恢复已有手机钱包",GREEN,PALE,v->walletFlow.setup(true)));content.addView(setup);gap(content,18);
        }else{
            LinearLayout wallet=card(WHITE);wallet.setPadding(dp(16),dp(16),dp(16),dp(16));LinearLayout addressRow=row();LinearLayout detail=column();detail.addView(text(observing()?"观察地址 · 只读":walletFlow.backedUp()?"钱包地址 · 已备份":"钱包地址 · 待备份",11,MUTED,false));gap(detail,6);TextView compact=text(PearlAddress.shortLabel(viewAddress()),13,INK,true);compact.setMaxLines(1);detail.addView(compact);weighted(addressRow,detail);addressRow.addView(iconButton("copy","复制钱包地址",GREEN,PALE,()->copy("Pearl 地址",viewAddress())),new LinearLayout.LayoutParams(dp(44),dp(44)));between(addressRow,6);addressRow.addView(iconButton("shield",observing()?"管理观察地址":walletFlow.backedUp()?"查看离线备份":"完成助记词备份",GREEN,BG,()->{if(observing())watchTools.show();else walletFlow.backup();}),new LinearLayout.LayoutParams(dp(44),dp(44)));wallet.addView(addressRow);content.addView(wallet);gap(content,18);
        }
        renderPriceCard(false);gap(content,22);LinearLayout heading=row();weighted(heading,text("最近交易",19,INK,true));Button all=button("查看全部",GREEN,BG,v->{page="activity";render();});all.setTextSize(12);heading.addView(all);content.addView(heading);gap(content,10);renderTransactions(3);
    }

    private void renderPriceCard(boolean large){
        LinearLayout market=card(WHITE);LinearLayout top=row();ImageView token=new ImageView(this);token.setImageResource(R.drawable.ic_pearl_mark);token.setPadding(dp(10),dp(10),dp(10),dp(10));token.setBackground(background(Color.rgb(0,112,99),0,14));top.addView(token,new LinearLayout.LayoutParams(dp(44),dp(44)));
        LinearLayout name=column();name.setPadding(dp(12),0,0,0);name.addView(text("Pearl",17,INK,true));gap(name,4);name.addView(text("PRL · 主网原生币",11,MUTED,false));weighted(top,name);
        if(!large)top.addView(iconButton("chevron","查看 Pearl 行情",MUTED,BG,()->{page="market";render();}),new LinearLayout.LayoutParams(dp(40),dp(40)));else top.addView(text("24h",11,MUTED,false));market.addView(top);gap(market,20);
        LinearLayout numbers=row();priceText=text("—",large?34:26,INK,true);priceText.setMaxLines(1);priceText.setAutoSizeTextTypeUniformWithConfiguration(19,large?34:26,1,android.util.TypedValue.COMPLEX_UNIT_SP);weighted(numbers,priceText);changeText=text("—",12,GREEN,true);changeText.setPadding(dp(8),dp(6),dp(8),dp(6));changeText.setBackground(background(PALE,0,8));numbers.addView(changeText);market.addView(numbers);gap(market,10);
        priceStatus=text("实时行情连接中",11,MUTED,false);market.addView(priceStatus);
        if(large){gap(market,20);segments(market,new String[]{"24 小时","7 天","30 天"},new String[]{"1","7","30"},String.valueOf(days),value->{days=Integer.parseInt(value);render();});
            chart=new PriceChartView(this);market.addView(chart,new LinearLayout.LayoutParams(-1,dp(235)));chartSource=text("历史行情加载中",11,MUTED,false);market.addView(chartSource);displayChart();}
        content.addView(market);
    }

    private void renderMarket(){
        heading("市场行情","实时价格、历史走势与目标价提醒");renderPriceCard(true);gap(content,16);
        LinearLayout alert=card(WHITE);LinearLayout line=row();ImageView icon=new ImageView(this);icon.setImageDrawable(PearlDesign.icon(this,"bell",GREEN,25));icon.setPadding(dp(10),dp(10),dp(10),dp(10));icon.setBackground(background(PALE,0,14));line.addView(icon,new LinearLayout.LayoutParams(dp(46),dp(46)));
        LinearLayout words=column();words.setPadding(dp(12),0,0,0);words.addView(text("价格提醒",16,INK,true));gap(words,4);words.addView(text("到达目标价，通知你",12,MUTED,false));weighted(line,words);line.addView(iconButton("chevron","管理价格提醒",MUTED,WHITE,()->publicTools.priceAlerts()),new LinearLayout.LayoutParams(dp(40),dp(40)));line.setOnClickListener(v->publicTools.priceAlerts());alert.addView(line);content.addView(alert);gap(content,16);
        LinearLayout about=card(PALE);about.addView(text("让计算创造价值",17,GREEN,true));gap(about,8);about.addView(text("Pearl 是采用有用工作证明的独立 L1 网络。PRL 是它的原生资产。",13,INK,false));gap(about,12);about.addView(button("了解 Pearl",GREEN,WHITE,v->open("https://pearlresearch.ai")));content.addView(about);
    }

    private void renderActivity(){
        heading("交易记录",viewAddress().isEmpty()?"选择钱包或观察地址后查看记录。":PearlAddress.shortLabel(viewAddress())+" · 最近 10 笔");
        content.addView(button("完整交易历史 / 搜索 / 导出",GREEN,PALE,v->historyTools.show(viewAddress())));gap(content,14);
        segments(content,new String[]{"全部","接收","发送"},new String[]{"all","received","sent"},transactionFilter,value->{transactionFilter=value;render();});gap(content,18);renderTransactions(10);
        if(account!=null&&account.transactionCount>10){gap(content,16);content.addView(button("在浏览器查看完整记录",GREEN,PALE,v->open("https://explorer.pearlresearch.ai/address/"+viewAddress()+"?network=mainnet")));}
    }

    private void renderTransactions(int limit) {
        java.util.List<PearlApi.Transaction> visible=new java.util.ArrayList<>();
        if(account!=null)for(PearlApi.Transaction tx:account.transactions)if(!page.equals("activity")||transactionFilter.equals("all")||(transactionFilter.equals("received")?tx.netGrains.signum()>=0:tx.netGrains.signum()<0))visible.add(tx);
        if (account == null || visible.isEmpty()) {
            LinearLayout empty=card(WHITE);empty.setGravity(Gravity.CENTER_HORIZONTAL);gap(empty,12);ImageView mark=new ImageView(this);mark.setImageDrawable(PearlDesign.icon(this,"activity",MUTED,27));mark.setPadding(dp(12),dp(12),dp(12),dp(12));mark.setBackground(background(BG,0,18));empty.addView(mark,new LinearLayout.LayoutParams(dp(52),dp(52)));gap(empty,15);
            TextView title=text(account==null?(loadingAccount?"链上数据加载中":"暂无交易数据"):account.transactions.isEmpty()?"还没有交易":"没有符合筛选的交易",15,INK,true);title.setGravity(Gravity.CENTER);empty.addView(title);gap(empty,8);
            TextView detail=text(accountError.isEmpty()?"你的 PRL 收发记录会显示在这里":accountError,12,MUTED,false);detail.setGravity(Gravity.CENTER);empty.addView(detail);gap(empty,12);content.addView(empty);return;
        }
        LinearLayout list = card(WHITE); int n = Math.min(limit, visible.size());
        for (int i = 0; i < n; i++) {
            PearlApi.Transaction tx = visible.get(i); boolean received = tx.netGrains.signum() >= 0;
            LinearLayout entry = row(); entry.setPadding(0, dp(10), 0, dp(10));
            ImageView direction=new ImageView(this);direction.setImageDrawable(PearlDesign.icon(this,received?"receive":"send",received?GREEN:INK,20));direction.setPadding(dp(10),dp(10),dp(10),dp(10));direction.setBackground(background(received?PALE:BG,0,13));
            entry.addView(direction, new LinearLayout.LayoutParams(dp(42), dp(42)));
            LinearLayout details = column(); details.setPadding(dp(12), 0, dp(5), 0);
            details.addView(text(received ? "接收 PRL" : "发送 PRL", 13, INK, true));
            details.addView(text(tx.confirmations == 0 ? "待确认" : tx.confirmations + " 次确认 · " + time(tx.time), 10, MUTED, false)); weighted(entry, details);
            LinearLayout amount = column(); amount.setGravity(Gravity.END);
            amount.addView(text(hideBalances?"••••":(received ? "+" : "") + PearlAmount.format(tx.netGrains), 14, received ? GREEN : INK, true));
            amount.addView(text("PRL  ›", 10, MUTED, false)); entry.addView(amount);
            entry.setContentDescription((received?"接收 ":"发送 ")+(hideBalances?"金额已隐藏":PearlAmount.format(tx.netGrains.abs())+" PRL")+"，查看交易详情");
            entry.setOnClickListener(v -> transactionDetail(tx)); list.addView(entry);
            if (i < n - 1) { View divider = new View(this); divider.setBackgroundColor(PALE); list.addView(divider, new LinearLayout.LayoutParams(-1, dp(1))); }
        }
        content.addView(list);
    }

    private void renderSettings(){
        heading("偏好与安全","计价、隐私与钱包管理");
        LinearLayout settings=card(WHITE);settings.addView(text("计价货币",15,INK,true));gap(settings,14);segments(settings,new String[]{"USDT","USD 美元","CNY 人民币"},new String[]{"usdt","usd","cny"},currency,this::changeCurrency);content.addView(settings);gap(content,16);content.addView(button(darkMode?"切换为浅色模式":"切换为深色模式",GREEN,PALE,v->{preferences.edit().putBoolean("dark_mode",!darkMode).apply();recreate();}));gap(content,16);
        LinearLayout privacy=card(WHITE);LinearLayout line=row();LinearLayout words=column();words.addView(text("隐藏资产金额",15,INK,true));gap(words,6);words.addView(text("钱包与记录页隐藏余额和交易金额",12,MUTED,false));weighted(line,words);android.widget.Switch toggle=new android.widget.Switch(this);toggle.setContentDescription("隐藏资产金额");toggle.setChecked(hideBalances);toggle.setThumbTintList(android.content.res.ColorStateList.valueOf(WHITE));toggle.setTrackTintList(new android.content.res.ColorStateList(new int[][]{new int[]{android.R.attr.state_checked},new int[]{}},new int[]{GREEN,Color.rgb(142,162,174)}));toggle.setOnCheckedChangeListener((b,checked)->{hideBalances=checked;preferences.edit().putBoolean("hide_balances",checked).apply();});line.addView(toggle);privacy.addView(line);gap(privacy,10);privacy.addView(text("转账确认仍显示实际金额，便于准确核对。",11,MUTED,false));content.addView(privacy);gap(content,16);
        LinearLayout wallet=card(WHITE);wallet.addView(text("手机钱包安全",16,INK,true));gap(wallet,8);wallet.addView(text(address.isEmpty()?"在本机创建钱包，私钥加密保存。":"查看备份与每次签名，都需要手机验证和钱包密码。",13,MUTED,false));gap(wallet,15);
        if(address.isEmpty()){wallet.addView(button("创建手机钱包",WHITE,GREEN,v->walletFlow.setup(false)));gap(wallet,8);wallet.addView(button("恢复已有手机钱包",GREEN,PALE,v->walletFlow.setup(true)));}
        else{wallet.addView(button(walletFlow.backedUp()?"查看离线备份":"完成助记词备份",GREEN,PALE,v->walletFlow.backup()));gap(wallet,8);wallet.addView(button("查询或重发待确认交易",INK,BG,v->walletFlow.pendingStatus()));gap(wallet,8);wallet.addView(button("钱包恢复兼容性",MUTED,WHITE,v->walletFlow.notice("钱包恢复兼容性","支持本应用 BIP39 英文 12 / 15 / 18 / 21 / 24 词，BIP39 附加口令为空。使用 Pearl 主网 Taproot 地址，固定收款与找零地址。尚不支持 Oyster XMSS 全钱包恢复。")));}
        content.addView(wallet);gap(content,16);LinearLayout tools=card(WHITE);tools.addView(text("日常工具",16,INK,true));gap(tools,12);tools.addView(button("桌面价格小组件",GREEN,BG,v->PriceWidget.pin(this)));gap(tools,8);tools.addView(button(preferences.getBoolean("receipt_notifications",false)?"关闭收款通知":"启用收款通知",GREEN,BG,v->receiptSettings()));gap(tools,8);tools.addView(button("观察地址管理",GREEN,BG,v->watchTools.show()));gap(tools,8);tools.addView(button("完整交易历史",GREEN,BG,v->historyTools.show(viewAddress())));gap(tools,8);tools.addView(button("地址簿",GREEN,BG,v->publicTools.addressBook(null)));gap(tools,8);tools.addView(button("价格提醒",GREEN,BG,v->publicTools.priceAlerts()));content.addView(tools);gap(content,16);
        LinearLayout sources=card(WHITE);sources.addView(text("关于掌珠钱包",16,INK,true));gap(sources,8);sources.addView(text("Pearl Pocket · 0.6.0\nPearl Mainnet · 手机独立钱包",13,MUTED,false));gap(sources,14);sources.addView(button("数据来源",INK,BG,v->walletFlow.notice("数据来源","链上：Pearl 官方 Blockbook\n行情：BigONE PRL/USDT 实时推送\n汇率 / 备用参考：CoinGecko\n矿池：HeroMiners 公开统计")));gap(sources,8);sources.addView(button("查看掌珠钱包源码",GREEN,WHITE,v->open("https://github.com/EthanHash-ux/pearl-pocket")));content.addView(sources);
    }

    private void transactionDetail(PearlApi.Transaction tx){
        String movement=hideBalances?"金额已隐藏":(tx.netGrains.signum()>=0?"+":"")+PearlAmount.format(tx.netGrains)+" PRL";
        AlertDialog d=new AlertDialog.Builder(this).setTitle("交易详情").setMessage("本地址余额变化\n"+movement+"\n转出时包含手续费与找零的净变化。\n\n状态："+(tx.confirmations==0?"待确认":tx.confirmations+" 次确认")+"\n时间："+time(tx.time)+"\n\n交易 ID\n"+tx.id)
                .setNegativeButton("关闭",null).setNeutralButton("复制交易 ID",(x,w)->copy("Pearl 交易 ID",tx.id)).setPositiveButton("区块浏览器",(x,w)->open("https://explorer.pearlresearch.ai/tx/"+tx.id+"?network=mainnet")).create();d.show();PearlDesign.dialog(d);
    }
    private void renderMining(){
        heading("矿业工作台","矿池动态与收益情景，一目了然。");content.addView(button("个人矿工监控",GREEN,PALE,v->miningTools.list()));gap(content,14);
        LinearLayout pool=card(WHITE);LinearLayout title=row();weighted(title,text("HeroMiners",18,INK,true));TextView coin=text("PEARL · PRL",11,GREEN,true);coin.setPadding(dp(9),dp(5),dp(9),dp(5));coin.setBackground(background(PALE,0,8));title.addView(coin);pool.addView(title);gap(pool,18);
        miningValues=new TextView[6];String[] labels={"矿池总算力","全网算力","占全网比例","矿工 / 工作器","矿池费率","主网高度"};
        for(int r=0;r<3;r++){LinearLayout metrics=row();for(int c=0;c<2;c++){int i=r*2+c;LinearLayout cell=column();cell.setPadding(dp(12),dp(12),dp(10),dp(12));cell.setBackground(background(BG,0,12));cell.addView(text(labels[i],11,MUTED,false));gap(cell,9);miningValues[i]=text("—",18,INK,true);miningValues[i].setMaxLines(1);miningValues[i].setAutoSizeTextTypeUniformWithConfiguration(13,18,1,android.util.TypedValue.COMPLEX_UNIT_SP);cell.addView(miningValues[i]);weighted(metrics,cell);if(c==0)between(metrics,10);}pool.addView(metrics);gap(pool,10);}
        miningText=text("公开矿池数据加载中",11,MUTED,false);pool.addView(miningText);gap(pool,14);LinearLayout buttons=row();weighted(buttons,button("刷新矿池统计",GREEN,PALE,v->refreshMining()));between(buttons,10);weighted(buttons,button("打开矿池网站",INK,BG,v->open("https://pearl.herominers.com")));pool.addView(buttons);content.addView(pool);gap(content,16);
        LinearLayout calc=card(PALE);LinearLayout caption=row();ImageView symbol=new ImageView(this);symbol.setImageDrawable(PearlDesign.icon(this,"calculator",GREEN,24));caption.addView(symbol,new LinearLayout.LayoutParams(dp(30),dp(30)));TextView name=text("收益情景计算",18,GREEN,true);name.setPadding(dp(8),0,0,0);caption.addView(name);calc.addView(caption);gap(calc,12);calc.addView(text("把电费、矿池费和租金算进去，查看每天与 30 / 90 天的净收益。",13,INK,false));gap(calc,16);calc.addView(button("挖矿收益计算",WHITE,GREEN,v->publicTools.profit(price!=null&&price.isFresh(System.currentTimeMillis()/1000)?price.cny:null)));content.addView(calc);gap(content,14);
        content.addView(text("只读矿池统计，手机不参与挖矿。收益基于你填写的估计，不预测未来产出。",11,MUTED,false));updateMining();refreshMining();
    }

    private void refreshMining(){
        if(loadingMining)return;loadingMining=true;updateMining();
        executor.execute(()->{try{MiningApi.Stats result=new MiningApi().stats();ui(()->{miningStats=result;miningError="";loadingMining=false;updateMining();});}
            catch(Exception e){ui(()->{miningError=message(e);loadingMining=false;updateMining();});}});
    }
    private void updateMining(){
        if(miningText==null)return;
        if(miningStats==null){miningText.setText(loadingMining?"公开矿池数据加载中":miningError.isEmpty()?"尚未获取数据":miningError);return;}
        MiningApi.Stats s=miningStats;boolean old=System.currentTimeMillis()/1000-s.receivedAt>180;
        String[] values={MiningApi.rate(s.hashRate),MiningApi.rate(s.networkHashRate),s.share().setScale(2,RoundingMode.HALF_UP).toPlainString()+"%",s.miners+" / "+s.workers,s.fee.toPlainString()+"%","#"+s.height};
        if(miningValues!=null)for(int i=0;i<values.length;i++)miningValues[i].setText(values[i]);
        String state="接收 "+priceTime(s.receivedAt)+(old?" · 数据已过期":"")+(loadingMining?" · 刷新中":"")+(!miningError.isEmpty()?"\n刷新失败："+miningError:"")+"\n统计包含普通与 Solo 模式。";miningText.setText(state);
    }

    private void changeCurrency(String value) { currency = value; preferences.edit().putString("currency", value).apply(); render(); }
    private void renderNavigation(){
        navigation.removeAllViews();String[] pages={"wallet","market","mining","activity","settings"};String[] titles={"钱包","行情","挖矿","记录","设置"};
        for(int i=0;i<pages.length;i++){String target=pages[i];boolean selected=page.equals(target);LinearLayout item=column();item.setGravity(Gravity.CENTER);item.setPadding(0,dp(5),0,dp(5));item.setBackground(PearlDesign.touch(this,selected?PALE:WHITE,0,14));item.setOnClickListener(v->{if(!page.equals(target)){page=target;render();}});
            ImageView icon=new ImageView(this);icon.setImageDrawable(PearlDesign.icon(this,target,selected?GREEN:MUTED,22));item.addView(icon,new LinearLayout.LayoutParams(dp(24),dp(24)));gap(item,5);TextView label=text(titles[i],11,selected?GREEN:MUTED,selected);label.setGravity(Gravity.CENTER);item.addView(label);weighted(navigation,item);}
    }

    private void updateLabels() {
        if (networkText != null) {
            networkText.setText(status != null && networkError.isEmpty() ? (status.synced ? "●" : "○") + "  主网 · #" + status.height : "○  主网 · " + (loadingStatus ? "连接中" : "暂未连接"));
        }
        if (balanceText != null) {
            balanceText.setText(hideBalances?"•••• PRL":account==null?"— PRL":PearlAmount.format(account.balance)+" PRL");
            balanceText.setContentDescription(hideBalances?"已确认余额已隐藏":"已确认余额 "+(account==null?"尚未获取":PearlAmount.format(account.balance)+" PRL"));
            String value = account == null ? "创建钱包后查看余额" : "已确认余额";
            if (account != null && price != null && price.isFresh(System.currentTimeMillis() / 1000) && priceError.isEmpty()) value = "≈ " + PearlAmount.fiat(account.balance, price.value(price.displayCurrency(currency)), price.displayCurrency(currency));
            if (account != null && account.unconfirmed.signum() != 0) value += " · 未确认 " + PearlAmount.format(account.unconfirmed) + " PRL";
            fiatText.setText(hideBalances?"资产金额已隐藏":value);
            balanceStatus.setText(viewAddress().isEmpty() ? "创建或恢复后即可收款" : !accountError.isEmpty() ? "数据刷新失败 · " + accountError
                    : account == null ? (observing()?"观察地址 · ":"本地钱包 · ") + (loadingAccount ? "加载中" : "未获取数据") : (observing()?"只读地址":"密钥已锁定")+" · 更新于 " + time(accountUpdatedAt));
        }
        if (priceText != null) {
            String actual = price == null ? currency : price.displayCurrency(currency);
            BigDecimal amount = price == null ? null : price.value(actual);
            priceText.setText(amount == null ? "—" : (actual.equals("cny") ? "¥" : actual.equals("usd") ? "$" : "") + amount.setScale(4, RoundingMode.HALF_UP).toPlainString() + (actual.equals("usdt") ? " USDT" : ""));
            boolean fresh = price != null && price.isFresh(System.currentTimeMillis() / 1000);
            changeText.setText(price == null ? "—" : (price.change24h.signum() >= 0 ? "+" : "") + price.change24h.setScale(2, RoundingMode.HALF_UP) + "%  24h");
            changeText.setTextColor(price!=null&&price.change24h.signum()<0?PearlDesign.RED:GREEN);
            changeText.setBackground(background(price!=null&&price.change24h.signum()<0?Color.rgb(252,235,236):PALE,0,8));
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
        refreshPrice(); refreshStatus(); if (!viewAddress().isEmpty()) refreshAccount(); if (chart != null) refreshChart();
        if(page.equals("mining"))refreshMining();
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
        String requestAddress = viewAddress(); int generation = ++accountGeneration; updateLabels();
        executor.execute(() -> {
            try { PearlApi.Account result = api.account(requestAddress); ui(() -> {
                if (generation != accountGeneration || !requestAddress.equals(viewAddress())) return;
                executor.execute(()->{try{ReceiptNotifications.check(getApplicationContext(),requestAddress,result);}catch(Exception ignored){}});account = result; accountError = ""; loadingAccount = false; accountUpdatedAt = System.currentTimeMillis() / 1000;
                if (page.equals("wallet") || page.equals("activity")) render(); else updateLabels();
            }); }
            catch (Exception e) { ui(() -> {
                if (generation != accountGeneration || !requestAddress.equals(viewAddress())) return;
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
        if (chartSource != null) chartSource.setText(chartUsesMarket ? "BigONE · PRL/USDT · " + (days == 1 ? "5 分钟" : days==7?"1 小时":"1 天") + "收盘价" + (!actual.equals("usdt") ? " · " + actual.toUpperCase(Locale.ROOT) + "折算" : "") : "CoinGecko · 参考历史行情");
        if (chartUsesMarket && price != null && price.usdt != null && price.isFresh(System.currentTimeMillis()/1000)) chart.livePrice(price.updatedAt * 1000, price.usdt.multiply(rate).doubleValue());
    }
    private void ui(Runnable action) { runOnUiThread(() -> { if (!isFinishing() && !isDestroyed()) action.run(); }); }
    private String message(Exception e) { return e instanceof IllegalArgumentException || e instanceof java.io.IOException ? e.getMessage() : "数据格式不兼容，请稍后重试"; }
    private String time(long seconds) { return seconds <= 0 ? "待确认" : new SimpleDateFormat("MM/dd HH:mm", Locale.CHINA).format(new Date(seconds * 1000)); }

    private void loadWallet() {
        try {
            String next = walletFlow.address();
            if (!next.equals(address)) { address = next;preferences.edit().putString("receipt_signer",address).apply();ReceiptNotifications.schedule(this);account = null; accountGeneration++; loadingAccount = false; }
        } catch (Exception e) { accountError = "钱包文件无法读取，请使用离线备份恢复"; }
    }

    private void receive() {
        receive("");
    }
    private void receive(String requestedAmount) {
        if (viewAddress().isEmpty()) { walletFlow.notice("先创建钱包", "创建或恢复手机钱包后，会生成你的 Pearl 主网收款地址。"); return; }
        String receiveAddress=viewAddress();String payload=requestedAmount.isEmpty()?receiveAddress:PaymentRequest.encode(receiveAddress,requestedAmount);
        LinearLayout receive = column(); receive.setPadding(dp(24), dp(8), dp(24), dp(12)); receive.setGravity(Gravity.CENTER_HORIZONTAL);
        receive.addView(text(observing()?"只读观察地址：此手机不控制该地址。请核对收款方，资金需由对应钱包管理。":"仅接收 Pearl 主网 PRL，请核对完整地址。",12,MUTED,false)); gap(receive, 18);
        if(!requestedAmount.isEmpty())receive.addView(text("请求收款 "+requestedAmount+" PRL\n带金额二维码适用于 Pearl Pocket。其他钱包可复制地址手动填写金额。",12,GREEN,true));
        try {
            BitMatrix bits = new MultiFormatWriter().encode(payload, BarcodeFormat.QR_CODE, 600, 600,
                    Collections.singletonMap(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M));
            Bitmap image = Bitmap.createBitmap(600, 600, Bitmap.Config.ARGB_8888);
            int[] pixels = new int[600 * 600];
            for (int y = 0; y < 600; y++) for (int x = 0; x < 600; x++) pixels[y * 600 + x] = bits.get(x, y) ? Color.BLACK : Color.WHITE;
            image.setPixels(pixels, 0, 600, 0, 0, 600, 600);
            ImageView qr = new ImageView(this); qr.setImageBitmap(image); qr.setContentDescription("Pearl 地址收款二维码"); receive.addView(qr, new LinearLayout.LayoutParams(dp(225), dp(225)));
        } catch (Exception e) { receive.addView(text("二维码生成失败，请复制完整地址", 12, MUTED, false)); }
        gap(receive, 14); TextView full = text(receiveAddress, 13, INK, true); full.setTextIsSelectable(true); full.setGravity(Gravity.CENTER); receive.addView(full); gap(receive, 14);
        receive.addView(button("复制地址", WHITE, GREEN, v -> {
            ((ClipboardManager) getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("Pearl 地址",receiveAddress));
            Toast.makeText(this, "已复制地址", Toast.LENGTH_SHORT).show();
        })); gap(receive, 8); receive.addView(button("分享地址", GREEN, PALE, v -> {
            Intent share = new Intent(Intent.ACTION_SEND); share.setType("text/plain"); share.putExtra(Intent.EXTRA_TEXT,receiveAddress); startActivity(Intent.createChooser(share, "分享 Pearl 地址"));
        }));
        gap(receive,8);receive.addView(button("指定收款金额",GREEN,PALE,v->requestAmount()));
        gap(receive,8);receive.addView(button("分享收款二维码图片",GREEN,PALE,v->{try{startActivity(PublicShareProvider.intent(this,QrImage.png(payload,receiveAddress,requestedAmount),"png","分享 Pearl 收款二维码"));}catch(Exception e){walletFlow.notice("无法分享二维码",e.getMessage());}}));
        if(!requestedAmount.isEmpty()){gap(receive,8);receive.addView(button("复制收款请求",GREEN,WHITE,v->copy("Pearl Pocket 收款请求",payload)));}
        ScrollView scroll=new ScrollView(this);scroll.addView(receive);
        if(receiveDialog!=null)receiveDialog.dismiss();
        AlertDialog d=new AlertDialog.Builder(this).setTitle("Pearl 主网收款地址").setView(scroll).setNegativeButton("关闭", null).create();receiveDialog=d;d.show();PearlDesign.dialog(d);
    }
    private void requestAmount(){
        if(receiveDialog!=null)receiveDialog.dismiss();
        EditText amount=new EditText(this);amount.setHint("请求金额（PRL）");amount.setInputType(InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_FLAG_DECIMAL);
        AlertDialog d=new AlertDialog.Builder(this).setTitle("指定收款金额").setView(amount).setNegativeButton("取消",null).setPositiveButton("生成收款请求",null).create();receiveDialog=d;d.show();PearlDesign.dialog(d);
        d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{try{String n=PearlAmount.format(PearlAmount.parsePositivePrl(amount.getText().toString()));d.dismiss();receive(n);}catch(Exception e){amount.setError(e.getMessage());}});
    }
    private void copy(String title,String value){((ClipboardManager)getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText(title,value));Toast.makeText(this,"已复制",Toast.LENGTH_SHORT).show();}

    @Override protected void onActivityResult(int request, int result, Intent data) { super.onActivityResult(request, result, data); if (request == WalletFlow.AUTH) walletFlow.authResult(result); if(request==QrScannerActivity.REQUEST)walletFlow.scanResult(result,data); }
    private void selectReceiptAddress(Intent intent){String selected=intent.getStringExtra("receipt_address");if(selected==null)return;try{selected=PearlAddress.normalize(selected);if(selected.equals(address))observedAddress="";else for(WatchBook.Entry e:new WatchBook(PublicStore.of(this)).list())if(e.address.equals(selected))observedAddress=e.address;preferences.edit().putString("observed_address",observedAddress).apply();}catch(Exception ignored){}}
    private void receiptSettings(){if(preferences.getBoolean("receipt_notifications",false)){preferences.edit().putBoolean("receipt_notifications",false).apply();ReceiptNotifications.schedule(this);render();return;}
        AlertDialog d=new AlertDialog.Builder(this).setTitle("启用收款通知？").setMessage("检查手机钱包和已添加的观察地址。后台约每 15 分钟检查最新记录，系统可能延迟；短时间大量交易可能漏检，完整结果请查看历史。首次检查建立基线，不补发历史通知。地址会发送给 Pearl 官方 Blockbook，后台不读取私钥。待确认与首次链上确认分别提醒；确认数可能因链重组改变。").setNegativeButton("取消",null).setPositiveButton("启用",(x,w)->{preferences.edit().putBoolean("receipt_notifications",true).apply();if(android.os.Build.VERSION.SDK_INT>=33&&checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)!=android.content.pm.PackageManager.PERMISSION_GRANTED)requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS},4212);else ReceiptNotifications.schedule(this);render();}).create();d.show();PearlDesign.dialog(d);
    }
    @Override public void onRequestPermissionsResult(int request,String[] permissions,int[] results){super.onRequestPermissionsResult(request,permissions,results);if(request==4212){ReceiptNotifications.schedule(this);render();}if(request==PublicTools.NOTIFICATIONS){AlertNotifications.schedule(this);handler.post(()->{if(foreground)publicTools.priceAlerts();});}}
    @Override protected void onNewIntent(Intent intent){super.onNewIntent(intent);setIntent(intent);if(intent.getBooleanExtra("show_market",false)){page="market";render();}if(intent.getBooleanExtra("show_receipts",false)){selectReceiptAddress(intent);account=null;accountGeneration++;loadingAccount=false;page="activity";render();refreshAccount();}}
    private void open(String url) { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))); }
    @Override protected void onSaveInstanceState(Bundle state){state.putString("page",page);state.putInt("days",days);state.putString("transaction_filter",transactionFilter);super.onSaveInstanceState(state);}
    @Override public void onResume() { super.onResume(); foreground = true; AlertNotifications.schedule(this);ReceiptNotifications.schedule(this);if (marketFeed != null) marketFeed.start(); handler.removeCallbacks(priceClock); handler.post(priceClock); handler.removeCallbacks(chartPoll); handler.postDelayed(chartPoll, 60_000); if (walletFlow != null) { walletFlow.resume(); loadWallet(); render(); } handler.removeCallbacks(poll); handler.postDelayed(poll, 90_000); updateLabels(); }
    @Override public void onPause() { foreground = false; if(receiveDialog!=null){receiveDialog.dismiss();receiveDialog=null;}if(publicTools!=null)publicTools.pause();if(watchTools!=null)watchTools.pause();if(miningTools!=null)miningTools.pause();if(historyTools!=null)historyTools.pause();if (marketFeed != null) marketFeed.stop(); handler.removeCallbacks(priceClock); handler.removeCallbacks(chartPoll); if (walletFlow != null) walletFlow.pause(); handler.removeCallbacks(poll); super.onPause(); }
    @Override public void onDestroy() { if (marketFeed != null) marketFeed.close(); handler.removeCallbacks(priceClock); handler.removeCallbacks(chartPoll); if (walletFlow != null) walletFlow.close(); handler.removeCallbacks(poll); accountGeneration++; chartGeneration++;if(historyTools!=null)historyTools.close();if(miningTools!=null)miningTools.close();executor.shutdownNow(); super.onDestroy(); }
}
