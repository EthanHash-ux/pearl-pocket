package ai.pearl.wallet;

import static org.junit.Assert.*;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import org.json.*;
import org.junit.Test;

public class DeFiTest {
    static final String FROM = "0x9858effd232b4033e47d90003d41ec34ecaeda94";
    static final BigInteger MILLION = BigInteger.valueOf(1_000_000);
    static JSONObject request(String m, JSONArray p) throws Exception { return new JSONObject().put("jsonrpc","2.0").put("id",1).put("method",m).put("params",p); }
    static String abi(BigInteger... v) { StringBuilder b = new StringBuilder("0x"); for (BigInteger x : v) b.append(EvmPublicApi.word(x)); return b.toString(); }
    static BigInteger config() { return BigInteger.valueOf(6).shiftLeft(48).setBit(56); }
    static final class Rpc implements EvmPublicApi.Rpc {
        BigInteger config = config(), allowance = BigInteger.ZERO, debt = BigInteger.ZERO;
        String chain = "0x1", receipt = "null", pool = DeFiApi.POOL;
        boolean badPrecision, badAToken, simulateFailure, oldBlock;
        final List<JSONObject> calls = new ArrayList<>();
        public String post(String body) throws Exception {
            JSONObject r = new JSONObject(body); calls.add(r); String m = r.getString("method"); JSONArray p = r.getJSONArray("params"); Object result;
            switch(m) {
                case "eth_chainId": result = chain; break;
                case "eth_blockNumber": result = "0x100"; break;
                case "eth_getBalance": result = "0xde0b6b3a7640000"; break;
                case "eth_maxPriorityFeePerGas": result = "0x3b9aca00"; break;
                case "eth_getTransactionCount": result = "0x5"; break;
                case "eth_getBlockByNumber": result = new JSONObject().put("number",p.getString(0)).put("hash","0x"+"12".repeat(32)).put("baseFeePerGas","0x3b9aca00").put("timestamp","0x"+Long.toHexString(System.currentTimeMillis()/1000-(oldBlock?1000:0))); break;
                case "eth_getTransactionReceipt": result = new JSONTokener(receipt).nextValue(); break;
                case "eth_estimateGas": if(simulateFailure)return new JSONObject().put("jsonrpc","2.0").put("id",1).put("error",new JSONObject().put("code",-32000)).toString();result="0x186a0";break;
                case "eth_call": {
                    JSONObject c=p.getJSONObject(0);String to=c.getString("to"),data=c.getString("data");
                    if(data.equals("0x026b1d5f"))result=abi(new BigInteger(pool.substring(2),16));
                    else if(data.equals("0x0542975c"))result=abi(new BigInteger(DeFiApi.PROVIDER.substring(2),16));
                    else if(data.equals("0x313ce567"))result=abi(BigInteger.valueOf(badPrecision?18:6));
                    else if(data.startsWith("0x35ea6a75")) {BigInteger[] a=new BigInteger[15];java.util.Arrays.fill(a,BigInteger.ZERO);a[0]=config;a[2]=BigInteger.TEN.pow(25).multiply(BigInteger.valueOf(3));a[8]=new BigInteger((badAToken?DeFiApi.USDC:DeFiApi.A_USDC).substring(2),16);result=abi(a);}
                    else if(data.startsWith("0xbf92857c"))result=abi(MILLION,debt,BigInteger.ZERO,BigInteger.ZERO,BigInteger.ZERO,BigInteger.TEN.pow(18).multiply(BigInteger.valueOf(2)));
                    else if(data.startsWith("0xdd62ed3e"))result=abi(allowance);
                    else if(data.equals("0x18160ddd"))result=abi(MILLION.multiply(BigInteger.valueOf(1000)));
                    else result=abi(MILLION.multiply(BigInteger.valueOf(to.equals(DeFiApi.A_USDC)?5:10)));
                    break;
                }
                default: throw new AssertionError("unexpected method "+m);
            }
            return new JSONObject().put("jsonrpc","2.0").put("id",1).put("result",result).toString();
        }
    }
    @Test public void parsesExactUSDCAndRejectsAmbiguousAmounts() {
        assertEquals(BigInteger.ONE,DeFiAmount.parse("0.000001"));assertEquals(new BigInteger("123456789"),DeFiAmount.parse("123.456789"));
        for(String s:new String[]{"0","-1","1e6","1.0000001","01"," 1","1,000","NaN",".5","1."})assertThrows(IllegalArgumentException.class,()->DeFiAmount.parse(s));
    }
    @Test public void readsConsistentBlockAndComputesVariableAPR() throws Exception {
        Rpc r=new Rpc();DeFiApi.Position p=new DeFiApi(r).position(FROM);
        assertEquals("3.000%",p.apr());assertEquals(MILLION.multiply(BigInteger.valueOf(5)),p.supplied);assertEquals("0x100",p.block);
        for(JSONObject c:r.calls) if(c.getString("method").equals("eth_call"))assertEquals("0x100",c.getJSONArray("params").getString(1));
    }
    @Test public void failsClosedOnWrongNetworkOrMarketOrToken() throws Exception {
        for(int i=0;i<4;i++){Rpc r=new Rpc();if(i==0)r.chain="0x89";if(i==1)r.pool=DeFiApi.USDC;if(i==2)r.badPrecision=true;if(i==3)r.badAToken=true;assertThrows(IllegalArgumentException.class,()->new DeFiApi(r).position(FROM));}
    }
    @Test public void approvalAndSupplyAreSeparateAndBounded() throws Exception {
        Rpc r=new Rpc();DeFiApi api=new DeFiApi(r);
        assertThrows(IllegalArgumentException.class,()->api.prepare(FROM,"supply",MILLION));
        JSONObject approve=api.prepare(FROM,"approve",MILLION);assertEquals("approve",approve.getString("operation"));assertEquals("130000",approve.getString("gas"));assertEquals("3000000000",approve.getString("maxFeePerGas"));assertEquals("5",approve.getString("nonce"));
        JSONObject intent=DeFiApi.intent("approve",FROM,MILLION);assertEquals(DeFiApi.USDC,intent.getString("to"));assertEquals("0x095ea7b3"+EvmPublicApi.addressWord(DeFiApi.POOL)+EvmPublicApi.word(MILLION),intent.getString("data"));
        r.allowance=MILLION;assertEquals("supply",api.prepare(FROM,"supply",MILLION).getString("operation"));
        r.simulateFailure=true;assertThrows(IllegalArgumentException.class,()->api.prepare(FROM,"supply",MILLION));
    }
    @Test public void blocksPausedFrozenCappedIlliquidAndBorrowedPositions() throws Exception {
        Rpc r=new Rpc();DeFiApi api=new DeFiApi(r);r.allowance=MILLION;
        r.config=r.config.setBit(60);assertThrows(IllegalArgumentException.class,()->api.prepare(FROM,"supply",MILLION));
        r.config=config().setBit(57);assertThrows(IllegalArgumentException.class,()->api.prepare(FROM,"approve",MILLION));
        r.config=config().or(BigInteger.valueOf(1000).shiftLeft(116));assertThrows(IllegalArgumentException.class,()->api.prepare(FROM,"supply",MILLION));
        r.config=config();r.debt=BigInteger.ONE;assertThrows(IllegalArgumentException.class,()->api.prepare(FROM,"withdraw",MILLION));
        r.debt=BigInteger.ZERO;assertThrows(IllegalArgumentException.class,()->api.prepare(FROM,"withdraw",MILLION.multiply(BigInteger.valueOf(6))));
        r.oldBlock=true;assertThrows(IllegalArgumentException.class,()->api.prepare(FROM,"withdraw",MILLION));
    }
    @Test public void restrictsRPCAndContractRecipients() throws Exception {
        JSONObject c=DeFiApi.intent("supply",FROM,MILLION);DeFiApi.validateRequest(request("eth_estimateGas",new JSONArray().put(c).put("pending")));
        c.put("to",DeFiApi.USDC);assertThrows(IllegalArgumentException.class,()->DeFiApi.validateRequest(request("eth_estimateGas",new JSONArray().put(c).put("pending"))));
        c.put("to",DeFiApi.POOL).put("data",c.getString("data").replace(EvmPublicApi.addressWord(FROM),EvmPublicApi.addressWord(DeFiApi.USDC)));
        assertThrows(IllegalArgumentException.class,()->DeFiApi.validateRequest(request("eth_estimateGas",new JSONArray().put(c).put("pending"))));
        assertThrows(IllegalArgumentException.class,()->DeFiApi.validateRequest(request("eth_sign",new JSONArray().put(FROM).put("0x00"))));
        assertThrows(IllegalArgumentException.class,()->DeFiApi.intent("approve",FROM,BigInteger.ONE.shiftLeft(256).subtract(BigInteger.ONE)));
        assertThrows(IllegalArgumentException.class,()->EvmPublicApi.validateRequest(request("eth_sendRawTransaction",new JSONArray().put("0x02"))));
    }
    @Test public void distinguishesUnconfirmedSuccessFailureAndReorg() throws Exception {
        Rpc r=new Rpc();DeFiApi api=new DeFiApi(r);JSONObject signed=new JSONObject().put("hash","0x"+"ab".repeat(32)).put("from",FROM).put("to",DeFiApi.POOL);
        assertEquals(0,api.receipt(signed));
        JSONObject receipt=new JSONObject().put("transactionHash",signed.getString("hash")).put("from",FROM).put("to",DeFiApi.POOL).put("blockHash","0x"+"12".repeat(32)).put("blockNumber","0xff").put("status","0x1");
        r.receipt=receipt.toString();assertEquals(0,api.receipt(signed));receipt.put("blockNumber","0xfe");r.receipt=receipt.toString();assertEquals(1,api.receipt(signed));
        receipt.put("status","0x0");r.receipt=receipt.toString();assertEquals(-1,api.receipt(signed));
        receipt.put("blockHash","0x"+"34".repeat(32));r.receipt=receipt.toString();assertEquals(0,api.receipt(signed));
        receipt.put("from",DeFiApi.USDC);r.receipt=receipt.toString();assertThrows(IllegalArgumentException.class,()->api.receipt(signed));
    }
}
