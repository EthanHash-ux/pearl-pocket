package ai.pearl.wallet;

import java.math.BigInteger;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

/** Pearl Pocket payment QR format. Raw addresses remain the default for other wallets. */
final class PaymentRequest {
    final String address;
    final BigInteger amount; // null means the sender chooses an amount
    private PaymentRequest(String address, BigInteger amount) { this.address = address; this.amount = amount; }
    static PaymentRequest parse(String input) {
        String value = input == null ? "" : input.trim();
        if (value.length() > 512) throw new IllegalArgumentException("收款二维码内容过长");
        if (!value.regionMatches(true, 0, "pearl:", 0, 6)) return new PaymentRequest(PearlAddress.normalize(value), null);
        try {
            URI uri = new URI(value);
            if (!uri.isOpaque() || uri.getRawFragment() != null) throw new IllegalArgumentException("收款链接格式无效");
            String[] parts = uri.getRawSchemeSpecificPart().split("\\?", -1);
            if (parts.length > 2) throw new IllegalArgumentException("收款链接格式无效");
            String address = PearlAddress.normalize(parts[0]);
            BigInteger amount = null;
            if (parts.length == 2) {
                // Only an explicit amount is supported. Unknown/required parameters must never be silently ignored.
                String[] pair = parts[1].split("=", -1);
                if (pair.length != 2 || !"amount".equals(pair[0])) throw new IllegalArgumentException("收款链接含不支持或重复的参数");
                amount = PearlAmount.parsePositivePrl(URLDecoder.decode(pair[1], StandardCharsets.UTF_8.name()));
            }
            return new PaymentRequest(address, amount);
        } catch (IllegalArgumentException e) { throw e; }
        catch (Exception e) { throw new IllegalArgumentException("收款链接格式无效"); }
    }
    static String encode(String address, String amount) {
        return "pearl:" + PearlAddress.normalize(address) + "?amount=" + PearlAmount.format(PearlAmount.parsePositivePrl(amount));
    }
}
