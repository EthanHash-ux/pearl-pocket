package ai.pearl.wallet;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Scenario arithmetic; a positive result is not an execution or a profit guarantee. */
final class ArbitrageMath {
  static BigDecimal number(String text, BigDecimal min, BigDecimal max) {
    if (text == null || !text.trim().matches("-?[0-9]{1,12}(\\.[0-9]{1,8})?"))
      throw new IllegalArgumentException("请填写完整的成本与情景假设，最多 8 位小数");
    BigDecimal v = new BigDecimal(text.trim());
    if (v.compareTo(min) < 0 || v.compareTo(max) > 0)
      throw new IllegalArgumentException("情景参数超出允许范围");
    return v;
  }

  static BigDecimal percent(String text) {
    return number(text, BigDecimal.ZERO, new BigDecimal("10")).movePointLeft(2);
  }

  static BigDecimal spotBuyQuantity(BigDecimal quantity, BigDecimal fee) {
    return quantity.divide(BigDecimal.ONE.subtract(fee), 8, RoundingMode.CEILING);
  }

  static BigDecimal spotNet(BigDecimal proceeds, BigDecimal fee) {
    return proceeds.multiply(BigDecimal.ONE.subtract(fee));
  }

  static BigDecimal bridgeNet(
      BigDecimal inputCost,
      BigDecimal proceeds,
      BigDecimal slip,
      BigDecimal costs,
      boolean sellDex) {
    return sellDex
        ? proceeds.multiply(BigDecimal.ONE.subtract(slip)).subtract(inputCost).subtract(costs)
        : proceeds.subtract(inputCost.multiply(BigDecimal.ONE.add(slip))).subtract(costs);
  }

  static final class Hedge {
    final BigDecimal pnl, funding, margin, entryBasis;

    Hedge(BigDecimal p, BigDecimal f, BigDecimal m, BigDecimal b) {
      pnl = p;
      funding = f;
      margin = m;
      entryBasis = b;
    }
  }

  static Hedge hedge(
      BigDecimal quantity,
      BigDecimal spotEntryCost,
      BigDecimal shortEntry,
      BigDecimal index,
      BigDecimal exitSpot,
      BigDecimal exitBasis,
      BigDecimal futureFee,
      BigDecimal spotFee,
      BigDecimal fundingHourly,
      int hours,
      BigDecimal costs,
      BigDecimal reserve,
      int marginBps,
      BigDecimal usdcUsdt) {
    if (hours < 0 || hours > 720) throw new IllegalArgumentException("持仓小时数应为 0–720");
    if (usdcUsdt.signum() <= 0) throw new IllegalArgumentException("USDC/USDT 汇率必须为正");
    BigDecimal futureExit = exitSpot.divide(usdcUsdt, 18, RoundingMode.HALF_EVEN).add(exitBasis);
    if (futureExit.signum() <= 0) throw new IllegalArgumentException("假设平仓合约价格必须为正");
    // Lighter is USDC-collateralised; convert both perp PnL and funding with
    // the explicitly entered USDC/USDT rate instead of silently equating them.
    BigDecimal funding =
        quantity.multiply(index).multiply(fundingHourly).multiply(BigDecimal.valueOf(hours));
    BigDecimal fee = shortEntry.add(quantity.multiply(futureExit)).multiply(futureFee);
    BigDecimal perp =
        shortEntry
            .subtract(quantity.multiply(futureExit))
            .add(funding)
            .subtract(fee)
            .multiply(usdcUsdt);
    BigDecimal pnl =
        spotNet(quantity.multiply(exitSpot), spotFee)
            .subtract(spotEntryCost)
            .add(perp)
            .subtract(costs)
            .subtract(reserve);
    BigDecimal margin =
        quantity.multiply(index).multiply(BigDecimal.valueOf(marginBps)).movePointLeft(4);
    BigDecimal entryBasis = shortEntry.multiply(usdcUsdt).subtract(spotEntryCost);
    return new Hedge(pnl, funding, margin, entryBasis);
  }
}
