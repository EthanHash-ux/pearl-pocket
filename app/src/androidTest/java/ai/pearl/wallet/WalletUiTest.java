package ai.pearl.wallet;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.By;
import androidx.test.uiautomator.UiDevice;
import androidx.test.uiautomator.UiObject2;
import androidx.test.uiautomator.Until;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.File;
import java.nio.file.Files;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import static org.junit.Assert.*;

/** Destructive UI checks are restricted to a disposable emulator, never a real phone. */
@RunWith(AndroidJUnit4.class)
public class WalletUiTest {
    private final String password="Emulator-wallet-test-42";
    private Context context(){return InstrumentationRegistry.getInstrumentation().getTargetContext();}
    private UiDevice device(){return UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());}
    private UiObject2 text(String text){UiObject2 item=device().wait(Until.findObject(By.text(text)),30_000);assertNotNull("Expected UI control is missing: "+text,item);return item;}
    private void tap(String text){text(text).click();}
    private void authenticate(){UiObject2 pin=device().wait(Until.findObject(By.clazz("android.widget.EditText")),15_000);assertNotNull("System credential entry is missing",pin);pin.setText("24682468");device().pressEnter();}
    private void hideKeyboard(){if(device().hasObject(By.pkg("com.google.android.inputmethod.latin")))device().pressBack();}
    private void passwords(){text("新钱包密码（至少 10 个字符）").setText(password);text("再次输入密码").setText(password);hideKeyboard();}
    private void fresh()throws Exception{
        assertEquals("1",device().executeShellCommand("getprop ro.kernel.qemu").trim());
        device().pressHome();Thread.sleep(400);
        File wallet=new File(context().getNoBackupFilesDir(),"wallet-v1.json");
        if(wallet.exists()) {JSONObject meta=new JSONObject(new String(Files.readAllBytes(wallet.toPath()),java.nio.charset.StandardCharsets.UTF_8));KeyStore keys=KeyStore.getInstance("AndroidKeyStore");keys.load(null);keys.deleteEntry("pearl-wallet-"+meta.getString("id"));assertTrue(wallet.delete());}
        context().getSharedPreferences("backup_status",Context.MODE_PRIVATE).edit().clear().commit();
        Intent intent=new Intent(context(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK);context().startActivity(intent);
        text("恢复已有手机钱包");
    }
    private List<String> backup(){
        text("离线助记词备份");List<String> result=new ArrayList<>();
        for(UiObject2 item:device().findObjects(By.clazz("android.widget.TextView"))){String value=item.getText();if(value==null)continue;Matcher m=Pattern.compile("(\\d+)\\. ([a-z]+)").matcher(value);while(m.find())result.add(m.group(2));}
        assertEquals("Expected 24-word generated backup",24,result.size());return result;
    }
    private String receive(){tap("↙  接收");text("Pearl 主网收款地址");UiObject2 address=device().findObject(By.text(Pattern.compile("prl1p[a-z0-9]{58}")));assertNotNull("Receive address missing",address);String result=address.getText();assertNotNull(device().findObject(By.desc("Pearl 地址收款二维码")));tap("关闭");return result;}
    private void recover(String phrase,String expected,boolean clipboard)throws Exception{
        tap("恢复已有手机钱包");text("恢复手机钱包");
        assertFalse(text("校验并继续").isEnabled());
        if(clipboard){InstrumentationRegistry.getInstrumentation().runOnMainSync(()->((ClipboardManager)context().getSystemService(Context.CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("public emulator recovery vector",phrase)));tap("粘贴整段助记词");}
        else {UiObject2 first=device().wait(Until.findObject(By.desc("第 1 个助记词")),10_000);assertNotNull(first);first.setText(phrase);hideKeyboard();}
        assertTrue("Valid phrase must enable continuation",text("校验并继续").isEnabled());tap("校验并继续");text("确认恢复的钱包");assertNotNull("Derived address differs from original wallet",device().findObject(By.text(expected)));
        tap("设置钱包密码");authenticate();text("设置恢复钱包的密码");passwords();tap("恢复钱包");text("钱包已恢复");tap("知道了");assertEquals(expected,receive());
        if(clipboard)InstrumentationRegistry.getInstrumentation().runOnMainSync(()->((ClipboardManager)context().getSystemService(Context.CLIPBOARD_SERVICE)).clearPrimaryClip());
    }
    @Test public void createAndRecoverTwentyFourWordsThenTwelveWordClipboard()throws Exception{
        fresh();tap("创建手机钱包");authenticate();text("创建手机钱包");passwords();tap("继续");List<String> words=backup();
        tap("已抄好，验证备份");text("验证离线备份");List<UiObject2> fields=device().findObjects(By.clazz("android.widget.EditText"));assertEquals(3,fields.size());
        for(UiObject2 field:fields){Matcher m=Pattern.compile("第 (\\d+) 个词").matcher(field.getText());assertTrue(m.find());field.setText(words.get(Integer.parseInt(m.group(1))-1));}
        hideKeyboard();tap("完成");text("备份完成");tap("知道了");String original=receive();
        fresh();recover(String.join(" ",words),original,false);words.clear();
        tap("⚙\n设置");tap("查看离线备份");authenticate();text("钱包密码").setText("Wrong-password-42");hideKeyboard();tap("确认");text("操作失败");assertTrue(device().hasObject(By.textContains("密码错误")));tap("知道了");
        fresh();tap("恢复已有手机钱包");text("恢复手机钱包");tap("12 个词");UiObject2 first=device().findObject(By.desc("第 1 个助记词"));assertNotNull(first);first.setText("abandon ".repeat(12).trim());hideKeyboard();assertFalse(text("校验并继续").isEnabled());
        assertTrue(device().hasObject(By.textContains("校验")));first=device().findObject(By.desc("第 1 个助记词"));first.setText("abndon");hideKeyboard();assertFalse(text("校验并继续").isEnabled());
        device().pressHome();context().startActivity(new Intent(context(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));text("恢复已有手机钱包");assertFalse(device().hasObject(By.text("恢复手机钱包")));
        String phrase="abandon ".repeat(11)+"about";recover(phrase,"prl1pr6yuq8u2r95wjzzgpdy8cpnncpl7l8zgy6x5q0367pnc53s2famqg7pt74",true);
        tap("⌁\n行情");assertTrue("Live exchange price did not appear",device().wait(Until.hasObject(By.textContains("BigONE ·")),40_000));assertTrue(device().hasObject(By.textContains("接收 ")));tap("7 天");
        tap("⚙\n设置");tap("USDT");tap("⌁\n行情");assertTrue(device().wait(Until.hasObject(By.text(Pattern.compile("[0-9.]+ USDT"))),10_000));
    }
    @Test public void actualWebSocketStopsInBackgroundAndStartsWithNewSnapshot()throws Exception{
        AtomicInteger received=new AtomicInteger();java.util.concurrent.atomic.AtomicBoolean secondSession=new java.util.concurrent.atomic.AtomicBoolean();CountDownLatch first=new CountDownLatch(1),resumed=new CountDownLatch(1);
        MarketFeed feed=new MarketFeed(new PearlApi(),new MarketFeed.Listener(){public void quote(PearlApi.Price p){if(p.live){assertNotNull(p.usdt);assertTrue(p.usdt.signum()>0);received.incrementAndGet();first.countDown();if(secondSession.get())resumed.countDown();}}public void state(String state){}});
        try {feed.start();assertTrue("Public WSS did not deliver the initial snapshot",first.await(45,TimeUnit.SECONDS));feed.stop();int stopped=received.get();Thread.sleep(1200);assertEquals("Feed published after stop",stopped,received.get());secondSession.set(true);feed.start();assertTrue("Resumed WSS did not deliver a fresh snapshot",resumed.await(45,TimeUnit.SECONDS));assertTrue(received.get()>stopped);}finally{feed.close();}
    }
    @Test public void recoverFifteenEighteenAndTwentyOneWordsAndUnlockPersistedVault()throws Exception{
        int[] counts={15,18,21};String[] lastWords={"address","agent","admit"};
        for(int i=0;i<counts.length;i++){
            fresh();String phrase="abandon ".repeat(counts[i]-1)+lastWords[i];
            byte[] entropy=new byte[(counts[i]*11*32/33)/8];
            String expected=NativeCore.address(entropy);
            tap("恢复已有手机钱包");text("恢复手机钱包");tap(counts[i]+" 个词");
            assertTrue(device().hasObject(By.text("已填写 0 / "+counts[i]+" 个词")));tap("取消");
            recover(phrase,expected,i!=1);
            WalletVault persisted=new WalletVault(context());
            byte[] unlocked=persisted.unlock(password.toCharArray());
            try {assertArrayEquals("Stored recovery entropy differs",entropy,unlocked);assertEquals(expected,NativeCore.address(unlocked));}
            finally{java.util.Arrays.fill(unlocked,(byte)0);java.util.Arrays.fill(entropy,(byte)0);}
        }
    }
}
