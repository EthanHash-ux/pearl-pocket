package ai.pearl.wallet;

import static org.junit.Assert.*;
import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.view.WindowManager;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.*;
import java.io.File;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class DeFiInstrumentedTest {
    final Instrumentation instrument = InstrumentationRegistry.getInstrumentation();
    Context context() { return instrument.getTargetContext(); }
    UiDevice device() { return UiDevice.getInstance(instrument); }
    Context sandbox() { File dir=new File(context().getCacheDir(),"defi-test-"+System.nanoTime());assertTrue(dir.mkdirs());return new ContextWrapper(context()){@Override public File getNoBackupFilesDir(){return dir;}}; }
    static JSONObject plan(String from,String op) throws Exception {return new JSONObject().put("chainId",1).put("from",from).put("operation",op).put("amount","12500000").put("nonce","0").put("gas","150000").put("maxFeePerGas","30000000000").put("maxPriorityFeePerGas","1000000000").put("expires",System.currentTimeMillis()/1000+240);}
    @Test public void nativeBip44AndJavaIntentsAgreeAcrossAllWordCounts() throws Exception {
        String[] addresses={"0x9858EfFD232B4033E47d90003D41EC34EcaEda94","0x54bE88525B024a20229Fe7f6F62DC0884e4aAA0a","0x197A1bEE163923815Ba58EaD0F14B3Fcd8C5926d","0x15cc2210a0AFbb1A2d80Dd3D585aA94eBa6c10E0","0xF278cF59F82eDcf871d630F28EcC8056f25C1cdb"};
        for(int i=0;i<5;i++){byte[] entropy=new byte[16+4*i];JSONObject id=NativeCore.ethereumIdentity(entropy);assertEquals(addresses[i],id.getString("address"));assertEquals(EthereumAccount.PATH,id.getString("path"));
            for(String op:new String[]{"approve","supply","withdraw"}) {JSONObject p=plan(id.getString("address"),op);JSONObject nativeIntent=NativeCore.call(new JSONObject().put("action","ethintent").put("ethereum",p));JSONObject javaIntent=DeFiApi.intent(op,id.getString("address"),new BigInteger(p.getString("amount")));assertEquals(nativeIntent.getString("to"),javaIntent.getString("to"));assertEquals(nativeIntent.getString("data"),javaIntent.getString("data"));JSONObject s=NativeCore.ethereumSign(entropy,p);assertEquals(id.getString("address"),s.getString("from"));}
            Arrays.fill(entropy,(byte)0);
        }
    }
    @Test public void pendingWalletBindingRestartAndIdenticalTimeoutRetry() throws Exception {
        Context c=sandbox();String from=NativeCore.ethereumIdentity(new byte[16]).getString("address");String slot=UUID.randomUUID().toString();
        JSONObject signed=NativeCore.ethereumSign(new byte[16],plan(from,"approve"));PendingEthereum p=new PendingEthereum(c,slot,from);p.save(signed);
        JSONObject loaded=new PendingEthereum(c,slot,from).load();assertEquals(signed.getString("raw"),loaded.getString("raw"));assertThrows(IllegalArgumentException.class,()->p.save(signed));
        assertNull(new PendingEthereum(c,UUID.randomUUID().toString(),from).load());assertThrows(IllegalArgumentException.class,()->new PendingEthereum(c,slot,DeFiApi.USDC).load());
        final int[] attempts={0};DeFiApi api=new DeFiApi(body->{JSONObject r=new JSONObject(body);String method=r.getString("method");Object result="0x1";if(method.equals("eth_sendRawTransaction")){assertEquals(signed.getString("raw"),r.getJSONArray("params").getString(0));if(++attempts[0]==1)throw new java.io.IOException("simulated timeout after node receipt");result=signed.getString("hash");}return new JSONObject().put("jsonrpc","2.0").put("id",1).put("result",result).toString();});
        assertThrows(java.io.IOException.class,()->api.broadcast(p.load()));assertEquals(signed.getString("hash"),api.broadcast(new PendingEthereum(c,slot,from).load()));assertEquals(2,attempts[0]);
        File f=new File(WalletCatalog.directory(c,slot),"pending-ethereum.json");JSONObject tampered=new JSONObject(new String(java.nio.file.Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));tampered.put("walletSlot",UUID.randomUUID().toString());java.nio.file.Files.write(f.toPath(),tampered.toString().getBytes(StandardCharsets.UTF_8));assertThrows(IllegalArgumentException.class,()->p.load());
    }
    @Test public void liveAaveMarketReadOnlyMainnet() throws Exception {
        List<String> methods=new ArrayList<>();DeFiApi api=new DeFiApi(body->{String method=new JSONObject(body).getString("method");methods.add(method);assertFalse(method.equals("eth_sendRawTransaction"));assertFalse(body.contains("entropy"));return PearlApi.httpsDeFi(body);});
        DeFiApi.Position p=api.position("0x0000000000000000000000000000000000000001");assertTrue(DeFiApi.quantity(p.block).signum()>0);assertTrue(p.rate.signum()>=0);assertEquals(0,p.debt.signum());assertTrue(methods.contains("eth_call"));
        JSONObject report=new JSONObject().put("result","PASS").put("scope","read-only Ethereum mainnet Aave V3 USDC").put("block",p.block).put("apr",p.apr()).put("pool",DeFiApi.POOL).put("aUSDC",DeFiApi.A_USDC).put("methods",new JSONArray(methods)).put("mainnet_broadcast",false);
        java.nio.file.Files.write(new File(context().getExternalFilesDir(null),"defi-live-read.json").toPath(),report.toString().getBytes(StandardCharsets.UTF_8));
    }
    static String abi(BigInteger... v){StringBuilder b=new StringBuilder("0x");for(BigInteger x:v)b.append(EvmPublicApi.word(x));return b.toString();}
    /** Public RPC simulation. It never calls any network transport. */
    static final class FixtureRpc implements EvmPublicApi.Rpc {
        final List<JSONObject> signed=new ArrayList<>();BigInteger allowance=BigInteger.ZERO,supplied=BigInteger.ZERO,usdc=BigInteger.valueOf(100_000_000);String from; boolean rejectPositionCalls;
        synchronized public String post(String body)throws Exception{
            JSONObject r=new JSONObject(body);String m=r.getString("method");JSONArray p=r.getJSONArray("params");Object result;
            switch(m){
                case "eth_chainId":result="0x1";break;
                case "eth_blockNumber":result="0x100";break;
                case "eth_getBalance":result="0xde0b6b3a7640000";break;
                case "eth_getTransactionCount":result="0x"+Integer.toHexString(signed.size());break;
                case "eth_maxPriorityFeePerGas":result="0x3b9aca00";break;
                case "eth_estimateGas":result="0x186a0";break;
                case "eth_getBlockByNumber":result=new JSONObject().put("number",p.getString(0)).put("hash","0x"+"12".repeat(32)).put("timestamp","0x"+Long.toHexString(System.currentTimeMillis()/1000)).put("baseFeePerGas","0x3b9aca00");break;
                case "eth_sendRawTransaction":{JSONObject tx=NativeCore.call(new JSONObject().put("action","ethtransaction").put("raw",p.getString(0)));assertEquals(from,tx.getString("from"));signed.add(tx);BigInteger amount=new BigInteger(tx.getString("amount"));switch(tx.getString("operation")){case "approve":allowance=amount;break;case "supply":assertTrue(allowance.compareTo(amount)>=0);supplied=supplied.add(amount);usdc=usdc.subtract(amount);allowance=allowance.subtract(amount);break;case "withdraw":supplied=supplied.subtract(amount);usdc=usdc.add(amount);break;}result=tx.getString("hash");break;}
                case "eth_getTransactionReceipt":{JSONObject found=null;for(JSONObject tx:signed)if(tx.getString("hash").equals(p.getString(0)))found=tx;if(found==null)result=JSONObject.NULL;else result=new JSONObject().put("transactionHash",found.getString("hash")).put("from",from).put("to",found.getString("to")).put("blockHash","0x"+"12".repeat(32)).put("blockNumber","0xfe").put("status","0x1");break;}
                case "eth_call":{assertFalse("Pending record must be usable even when position queries are unavailable",rejectPositionCalls);JSONObject c=p.getJSONObject(0);String data=c.getString("data"),to=c.getString("to");
                    if(data.equals("0x026b1d5f"))result=abi(new BigInteger(DeFiApi.POOL.substring(2),16));
                    else if(data.equals("0x0542975c"))result=abi(new BigInteger(DeFiApi.PROVIDER.substring(2),16));
                    else if(data.equals("0x313ce567"))result=abi(BigInteger.valueOf(6));
                    else if(data.startsWith("0x35ea6a75")){BigInteger[] a=new BigInteger[15];Arrays.fill(a,BigInteger.ZERO);a[0]=BigInteger.valueOf(6).shiftLeft(48).setBit(56);a[2]=BigInteger.TEN.pow(25).multiply(BigInteger.valueOf(3));a[8]=new BigInteger(DeFiApi.A_USDC.substring(2),16);result=abi(a);}
                    else if(data.startsWith("0xbf92857c"))result=abi(BigInteger.ZERO,BigInteger.ZERO,BigInteger.ZERO,BigInteger.ZERO,BigInteger.ZERO,BigInteger.TEN.pow(18));
                    else if(data.startsWith("0xdd62ed3e"))result=abi(allowance);
                    else if(to.equals(DeFiApi.A_USDC))result=abi(supplied);
                    else if(data.endsWith(EvmPublicApi.addressWord(from)))result=abi(usdc);
                    else result=abi(BigInteger.valueOf(1_000_000_000));break;
                }
                default:throw new AssertionError("Unexpected "+m);
            }return new JSONObject().put("jsonrpc","2.0").put("id",1).put("result",result).toString();
        }
    }
    UiObject2 text(String s){UiObject2 v=device().wait(Until.findObject(By.text(s)),15_000);assertNotNull("Missing "+s,v);return v;}
    void tap(String s){for(int i=0;i<3;i++){device().waitForIdle();try{text(s).click();return;}catch(StaleObjectException e){if(i==2)throw e;}}}
    void keyboard()throws Exception{if(device().executeShellCommand("dumpsys input_method").contains("mInputShown=true"))device().pressBack();}
    void auth(String password)throws Exception{UiObject2 pin=device().wait(Until.findObject(By.clazz("android.widget.EditText")),10_000);assertNotNull(pin);pin.setText("24682468");device().pressEnter();text("钱包密码").setText(password);keyboard();tap("确认");}
    void open() throws Exception {if(!device().hasObject(By.text("打开 DeFi")))new UiScrollable(new UiSelector().scrollable(true)).scrollIntoView(new UiSelector().text("打开 DeFi"));tap("打开 DeFi");}
    @Test public void walletUiSeparatesApprovalSupplyWithdrawAndPreservesPending()throws Exception{
        assertEquals("1",device().executeShellCommand("getprop ro.kernel.qemu").trim());
        // Run after the explicit 0.9 -> 0.10 upgrade setup. Only a known public fixture is allowed.
        assertEquals(NativeCore.address(new byte[28]),new WalletVault(context()).metadata().getString("address"));
        String selected=new WalletCatalog(context()).active();assertEquals(WalletCatalog.LEGACY,selected);
        new android.util.AtomicFile(new File(WalletCatalog.directory(context(),selected),"ethereum-address.json")).delete();
        new android.util.AtomicFile(new File(WalletCatalog.directory(context(),selected),"pending-ethereum.json")).delete();
        Instrumentation.ActivityMonitor monitor=instrument.addMonitor(MainActivity.class.getName(),null,false);
        context().startActivity(new Intent(context(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK));
        Activity a=instrument.waitForMonitorWithTimeout(monitor,15_000);instrument.removeMonitor(monitor);assertNotNull(a);
        assertTrue((a.getWindow().getAttributes().flags&WindowManager.LayoutParams.FLAG_SECURE)!=0);
        java.lang.reflect.Field field=MainActivity.class.getDeclaredField("walletFlow");field.setAccessible(true);WalletFlow flow=(WalletFlow)field.get(a);
        FixtureRpc rpc=new FixtureRpc();rpc.from=NativeCore.ethereumIdentity(new byte[28]).getString("address");
        java.lang.reflect.Field defi=MainActivity.class.getDeclaredField("deFiTools");defi.setAccessible(true);
        instrument.runOnMainSync(()->{try{((DeFiTools)defi.get(a)).close();DeFiTools t=new DeFiTools(a,()->flow,()->selected,()->"模拟测试钱包",()->false,()->false,new DeFiApi(rpc));defi.set(a,t);t.resume();}catch(Exception e){throw new AssertionError(e);}});
        open();tap("验证并生成 Ethereum 地址");auth("Wrong-password-42");text("操作失败");tap("知道了");assertEquals("",new EthereumAccount(context(),selected).load(flow.address()));
        open();tap("验证并生成 Ethereum 地址");auth("Emulator-wallet-test-42");text("复制 Ethereum 收款地址");assertEquals(rpc.from,new EthereumAccount(context(),selected).load(flow.address()));
        String[] operations={"approve","supply","withdraw"};String[] titles={"确认 授权 USDC","确认 存入 Aave","确认 赎回 USDC"};
        for(int i=0;i<3;i++){
            tap(i==2?"赎回 USDC":"存入 USDC");text("USDC 金额（最多 6 位小数）").setText(i==2?"5":"12.5");keyboard();tap("核验金额并预览手续费");text(titles[i]);assertTrue(device().hasObject(By.textContains("最高网络手续费")));assertTrue(device().hasObject(By.textContains("Ethereum 主网")));tap("验证并签名");auth("Emulator-wallet-test-42");text("DeFi 交易已提交");tap("知道了");
            assertEquals(i+1,rpc.signed.size());assertEquals(operations[i],rpc.signed.get(i).getString("operation"));assertNotNull(new PendingEthereum(context(),selected,rpc.from).load());
            rpc.rejectPositionCalls=true;open();tap("查询或重发待确认 DeFi 交易");tap("查询结果");text("DeFi 交易已确认");tap("知道了");assertNull(new PendingEthereum(context(),selected,rpc.from).load());rpc.rejectPositionCalls=false;open();text("复制 Ethereum 收款地址");
        }
        assertEquals(new BigInteger("7500000"),rpc.supplied);assertEquals(new BigInteger("92500000"),rpc.usdc);tap("关闭");
        JSONObject report=new JSONObject().put("result","PASS").put("scope","Android UI with simulated RPC; native signatures, real device/password authentication and persistence").put("operations",new JSONArray(operations)).put("signed_transactions",rpc.signed.size()).put("mainnet_broadcast",false);
        java.nio.file.Files.write(new File(context().getExternalFilesDir(null),"defi-ui-test.json").toPath(),report.toString().getBytes(StandardCharsets.UTF_8));
    }
}
