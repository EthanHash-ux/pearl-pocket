package ai.pearl.wallet;

import android.content.Context;
import android.content.Intent;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.By;
import androidx.test.uiautomator.UiDevice;
import androidx.test.uiautomator.UiObject2;
import androidx.test.uiautomator.Until;
import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Explicit two-install release upgrade check; never creates, replaces or deletes a wallet. */
@RunWith(AndroidJUnit4.class)
public class WalletUpgradeTest {
    @Test public void previousReleaseWalletRemainsDecryptableAtTheSameAddress()throws Exception{
        Assume.assumeTrue("Run only after the documented old-release test-wallet setup and in-place upgrade", "true".equals(InstrumentationRegistry.getArguments().getString("check_upgrade")));
        UiDevice device=UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());assertEquals("1",device.executeShellCommand("getprop ro.kernel.qemu").trim());
        Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();WalletVault vault=new WalletVault(context);
        String expected=NativeCore.address(new byte[28]);assertEquals(expected,vault.metadata().getString("address"));assertTrue(context.getSharedPreferences("backup_status",Context.MODE_PRIVATE).getBoolean(expected,false));
        context.startActivity(new Intent(context,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK));
        UiObject2 receive=device.wait(Until.findObject(By.text(java.util.regex.Pattern.compile("↙  接收|接收"))),10000);assertNotNull(receive);receive.click();assertTrue(device.wait(Until.hasObject(By.text(expected)),10000));
        UiObject2 close=device.wait(Until.findObject(By.text("关闭")),10000);assertNotNull(close);close.click();
        UiObject2 settings=device.wait(Until.findObject(By.text(java.util.regex.Pattern.compile("⚙\\n设置|设置"))),10000);assertNotNull(settings);settings.click();
        for(int i=0;i<5&&!device.hasObject(By.text("查看离线备份"));i++)device.swipe(device.getDisplayWidth()/2,device.getDisplayHeight()*3/4,device.getDisplayWidth()/2,device.getDisplayHeight()/3,20);
        UiObject2 backup=device.wait(Until.findObject(By.text("查看离线备份")),10000);assertNotNull(backup);backup.click();
        UiObject2 pin=device.wait(Until.findObject(By.clazz("android.widget.EditText")),10000);assertNotNull(pin);pin.setText("24682468");device.pressEnter();
        UiObject2 password=device.wait(Until.findObject(By.text("钱包密码")),10000);assertNotNull(password);password.setText("Emulator-wallet-test-42");if(device.hasObject(By.pkg("com.google.android.inputmethod.latin")))device.pressBack();
        UiObject2 confirm=device.wait(Until.findObject(By.text("确认")),10000);assertNotNull(confirm);confirm.click();
        assertTrue(device.wait(Until.hasObject(By.text("离线助记词备份")),10000));device.pressBack();
    }
}
