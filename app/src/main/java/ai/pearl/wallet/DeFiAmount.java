package ai.pearl.wallet;

import java.math.BigDecimal;
import java.math.BigInteger;

final class DeFiAmount {
    static BigInteger parse(String s) {
        if (s == null || !s.matches("(?:0|[1-9][0-9]{0,70})(?:\\.[0-9]{1,6})?"))
            throw new IllegalArgumentException("USDC 金额最多 6 位小数，请直接填写数字");
        BigInteger v = new BigDecimal(s).movePointRight(6).toBigIntegerExact();
        if (v.signum() <= 0 || v.bitLength() > 256 || v.equals(BigInteger.ONE.shiftLeft(256).subtract(BigInteger.ONE)))
            throw new IllegalArgumentException("请输入明确的正数 USDC 金额");
        return v;
    }
    static String usdc(BigInteger v) { return new BigDecimal(v, 6).stripTrailingZeros().toPlainString(); }
    static String eth(BigInteger v) { return new BigDecimal(v, 18).stripTrailingZeros().toPlainString(); }
}
