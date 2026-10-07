package ai.pearl.wallet;

import java.math.BigDecimal;
import org.json.JSONArray;
import org.json.JSONObject;

/** Pinned PRL perpetual; never selects another asset merely by its ticker. */
final class LighterMarket {
  static final String BASE = "https://mainnet.zklighter.elliot.ai/api/v1/";
  static final int ID = 4097;
  final TradeBook book;
  final BigDecimal mark, index, last, minSize, minQuote;
  final int sizeDecimals, initialMarginBps;

  LighterMarket(JSONObject meta, JSONObject depth) throws Exception {
    if (meta.getInt("code") != 200 || depth.getInt("code") != 200)
      throw new IllegalArgumentException("Lighter 行情暂不可用");
    JSONArray rows = meta.getJSONArray("order_book_details");
    JSONObject row = null;
    for (int i = 0; i < rows.length(); i++)
      if (rows.getJSONObject(i).getInt("market_id") == ID) {
        if (row != null) throw new IllegalArgumentException("Lighter 市场重复");
        row = rows.getJSONObject(i);
      }
    if (row == null
        || !"PRL".equals(row.getString("symbol"))
        || !"perp".equals(row.getString("market_type"))
        || !"active".equals(row.getString("status"))
        || BridgeApi.bool(row, "is_frozen")
        || row.getJSONObject("market_config").getBoolean("force_reduce_only"))
      throw new IllegalArgumentException("固定 PRL 永续市场不可用");
    mark = TradeBook.decimal(row, "mark_price");
    index = TradeBook.decimal(row, "index_price");
    last = TradeBook.decimal(row, "last_trade_price");
    minSize = TradeBook.decimal(row, "min_base_amount");
    minQuote = TradeBook.decimal(row, "min_quote_amount");
    sizeDecimals = BridgeApi.bounded(row, "supported_size_decimals", 0, 8);
    initialMarginBps = BridgeApi.bounded(row, "default_initial_margin_fraction", 1, 10000);
    book =
        new TradeBook(
            depth.getJSONArray("bids"), depth.getJSONArray("asks"), "remaining_base_amount");
  }

  static LighterMarket fetch(PearlApi.Transport get) throws Exception {
    return new LighterMarket(
        new JSONObject(get.get(BASE + "orderBookDetails?market_id=" + ID)),
        new JSONObject(get.get(BASE + "orderBookOrders?market_id=" + ID + "&limit=250")));
  }

  TradeBook.Fill shortFill(BigDecimal quantity) {
    if (quantity.stripTrailingZeros().scale() > sizeDecimals || quantity.compareTo(minSize) < 0)
      throw new IllegalArgumentException("Lighter 数量不符合最小数量或步长");
    TradeBook.Fill f = book.fill(quantity, false);
    if (f.quote.compareTo(minQuote) < 0) throw new IllegalArgumentException("Lighter 数量低于最小成交金额");
    return f;
  }

  static String settledFunding(PearlApi.Transport get, long now) throws Exception {
    JSONObject o =
        new JSONObject(
            get.get(
                BASE
                    + "fundings?market_id="
                    + ID
                    + "&resolution=1h&start_timestamp="
                    + (now - 7200)
                    + "&end_timestamp="
                    + now
                    + "&count_back=0"));
    if (o.getInt("code") != 200 || !"1h".equals(o.getString("resolution")))
      throw new IllegalArgumentException("资金费记录无效");
    JSONArray rows = o.getJSONArray("fundings");
    JSONObject latest = null;
    long time = 0;
    for (int i = 0; i < rows.length(); i++) {
      JSONObject r = rows.getJSONObject(i);
      long t = r.getLong("timestamp");
      if (t > time && t <= now + 60) {
        time = t;
        latest = r;
      }
    }
    if (latest == null || now - time > 7200) throw new IllegalArgumentException("最近已结算资金费不可用");
    String direction = latest.getString("direction");
    if (!direction.equals("long") && !direction.equals("short"))
      throw new IllegalArgumentException("资金费方向未知");
    BigDecimal rate = new BigDecimal(latest.getString("rate")),
        value = new BigDecimal(latest.getString("value"));
    if (rate.signum() < 0 || rate.compareTo(BigDecimal.ONE) > 0 || value.signum() < 0)
      throw new IllegalArgumentException("资金费记录超出范围");
    return "最近已结算小时 "
        + java.time.Instant.ofEpochSecond(time)
        + "\n"
        + (direction.equals("long") ? "多头付费" : "空头付费")
        + " · "
        + rate.toPlainString()
        + "%\n每 PRL 名义数量："
        + value.toPlainString()
        + " USDC。这是历史记录，不预测下次资金费。";
  }
}
