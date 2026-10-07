package ai.pearl.wallet;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Locale;
import org.json.JSONArray;
import org.json.JSONObject;

/** Ethereum mainnet Aave V3 USDC only. Seeds and passwords never enter this boundary. */
final class DeFiApi {
    static final String PROVIDER = "0x2f39d218133afab8f2b819b1066c7e434ad94e9e";
    static final String POOL = "0x87870bca3f3fd6335c3f4ce8392d69350b4fa4e2";
    static final String USDC = "0xa0b86991c6218b36c1d19d4a2e9eb0ce3606eb48";
    static final String A_USDC = "0x98c23e9d8f34fefb1b7bd6a91b7ff122f4e16f5c";
    static final BigInteger MAX_FEE = new BigInteger("500000000000");
    private final EvmPublicApi.Rpc rpc;
    DeFiApi() { this(PearlApi::httpsDeFi); }
    DeFiApi(EvmPublicApi.Rpc rpc) { this.rpc = rpc; }

    static JSONObject intent(String op, String from, BigInteger amount) throws Exception {
        from = EvmPublicApi.address(from);
        if (amount.signum() <= 0 || amount.bitLength() > 256 || amount.equals(BigInteger.ONE.shiftLeft(256).subtract(BigInteger.ONE)))
            throw new IllegalArgumentException("USDC 金额无效");
        String data, to = POOL, w = EvmPublicApi.word(amount);
        switch (op) {
            case "approve": to = USDC; data = "095ea7b3" + EvmPublicApi.addressWord(POOL) + w; break;
            case "supply": data = "617ba037" + EvmPublicApi.addressWord(USDC) + w + EvmPublicApi.addressWord(from) + EvmPublicApi.word(BigInteger.ZERO); break;
            case "withdraw": data = "69328dec" + EvmPublicApi.addressWord(USDC) + w + EvmPublicApi.addressWord(from); break;
            default: throw new IllegalArgumentException("不支持此借贷操作");
        }
        return new JSONObject().put("from", from).put("to", to).put("value", "0x0").put("data", "0x" + data);
    }
    private static boolean transactionAllowed(JSONObject c) throws Exception {
        if (c.length() != 4 || !"0x0".equals(c.getString("value"))) return false;
        String from = EvmPublicApi.address(c.getString("from")), to = EvmPublicApi.address(c.getString("to")), d = c.getString("data");
        if (!d.matches("0x[0-9a-f]+") || d.length() < 138) return false;
        String op = d.startsWith("0x095ea7b3") ? "approve" : d.startsWith("0x617ba037") ? "supply" : d.startsWith("0x69328dec") ? "withdraw" : "";
        JSONObject expected = intent(op, from, new BigInteger(d.substring(74, 138), 16));
        return to.equals(expected.getString("to")) && d.equals(expected.getString("data"));
    }
    private static boolean blockTag(String s) { return s.matches("0x[0-9a-f]+") && s.length() <= 18; }
    private static boolean hash(String s) { return s.matches("0x[0-9a-f]{64}"); }
    static void validateRequest(JSONObject r) throws Exception {
        if (r.length() != 4 || !"2.0".equals(r.getString("jsonrpc")) || r.getInt("id") != 1) throw new IllegalArgumentException("DeFi RPC 请求无效");
        String method = r.getString("method"); JSONArray p = r.getJSONArray("params");
        if ((method.equals("eth_chainId") || method.equals("eth_blockNumber") || method.equals("eth_maxPriorityFeePerGas")) && p.length() == 0) return;
        if (method.equals("eth_getBlockByNumber") && p.length() == 2 && blockTag(p.getString(0)) && Boolean.FALSE.equals(p.get(1))) return;
        if (method.equals("eth_getBalance") && p.length() == 2 && blockTag(p.getString(1))) { EvmPublicApi.address(p.getString(0)); return; }
        if (method.equals("eth_getTransactionCount") && p.length() == 2 && "pending".equals(p.getString(1))) { EvmPublicApi.address(p.getString(0)); return; }
        if (method.equals("eth_getTransactionReceipt") && p.length() == 1 && hash(p.getString(0))) return;
        if (method.equals("eth_estimateGas") && p.length() == 2 && "pending".equals(p.getString(1)) && transactionAllowed(p.getJSONObject(0))) return;
        if (method.equals("eth_sendRawTransaction") && p.length() == 1) {
            NativeCore.call(new JSONObject().put("action", "ethtransaction").put("raw", p.getString(0))); return;
        }
        if (method.equals("eth_call") && p.length() == 2 && blockTag(p.getString(1))) {
            JSONObject c = p.getJSONObject(0); String to = EvmPublicApi.address(c.getString("to")), d = c.getString("data");
            boolean allowed = c.length() == 2 && (
                (to.equals(PROVIDER) && d.equals("0x026b1d5f")) ||
                (to.equals(POOL) && (d.equals("0x0542975c") || d.equals("0x35ea6a75" + EvmPublicApi.addressWord(USDC)) || d.matches("0xbf92857c0{24}[0-9a-f]{40}"))) ||
                ((to.equals(USDC) || to.equals(A_USDC)) && (d.equals("0x313ce567") || d.equals("0x18160ddd") || d.matches("0x70a082310{24}[0-9a-f]{40}"))) ||
                (to.equals(USDC) && d.matches("0xdd62ed3e0{24}[0-9a-f]{40}" + EvmPublicApi.addressWord(POOL))));
            if (allowed) return;
        }
        throw new IllegalArgumentException("仅允许固定 Aave USDC 查询与流动性交易");
    }
    private Object request(String method, JSONArray p) throws Exception {
        JSONObject r = new JSONObject().put("jsonrpc", "2.0").put("id", 1).put("method", method).put("params", p);
        validateRequest(r);
        String body = rpc.post(r.toString());
        if (body.length() > 100_000) throw new IllegalArgumentException("DeFi 响应过大");
        JSONObject result = new JSONObject(body);
        if (!"2.0".equals(result.getString("jsonrpc")) || result.getInt("id") != 1 || result.has("error"))
            throw new IllegalArgumentException("Ethereum 查询或模拟未通过，请刷新检查余额、授权和协议状态");
        return result.get("result");
    }
    private String hex(String method, JSONArray p) throws Exception {
        Object v = request(method, p);
        if (!(v instanceof String) || !((String)v).matches("0x[0-9a-fA-F]+") || ((String)v).length() > 8194) throw new IllegalArgumentException("DeFi 返回值无效");
        return ((String)v).toLowerCase(Locale.ROOT);
    }
    static BigInteger quantity(String s) {
        if (s == null || !s.matches("0x(?:0|[1-9a-fA-F][0-9a-fA-F]{0,63})")) throw new IllegalArgumentException("Ethereum 数量返回值无效");
        return new BigInteger(s.substring(2), 16);
    }
    private BigInteger q(String method, JSONArray p) throws Exception { return quantity(hex(method, p)); }
    private String call(String to, String data, String block) throws Exception { return hex("eth_call", new JSONArray().put(new JSONObject().put("to", to).put("data", data)).put(block)); }
    static BigInteger[] words(String s, int n) {
        if (s == null || !s.matches("0x[0-9a-fA-F]+") || s.length() != 2 + n * 64) throw new IllegalArgumentException("Aave 接口返回值长度发生变化");
        BigInteger[] v = new BigInteger[n]; for (int i = 0; i < n; i++) v[i] = new BigInteger(s.substring(2 + i * 64, 66 + i * 64), 16); return v;
    }
    private BigInteger uint(String to, String data, String b) throws Exception { return words(call(to, data, b), 1)[0]; }
    private void chain() throws Exception { if (!q("eth_chainId", new JSONArray()).equals(BigInteger.ONE)) throw new IllegalArgumentException("RPC 不是 Ethereum 主网"); }
    static final class Position {
        final String from, block;
        final BigInteger usdc, supplied, wei, allowance, liquidity, debt, health, rate, config, supplyCap, totalSupply;
        Position(String a, String b, BigInteger u, BigInteger s, BigInteger e, BigInteger al, BigInteger l, BigInteger[] account, BigInteger[] reserve, BigInteger total) {
            from = a; block = b; usdc = u; supplied = s; wei = e; allowance = al; liquidity = l; debt = account[1]; health = account[5];
            rate = reserve[2]; config = reserve[0]; supplyCap = config.shiftRight(116).and(BigInteger.ONE.shiftLeft(36).subtract(BigInteger.ONE)); totalSupply = total;
        }
        boolean active() { return config.testBit(56) && !config.testBit(60); }
        String apr() { return new BigDecimal(rate, 25).setScale(3, java.math.RoundingMode.HALF_UP).toPlainString() + "%"; }
        void check(String op, BigInteger amount) {
            if (!active()) throw new IllegalArgumentException("Aave USDC 储备已暂停或未启用");
            if (op.equals("supply") || op.equals("approve")) {
                if (config.testBit(57)) throw new IllegalArgumentException("Aave USDC 储备已冻结新增存款");
                if (amount.compareTo(usdc) > 0) throw new IllegalArgumentException("Ethereum USDC 余额不足");
                if (supplyCap.signum() > 0 && totalSupply.add(amount).compareTo(supplyCap.multiply(BigInteger.TEN.pow(6))) > 0) throw new IllegalArgumentException("存款超过 Aave USDC 当前供应上限");
                if (op.equals("supply") && allowance.compareTo(amount) < 0) throw new IllegalArgumentException("请先授权本次存款金额，等待确认后再存入");
            } else if (op.equals("withdraw")) {
                if (debt.signum() > 0) throw new IllegalArgumentException("此地址存在借款，请先在 Aave 管理抵押和借款；本版只支持无借款仓位赎回");
                if (amount.compareTo(supplied) > 0) throw new IllegalArgumentException("赎回金额超过当前存款余额");
                if (amount.compareTo(liquidity) > 0) throw new IllegalArgumentException("当前池内 USDC 流动性不足");
            } else throw new IllegalArgumentException("不支持此操作");
        }
    }
    Position position(String input) throws Exception {
        String a = EvmPublicApi.address(input); chain(); String b = hex("eth_blockNumber", new JSONArray());
        if (quantity(b).signum() <= 0 || !blockTag(b)) throw new IllegalArgumentException("Ethereum 区块无效");
        if (!uint(PROVIDER, "0x026b1d5f", b).equals(new BigInteger(POOL.substring(2), 16)) || !uint(POOL, "0x0542975c", b).equals(new BigInteger(PROVIDER.substring(2), 16))) throw new IllegalArgumentException("Aave 市场地址发生变化，请更新应用");
        BigInteger[] reserve = words(call(POOL, "0x35ea6a75" + EvmPublicApi.addressWord(USDC), b), 15);
        if (!reserve[8].equals(new BigInteger(A_USDC.substring(2), 16)) || !reserve[0].shiftRight(48).and(BigInteger.valueOf(255)).equals(BigInteger.valueOf(6)) || !uint(USDC, "0x313ce567", b).equals(BigInteger.valueOf(6)) || !uint(A_USDC, "0x313ce567", b).equals(BigInteger.valueOf(6))) throw new IllegalArgumentException("USDC 精度或凭证地址发生变化");
        BigInteger[] account = words(call(POOL, "0xbf92857c" + EvmPublicApi.addressWord(a), b), 6);
        return new Position(a, b, uint(USDC, "0x70a08231" + EvmPublicApi.addressWord(a), b), uint(A_USDC, "0x70a08231" + EvmPublicApi.addressWord(a), b), q("eth_getBalance", new JSONArray().put(a).put(b)),
            uint(USDC, "0xdd62ed3e" + EvmPublicApi.addressWord(a) + EvmPublicApi.addressWord(POOL), b), uint(USDC, "0x70a08231" + EvmPublicApi.addressWord(A_USDC), b), account, reserve, uint(A_USDC, "0x18160ddd", b));
    }
    JSONObject prepare(String from, String op, BigInteger amount) throws Exception {
        Position p = position(from); p.check(op, amount);
        Object header = request("eth_getBlockByNumber", new JSONArray().put(p.block).put(false));
        if (!(header instanceof JSONObject)) throw new IllegalArgumentException("Ethereum 区块头无效");
        JSONObject h = (JSONObject)header;
        long now = System.currentTimeMillis() / 1000;
        BigInteger timestamp = quantity(h.getString("timestamp"));
        if (!quantity(h.getString("number")).equals(quantity(p.block)) || timestamp.compareTo(BigInteger.valueOf(now - 180)) < 0 || timestamp.compareTo(BigInteger.valueOf(now + 60)) > 0) throw new IllegalArgumentException("Ethereum 节点区块已过期，请重新连接");
        BigInteger base = quantity(h.getString("baseFeePerGas")), tip = q("eth_maxPriorityFeePerGas", new JSONArray());
        BigInteger fee = base.multiply(BigInteger.valueOf(2)).add(tip).max(BigInteger.ONE);
        if (fee.compareTo(MAX_FEE) > 0 || tip.compareTo(fee) > 0) throw new IllegalArgumentException("Ethereum gas 单价超过本版限制");
        BigInteger estimate = q("eth_estimateGas", new JSONArray().put(intent(op, from, amount)).put("pending"));
        BigInteger gas = estimate.multiply(BigInteger.valueOf(120)).divide(BigInteger.valueOf(100)).add(BigInteger.valueOf(10000));
        if (estimate.compareTo(BigInteger.valueOf(21000)) < 0 || gas.compareTo(BigInteger.valueOf(1_000_000)) > 0) throw new IllegalArgumentException("Ethereum gas 估算超出范围");
        if (gas.multiply(fee).compareTo(p.wei) > 0) throw new IllegalArgumentException("Ethereum ETH 余额不足以支付最高网络手续费");
        BigInteger nonce = q("eth_getTransactionCount", new JSONArray().put(p.from).put("pending"));
        if (nonce.bitLength() > 64) throw new IllegalArgumentException("Ethereum nonce 无效");
        return new JSONObject().put("operation", op).put("from", p.from).put("amount", amount.toString()).put("chainId", 1).put("nonce", nonce.toString())
            .put("gas", gas.toString()).put("maxFeePerGas", fee.toString()).put("maxPriorityFeePerGas", tip.toString()).put("expires", now + 300);
    }
    String broadcast(JSONObject signed) throws Exception {
        chain();
        JSONObject decoded = NativeCore.call(new JSONObject().put("action", "ethtransaction").put("raw", signed.getString("raw")));
        if (!decoded.getString("hash").equals(signed.getString("hash"))) throw new IllegalArgumentException("交易哈希不匹配");
        String result = hex("eth_sendRawTransaction", new JSONArray().put(decoded.getString("raw")));
        if (!result.equals(decoded.getString("hash"))) throw new IllegalArgumentException("节点返回了其他交易哈希");
        return result;
    }
    /** 0 = unknown/unconfirmed; 1 = success after 3 blocks; -1 = reverted after 3 blocks. */
    int receipt(JSONObject signed) throws Exception {
        chain(); Object result = request("eth_getTransactionReceipt", new JSONArray().put(signed.getString("hash")));
        if (result == JSONObject.NULL) return 0;
        if (!(result instanceof JSONObject)) throw new IllegalArgumentException("Ethereum 收据无效");
        JSONObject r = (JSONObject)result;
        if (!signed.getString("hash").equals(r.getString("transactionHash")) || !EvmPublicApi.address(signed.getString("from")).equals(EvmPublicApi.address(r.getString("from"))) || !EvmPublicApi.address(signed.getString("to")).equals(EvmPublicApi.address(r.getString("to"))) || !hash(r.getString("blockHash"))) throw new IllegalArgumentException("Ethereum 收据与待确认交易不匹配");
        BigInteger status = quantity(r.getString("status")), block = quantity(r.getString("blockNumber")), current = q("eth_blockNumber", new JSONArray());
        if (block.signum() <= 0 || current.compareTo(block) < 0 || !(status.equals(BigInteger.ZERO) || status.equals(BigInteger.ONE))) throw new IllegalArgumentException("Ethereum 收据状态无效");
        if (current.subtract(block).compareTo(BigInteger.valueOf(2)) < 0) return 0;
        JSONObject canonical = (JSONObject)request("eth_getBlockByNumber", new JSONArray().put("0x" + block.toString(16)).put(false));
        if (!r.getString("blockHash").equals(canonical.getString("hash")) || !quantity(canonical.getString("number")).equals(block)) return 0;
        return status.equals(BigInteger.ONE) ? 1 : -1;
    }
}
