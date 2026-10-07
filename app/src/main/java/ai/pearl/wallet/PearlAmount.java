package ai.pearl.wallet;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;

/** Exact protocol values. One PRL = 100,000,000 grains. */
public final class PearlAmount {
    public static final BigInteger MAX_GRAINS = new BigInteger("210000000000000000");
    private PearlAmount() { }

    public static BigInteger parseGrains(String value) {
        if (value == null || !value.matches("-?[0-9]{1,18}")) {
            throw new IllegalArgumentException("链上金额格式错误");
        }
        BigInteger grains = new BigInteger(value);
        if (grains.abs().compareTo(MAX_GRAINS) > 0) {
            throw new IllegalArgumentException("链上金额超出 PRL 总量");
        }
        return grains;
    }

    public static BigInteger parsePositivePrl(String value) {
        if (value == null || !value.matches("(?:0|[1-9][0-9]*)(?:\\.[0-9]{1,8})?")) {
            throw new IllegalArgumentException("请输入最多 8 位小数的 PRL 金额");
        }
        BigInteger grains = new BigDecimal(value).movePointRight(8).toBigIntegerExact();
        if (grains.signum() <= 0 || grains.compareTo(MAX_GRAINS) > 0) {
            throw new IllegalArgumentException("金额必须大于 0，且不超过 PRL 总量");
        }
        return grains;
    }

    public static BigDecimal toPrl(BigInteger grains) {
        return new BigDecimal(grains, 8);
    }

    public static String format(BigInteger grains) {
        return toPrl(grains).stripTrailingZeros().toPlainString();
    }

    public static String fiat(BigInteger grains, BigDecimal price, String currency) {
        return (currency.equals("cny") ? "¥" : currency.equals("usdt") ? "" : "$")
                + toPrl(grains).multiply(price).setScale(2, RoundingMode.HALF_UP).toPlainString() + (currency.equals("usdt") ? " USDT" : "");
    }
}
