package ai.pearl.wallet;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/** Decimal depth walking. Refuses to extrapolate beyond the returned book. */
final class TradeBook {
  static final class Level {
    final BigDecimal price, size;

    Level(BigDecimal p, BigDecimal s) {
      price = p;
      size = s;
    }
  }

  final List<Level> bids, asks;

  TradeBook(JSONArray bids, JSONArray asks, String size) throws Exception {
    this.bids = parse(bids, size, false);
    this.asks = parse(asks, size, true);
    if (this.bids.get(0).price.compareTo(this.asks.get(0).price) >= 0)
      throw new IllegalArgumentException("盘口交叉，请重新获取");
  }

  static BigDecimal decimal(JSONObject o, String key) throws Exception {
    String value = o.get(key).toString();
    if (!value.matches("[0-9]{1,14}(\\.[0-9]{1,18})?"))
      throw new IllegalArgumentException("盘口数值无效");
    BigDecimal n = new BigDecimal(value);
    if (n.signum() <= 0 || n.compareTo(new BigDecimal("1000000000000")) > 0)
      throw new IllegalArgumentException("盘口数值超出范围");
    return n;
  }

  private List<Level> parse(JSONArray a, String size, boolean ascending) throws Exception {
    if (a.length() < 1 || a.length() > 250) throw new IllegalArgumentException("盘口为空或过大");
    List<Level> result = new ArrayList<>();
    BigDecimal last = null;
    java.util.Set<String> ids = new java.util.HashSet<>();
    for (int i = 0; i < a.length(); i++) {
      JSONObject o = a.getJSONObject(i);
      BigDecimal p = decimal(o, "price"), q = decimal(o, size);
      if (last != null && (ascending ? p.compareTo(last) < 0 : p.compareTo(last) > 0))
        throw new IllegalArgumentException("盘口排序无效");
      if (o.has("order_id") && !ids.add(o.getString("order_id")))
        throw new IllegalArgumentException("盘口订单重复");
      result.add(new Level(p, q));
      last = p;
    }
    return java.util.Collections.unmodifiableList(result);
  }

  static final class Fill {
    final BigDecimal quote, average, slippagePercent;

    Fill(BigDecimal q, BigDecimal a, BigDecimal s) {
      quote = q;
      average = a;
      slippagePercent = s;
    }
  }

  Fill fill(BigDecimal quantity, boolean buy) {
    if (quantity.signum() <= 0) throw new IllegalArgumentException("数量必须为正");
    List<Level> side = buy ? asks : bids;
    BigDecimal left = quantity, quote = BigDecimal.ZERO;
    for (Level l : side) {
      BigDecimal take = left.min(l.size);
      quote = quote.add(take.multiply(l.price));
      left = left.subtract(take);
      if (left.signum() == 0) break;
    }
    if (left.signum() > 0) throw new IllegalArgumentException("已获取盘口深度不足以成交全部数量，请减小金额");
    BigDecimal avg = quote.divide(quantity, 18, RoundingMode.HALF_EVEN), top = side.get(0).price;
    BigDecimal slip =
        (buy ? avg.subtract(top) : top.subtract(avg))
            .divide(top, 18, RoundingMode.HALF_EVEN)
            .multiply(BigDecimal.valueOf(100));
    return new Fill(quote, avg, slip);
  }
}
