package ai.pearl.wallet;

import java.util.Locale;

/** Strict BIP350 mainnet validation: HRP prl, witness v1/v2, 32 bytes. */
public final class PearlAddress {
    private static final String CHARSET = "qpzry9x8gf2tvdw0s3jn54khce6mua7l";
    private static final int[] GENERATOR = {0x3b6a57b2, 0x26508e6d, 0x1ea119fa, 0x3d4233dd, 0x2a1462b3};
    private PearlAddress() { }

    private static int step(int chk, int value) {
        int top = chk >>> 25;
        chk = ((chk & 0x1ffffff) << 5) ^ value;
        for (int i = 0; i < 5; i++) if (((top >>> i) & 1) != 0) chk ^= GENERATOR[i];
        return chk;
    }

    public static String normalize(String input) {
        if (input == null) throw new IllegalArgumentException("请输入 Pearl 地址");
        String address = input.trim();
        if (address.length() != 63) throw new IllegalArgumentException("需要 Pearl 主网 Taproot 或 P2MR 地址");
        String lower = address.toLowerCase(Locale.ROOT);
        String upper = address.toUpperCase(Locale.ROOT);
        if (!address.equals(lower) && !address.equals(upper)) throw new IllegalArgumentException("地址不能混用大小写");
        if (!(lower.startsWith("prl1p") || lower.startsWith("prl1z"))) throw new IllegalArgumentException("请选择以 prl1p 或 prl1z 开头的主网地址");
        int chk = 1;
        String hrp = "prl";
        for (int i = 0; i < hrp.length(); i++) chk = step(chk, hrp.charAt(i) >>> 5);
        chk = step(chk, 0);
        for (int i = 0; i < hrp.length(); i++) chk = step(chk, hrp.charAt(i) & 31);
        int acc = 0, bits = 0, byteCount = 0;
        for (int i = 4; i < lower.length(); i++) {
            int v = CHARSET.indexOf(lower.charAt(i));
            if (v < 0) throw new IllegalArgumentException("地址包含无效字符");
            chk = step(chk, v);
            if (i > 4 && i < lower.length() - 6) {
                acc = ((acc << 5) | v) & 0xffff;
                bits += 5;
                while (bits >= 8) { bits -= 8; byteCount++; }
            }
        }
        if (chk != 0x2bc830a3 || byteCount != 32 || bits >= 5 || ((acc << (8 - bits)) & 255) != 0) {
            throw new IllegalArgumentException("地址校验失败，请检查完整地址");
        }
        return lower;
    }

    public static String shortLabel(String address) {
        return address.substring(0, 11) + "…" + address.substring(address.length() - 7);
    }
}
