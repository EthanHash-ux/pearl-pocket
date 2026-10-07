package ai.pearl.wallet;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import javax.net.ssl.HttpsURLConnection;

/** Only public data and signed transaction bytes cross the network boundary. */
public final class PearlApi {
    public static final String BLOCKBOOK = "https://blockbook.pearlresearch.ai/api/v2/";
    public static final String COINGECKO = "https://api.coingecko.com/api/v3/";
    public static final String COIN_ID = "pearl-2";
    public static final String BIGONE = "https://api.big.one/api/v3/";
    public interface Transport { String get(String url) throws Exception; }
    public interface Submitter { String post(String url, String raw) throws Exception; }
    private final Transport transport;
    private final Submitter submitter;

    public PearlApi() { this(PearlApi::httpsGet); }
    public PearlApi(Transport transport) { this(transport, PearlApi::httpsPost); }
    public PearlApi(Transport transport, Submitter submitter) { this.transport = transport; this.submitter = submitter; }

    public JSONObject payment(String from, String to, BigInteger amount) throws Exception {
        from = PearlAddress.normalize(from); to = PearlAddress.normalize(to);
        if (!status().synced) throw new IllegalArgumentException("链上数据服务正在同步");
        JSONObject estimate = new JSONObject(transport.get(BLOCKBOOK + "estimatefee/6"));
        // Blockbook returns PRL per 1000 virtual bytes. Conversion stays exact.
        long rate = new BigDecimal(estimate.getString("result")).movePointRight(8)
                .setScale(0, java.math.RoundingMode.CEILING).longValueExact();
        rate = Math.max(1000, rate);
        if (rate > 1_000_000) throw new IllegalArgumentException("网络建议手续费过高，请稍后再试");
        JSONArray rows = new JSONArray(transport.get(BLOCKBOOK + "utxo/" + from + "?confirmed=true"));
        if (rows.length() > 200) throw new IllegalArgumentException("钱包输入过多，请使用官方 Oyster 合并后再试");
        JSONArray utxos = new JSONArray(); java.util.Map<String, JSONObject> previous = new java.util.HashMap<>();
        for (int i = 0; i < rows.length(); i++) {
            JSONObject u = rows.getJSONObject(i); String id = u.getString("txid");
            if (!id.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("交易 ID 无效");
            JSONObject tx = previous.get(id);
            if (tx == null) { tx = new JSONObject(transport.get(BLOCKBOOK + "tx/" + id)); previous.put(id, tx); }
            if (!id.equals(tx.getString("txid"))) throw new IllegalArgumentException("输入交易 ID 不匹配");
            utxos.put(new JSONObject().put("txid", id).put("vout", u.getInt("vout"))
                    .put("raw", tx.getString("hex")).put("confirmations", Math.min(u.getLong("confirmations"), tx.getLong("confirmations"))));
        }
        return new JSONObject().put("from", from).put("to", to).put("amount", amount.toString()).put("rate", rate).put("utxos", utxos);
    }

    public String broadcast(JSONObject signed) throws Exception {
        String raw = signed.getString("raw"), expected = signed.getString("txid");
        if (!raw.matches("[0-9a-f]+") || raw.length() > 100_000 || !expected.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("签名交易无效");
        String accepted = new JSONObject(submitter.post(BLOCKBOOK + "sendtx/", raw)).getString("result");
        if (!expected.equals(accepted)) throw new IOException("服务返回了不同的交易 ID，保留原交易等待查询");
        return accepted;
    }
    public boolean transactionKnown(String id) throws Exception {
        if (!id.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("交易 ID 无效");
        JSONObject tx = new JSONObject(transport.get(BLOCKBOOK + "tx/" + id));
        return id.equals(tx.getString("txid"));
    }

    public static final class Status {
        public final long height;
        public final boolean synced;
        Status(long height, boolean synced) { this.height = height; this.synced = synced; }
    }

    public static final class Price {
        public final BigDecimal usd, cny, usdt, change24h;
        public final long updatedAt;
        public final String source;
        public final boolean live;
        Price(BigDecimal usd, BigDecimal cny, BigDecimal change24h, long updatedAt) {
            this(usd,cny,null,change24h,updatedAt,"CoinGecko",false);
        }
        Price(BigDecimal usd,BigDecimal cny,BigDecimal usdt,BigDecimal change24h,long updatedAt,String source,boolean live) {
            this.usd=usd;this.cny=cny;this.usdt=usdt;this.change24h=change24h;this.updatedAt=updatedAt;this.source=source;this.live=live;
        }
        public BigDecimal value(String currency) { return currency.equals("cny") ? cny : currency.equals("usdt") ? usdt : usd; }
        public String displayCurrency(String requested) { return value(requested)!=null ? requested : usdt!=null ? "usdt" : requested; }
        public boolean isFresh(long nowSeconds) {
            long age = nowSeconds - updatedAt;
            return age >= -60 && age <= (usdt!=null ? 120 : 900);
        }
    }

    public static final class Transaction {
        public final String id;
        public final BigInteger netGrains;
        public final long time;
        public final int confirmations;
        public final String counterparties;
        Transaction(String id, BigInteger netGrains, long time, int confirmations) {
            this(id,netGrains,time,confirmations,"");
        }
        Transaction(String id, BigInteger netGrains, long time, int confirmations,String counterparties) {
            this.id = id; this.netGrains = netGrains; this.time = time; this.confirmations = confirmations;
            this.counterparties=counterparties;
        }
    }

    static final class HistoryPage {
        final Account account;final int page,pages;
        HistoryPage(Account a,int p,int n){account=a;page=p;pages=n;}
    }
    HistoryPage history(String input,int page,int size)throws Exception{
        String address=PearlAddress.normalize(input);
        if(page<1||page>100000||size<1||size>100)throw new IllegalArgumentException("历史分页参数无效");
        if(!status().synced)throw new IllegalArgumentException("链上服务正在同步");
        JSONObject json=new JSONObject(transport.get(BLOCKBOOK+"address/"+address+"?details=txs&page="+page+"&pageSize="+size));
        Account account=parseAccount(json,address);int actual=json.optInt("page",page),pages=json.optInt("totalPages",(int)Math.max(1,(account.transactionCount+size-1)/size));
        if(actual!=page||pages<1||pages>100000||account.transactions.size()>size)throw new IllegalArgumentException("历史分页响应不一致");
        return new HistoryPage(account,actual,pages);
    }

    public static final class Account {
        public final String address;
        public final BigInteger balance, unconfirmed;
        public final long transactionCount;
        public final List<Transaction> transactions;
        Account(String address, BigInteger balance, BigInteger unconfirmed, long count, List<Transaction> txs) {
            this.address = address; this.balance = balance; this.unconfirmed = unconfirmed;
            this.transactionCount = count; this.transactions = Collections.unmodifiableList(new ArrayList<>(txs));
        }
    }

    public Status status() throws Exception {
        return parseStatus(new JSONObject(transport.get(BLOCKBOOK)));
    }

    static Status parseStatus(JSONObject json) throws JSONException {
        JSONObject book = json.getJSONObject("blockbook"), backend = json.getJSONObject("backend");
        if (!"Pearl".equals(book.getString("coin")) || !"mainnet".equals(backend.getString("chain"))
                || book.getInt("decimals") != 8) {
            throw new IllegalArgumentException("数据服务不是 Pearl 主网");
        }
        long height = book.getLong("bestHeight");
        if (height <= 0) throw new IllegalArgumentException("链高度无效");
        return new Status(height, book.getBoolean("inSync") && !book.getBoolean("initialSync"));
    }

    public Price price() throws Exception {
        String url = COINGECKO + "simple/price?ids=" + COIN_ID
                + "&vs_currencies=usd,cny&include_24hr_change=true&include_last_updated_at=true";
        return parsePrice(new JSONObject(transport.get(url)));
    }

    MarketQuote marketQuote() throws Exception {
        return MarketQuote.rest(new JSONObject(transport.get(BIGONE+"asset_pairs/PRL-USDT/ticker")),System.currentTimeMillis()/1000);
    }
    MarketQuote.Fx exchangeRates() throws Exception {
        return MarketQuote.Fx.parse(new JSONObject(transport.get(COINGECKO+"simple/price?ids=tether&vs_currencies=usd,cny&include_last_updated_at=true")),System.currentTimeMillis()/1000);
    }
    List<double[]> marketChart(int days) throws Exception {
        if (days!=1 && days!=7 && days!=30) throw new IllegalArgumentException("行情参数无效");
        JSONObject json=new JSONObject(transport.get(BIGONE+"asset_pairs/PRL-USDT/candles?period="+(days==1?"MIN5":days==7?"HOUR1":"DAY1")+"&limit="+(days==1?288:days==7?168:30)));
        if(json.getInt("code")!=0)throw new IOException("交易所历史行情暂不可用");
        JSONArray rows=json.getJSONArray("data"); List<double[]> points=new ArrayList<>();
        for(int i=0;i<rows.length();i++) {
            JSONObject row=rows.getJSONObject(i); long time=java.time.Instant.parse(row.getString("time")).toEpochMilli(); double close=positiveDecimal(row,"close").doubleValue();
            if(time<=0 || !Double.isFinite(close))throw new IOException("历史行情数据无效");points.add(new double[]{time,close});
        }
        points.sort(java.util.Comparator.comparingDouble(p->p[0]));
        if(points.size()<2)throw new IOException("历史行情数据不足");
        for(int i=1;i<points.size();i++)if(points.get(i)[0]<=points.get(i-1)[0])throw new IOException("历史行情时间重复");
        return points;
    }

    static Price parsePrice(JSONObject json) throws JSONException {
        JSONObject coin = json.getJSONObject(COIN_ID);
        BigDecimal usd = positiveDecimal(coin, "usd"), cny = positiveDecimal(coin, "cny");
        BigDecimal change = decimal(coin, "usd_24h_change");
        long updated = coin.getLong("last_updated_at");
        if (updated <= 0) throw new IllegalArgumentException("行情更新时间无效");
        return new Price(usd, cny, change, updated);
    }

    public List<double[]> chart(int days, String currency) throws Exception {
        if (!(days == 1 || days == 7 || days == 30) || !(currency.equals("usd") || currency.equals("cny"))) {
            throw new IllegalArgumentException("行情参数无效");
        }
        JSONObject json = new JSONObject(transport.get(COINGECKO + "coins/" + COIN_ID
                + "/market_chart?vs_currency=" + currency + "&days=" + days));
        JSONArray prices = json.getJSONArray("prices");
        List<double[]> points = new ArrayList<>();
        long lastTime = 0;
        for (int i = 0; i < prices.length(); i++) {
            JSONArray p = prices.getJSONArray(i);
            long time = p.getLong(0); double price = p.getDouble(1);
            if (time <= lastTime || !Double.isFinite(price) || price <= 0) {
                throw new IllegalArgumentException("行情曲线数据无效");
            }
            lastTime = time; points.add(new double[]{time, price});
        }
        if (points.size() < 2) throw new IllegalArgumentException("行情曲线暂不可用");
        return points;
    }

    public Account account(String input) throws Exception {
        String address = PearlAddress.normalize(input);
        Status status = status();
        if (!status.synced) throw new IllegalArgumentException("链上数据服务正在同步，请稍后刷新");
        return parseAccount(new JSONObject(transport.get(BLOCKBOOK + "address/" + address
                + "?details=txs&page=1&pageSize=10")), address);
    }

    static Account parseAccount(JSONObject json, String address) throws JSONException {
        if (!address.equals(json.getString("address"))) throw new IllegalArgumentException("返回地址不匹配");
        BigInteger balance = PearlAmount.parseGrains(json.getString("balance"));
        if (balance.signum() < 0) throw new IllegalArgumentException("确认余额不能为负数");
        BigInteger unconfirmed = PearlAmount.parseGrains(json.getString("unconfirmedBalance"));
        JSONArray txs = json.optJSONArray("transactions");
        List<Transaction> transactions = new ArrayList<>();
        if (txs != null) for (int i = 0; i < txs.length(); i++) {
            JSONObject tx = txs.getJSONObject(i);
            String id = tx.getString("txid");
            if (!id.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("交易 ID 无效");
            BigInteger net = sumOwned(tx.getJSONArray("vout"), address)
                    .subtract(sumOwned(tx.getJSONArray("vin"), address));
            int confirmations=tx.getInt("confirmations");long at=tx.optLong("blockTime",0);if(confirmations<0||at<0)throw new IllegalArgumentException("交易状态无效");
            java.util.LinkedHashSet<String> others=new java.util.LinkedHashSet<>();
            for(String side:new String[]{"vin","vout"}){JSONArray rows=tx.getJSONArray(side);for(int n=0;n<rows.length();n++){JSONArray a=rows.getJSONObject(n).optJSONArray("addresses");if(a!=null)for(int k=0;k<a.length();k++){String value=a.getString(k);if(!value.equals(address)&&value.length()<=120)others.add(value);}}}
            transactions.add(new Transaction(id, net, at, confirmations,String.join(" ",others)));
        }
        long count = json.getLong("txs");
        if (count < 0) throw new IllegalArgumentException("交易数量无效");
        return new Account(address, balance, unconfirmed, count, transactions);
    }

    private static BigInteger sumOwned(JSONArray rows, String address) throws JSONException {
        BigInteger sum = BigInteger.ZERO;
        for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.getJSONObject(i);
            JSONArray addresses = row.optJSONArray("addresses");
            if (addresses == null) continue;
            for (int j = 0; j < addresses.length(); j++) if (address.equals(addresses.getString(j))) {
                BigInteger value = PearlAmount.parseGrains(row.getString("value"));
                if (value.signum() < 0) throw new IllegalArgumentException("交易输入输出金额不能为负数");
                sum = sum.add(value); break;
            }
        }
        return sum;
    }

    private static BigDecimal decimal(JSONObject json, String key) throws JSONException {
        Object value = json.get(key);
        if (!(value instanceof Number) && !(value instanceof String)) throw new IllegalArgumentException("行情数值无效");
        try { return new BigDecimal(value.toString()); }
        catch (NumberFormatException e) { throw new IllegalArgumentException("行情数值无效"); }
    }

    private static BigDecimal positiveDecimal(JSONObject json, String key) throws JSONException {
        BigDecimal value = decimal(json, key);
        if (value.signum() <= 0) throw new IllegalArgumentException("行情价格无效");
        return value;
    }

    static String httpsGet(String url) throws Exception {
        return httpsRequest(url, null);
    }
    static String httpsPost(String url, String raw) throws Exception {
        if (!(BLOCKBOOK + "sendtx/").equals(url)) throw new IllegalArgumentException("广播地址无效");
        return httpsRequest(url, raw);
    }
    // A separate read-only Ethereum RPC boundary. It cannot broadcast or sign.
    static String httpsRpc(String body) throws Exception {
        EvmPublicApi.validateRequest(new JSONObject(body));
        return httpsRequest(EvmPublicApi.RPC, body);
    }
    private static String httpsRequest(String url, String raw) throws Exception {
        URI uri = new URI(url);
        if (!"https".equals(uri.getScheme()) || uri.getUserInfo() != null || uri.getPort() != -1
                || !("blockbook.pearlresearch.ai".equals(uri.getHost()) || "api.coingecko.com".equals(uri.getHost()) || "api.big.one".equals(uri.getHost()) || "pearl.herominers.com".equals(uri.getHost())
                    || (raw == null && ("api.pearlbridge.xyz".equals(uri.getHost()) || "mainnet.zklighter.elliot.ai".equals(uri.getHost())))
                    || (EvmPublicApi.RPC.equals(url) && raw != null))) {
            throw new IllegalArgumentException("仅允许受支持的 HTTPS 数据服务");
        }
        HttpsURLConnection connection = (HttpsURLConnection) uri.toURL().openConnection();
        try {
            connection.setInstanceFollowRedirects(false);
            boolean market="api.big.one".equals(uri.getHost())||"api.coingecko.com".equals(uri.getHost());
            connection.setConnectTimeout(market?6_000:12_000); connection.setReadTimeout(market?8_000:20_000);
            connection.setUseCaches(false); connection.setRequestProperty("Cache-Control","no-cache");
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("User-Agent", "PearlPocketAndroid/0.7");
            if (raw != null) {
                byte[] data = raw.getBytes(StandardCharsets.US_ASCII);
                connection.setRequestMethod("POST"); connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", EvmPublicApi.RPC.equals(url) ? "application/json" : "text/plain"); connection.setFixedLengthStreamingMode(data.length);
                try (java.io.OutputStream out = connection.getOutputStream()) { out.write(data); }
            }
            int code = connection.getResponseCode();
            if (code == 429) throw new IOException("数据服务请求过于频繁，请稍后刷新");
            if (code != 200) throw new IOException("数据服务暂不可用（HTTP " + code + "）");
            String type = connection.getContentType();
            if (type == null || !type.toLowerCase(java.util.Locale.ROOT).contains("application/json")) {
                throw new IOException("数据服务返回了非 JSON 内容");
            }
            try (InputStream input = connection.getInputStream(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192]; int size;
                while ((size = input.read(buffer)) != -1) {
                    if (output.size() + size > 4_000_000) throw new IOException("数据响应过大");
                    output.write(buffer, 0, size);
                }
                return new String(output.toByteArray(), StandardCharsets.UTF_8);
            }
        } finally { connection.disconnect(); }
    }
}
