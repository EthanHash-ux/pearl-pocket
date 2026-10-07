package ai.pearl.wallet;

import android.app.KeyguardManager;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.util.Base64;
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
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class WalletInstrumentedTest {
    private Context context() { return InstrumentationRegistry.getInstrumentation().getTargetContext(); }
    private Context sandbox() {
        final File dir = new File(context().getCacheDir(), "vault-test-" + System.nanoTime()); assertTrue(dir.mkdirs());
        return new ContextWrapper(context()) { @Override public File getNoBackupFilesDir() { return dir; } };
    }
    private void authenticate() throws Exception {
        UiDevice device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        KeyguardManager keyguard = (KeyguardManager) context().getSystemService(Context.KEYGUARD_SERVICE);
        assertTrue("Set the emulator test PIN before running instrumentation", keyguard.isDeviceSecure());
        Intent intent = keyguard.createConfirmDeviceCredentialIntent("Pearl vault instrumentation", "Test device authentication");
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); context().startActivity(intent);
        UiObject2 entry = device.wait(Until.findObject(By.clazz("android.widget.EditText")), 10000);
        assertNotNull("System PIN entry did not appear", entry); entry.setText("24682468"); device.pressEnter();
        assertTrue(device.wait(Until.gone(By.text("Pearl vault instrumentation")), 10000));
        Thread.sleep(500);
    }
    @Test public void nativeRecoveryVectorAndRandomGeneration() throws Exception {
        byte[] entropy = new byte[16];
        JSONObject identity = NativeCore.identity(entropy);
        assertEquals("prl1pr6yuq8u2r95wjzzgpdy8cpnncpl7l8zgy6x5q0367pnc53s2famqg7pt74", identity.getString("address"));
        JSONObject restored = NativeCore.call(new JSONObject().put("action", "import").put("mnemonic", identity.getString("mnemonic")));
        assertEquals(identity.getString("address"), restored.getString("address"));
        JSONObject a = NativeCore.call(new JSONObject().put("action", "generate")), b = NativeCore.call(new JSONObject().put("action", "generate"));
        assertNotEquals(a.getString("address"), b.getString("address")); assertEquals(24, a.getString("mnemonic").split(" ").length);
    }
    @Test public void nativeQuoteSignAndPendingRestartRecovery() throws Exception {
        String fixture;
        try (java.io.InputStream in = InstrumentationRegistry.getInstrumentation().getContext().getAssets().open("signing-fixture.json")) {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(); byte[] buffer = new byte[4096]; int size;
            while ((size = in.read(buffer)) != -1) out.write(buffer,0,size); fixture = out.toString("UTF-8");
        }
        JSONObject data = new JSONObject(fixture), quote = NativeCore.call(new JSONObject().put("action", "plan").put("payment", data.getJSONObject("payment")));
        byte[] entropy = Base64.decode(data.getString("entropy"), Base64.NO_WRAP);
        JSONObject signed = NativeCore.sign(entropy, quote); Arrays.fill(entropy, (byte)0);
        assertEquals(quote.getString("fee"), signed.getString("fee")); assertEquals(quote.getInt("vsize"), signed.getInt("vsize"));
        assertEquals(signed.getString("txid"), NativeCore.call(new JSONObject().put("action", "transaction").put("raw", signed.getString("raw"))).getString("txid"));
        Context sandbox = sandbox(); PendingTransfer pending = new PendingTransfer(sandbox); pending.save(signed);
        PendingTransfer restarted = new PendingTransfer(sandbox); assertEquals(signed.getString("raw"), restarted.load().getString("raw"));
        assertThrows(IllegalArgumentException.class, () -> restarted.save(signed));
        java.util.concurrent.atomic.AtomicInteger attempts = new java.util.concurrent.atomic.AtomicInteger();
        PearlApi network = new PearlApi(url -> "{}", (url,raw) -> {
            assertEquals(signed.getString("raw"),raw);
            if (attempts.incrementAndGet() == 1) throw new java.io.IOException("simulated response timeout");
            return new JSONObject().put("result",signed.getString("txid")).toString();
        });
        assertThrows(java.io.IOException.class, () -> network.broadcast(restarted.load()));
        assertEquals(signed.getString("txid"), network.broadcast(new PendingTransfer(sandbox).load()));
        assertEquals(2,attempts.get()); restarted.clear(); assertNull(restarted.load());
        quote.put("fee", "0"); byte[] fixed = new byte[32]; assertThrows(IllegalArgumentException.class, () -> NativeCore.sign(fixed, quote));
    }
    @Test public void passwordCipherWrongPasswordAndTamperingRejected() throws Exception {
        char[] password = "Test-wallet-password-42".toCharArray(); byte[] salt = WalletVault.random(32), iv = WalletVault.random(12), aad = "bound metadata".getBytes(StandardCharsets.UTF_8), entropy = new byte[32];
        byte[] key = WalletVault.passwordKey(password, salt);
        byte[] encrypted = WalletVault.crypt(Cipher.ENCRYPT_MODE, new SecretKeySpec(key,"AES"),iv,aad,entropy);
        assertArrayEquals(entropy, WalletVault.crypt(Cipher.DECRYPT_MODE,new SecretKeySpec(key,"AES"),iv,aad,encrypted));
        byte[] wrong = WalletVault.passwordKey("Wrong-password-42".toCharArray(),salt);
        assertThrows(javax.crypto.AEADBadTagException.class, () -> WalletVault.crypt(Cipher.DECRYPT_MODE,new SecretKeySpec(wrong,"AES"),iv,aad,encrypted));
        encrypted[0] ^= 1; assertThrows(javax.crypto.AEADBadTagException.class, () -> WalletVault.crypt(Cipher.DECRYPT_MODE,new SecretKeySpec(key,"AES"),iv,aad,encrypted));
        Arrays.fill(key,(byte)0); Arrays.fill(wrong,(byte)0); Arrays.fill(password,'\0');
    }
    @Test public void authenticatedKeystoreVaultPersistsAndRejectsWrongPassword() throws Exception {
        authenticate(); Context sandbox = sandbox(); WalletVault vault = new WalletVault(sandbox);
        byte[] entropy = new byte[32]; String address = NativeCore.identity(entropy).getString("address"); char[] password = "Test-wallet-password-42".toCharArray();
        try {
            vault.create(entropy,password,address); WalletVault restarted = new WalletVault(sandbox);
            assertEquals(address,restarted.metadata().getString("address")); assertArrayEquals(entropy,restarted.unlock(password));
            assertThrows(IllegalArgumentException.class, () -> restarted.unlock("Wrong-password-42".toCharArray()));
            String persisted = new String(java.nio.file.Files.readAllBytes(new File(sandbox.getNoBackupFilesDir(),"wallet-v1.json").toPath()), StandardCharsets.UTF_8);
            assertFalse(persisted.contains("mnemonic")); assertFalse(persisted.contains("abandon")); assertFalse(persisted.contains("Test-wallet-password"));
            JSONObject corrupt = new JSONObject(persisted); corrupt.put("address", "prl1pr6yuq8u2r95wjzzgpdy8cpnncpl7l8zgy6x5q0367pnc53s2famqg7pt74");
            java.nio.file.Files.write(new File(sandbox.getNoBackupFilesDir(),"wallet-v1.json").toPath(),corrupt.toString().getBytes(StandardCharsets.UTF_8));
            assertThrows(IllegalArgumentException.class, () -> restarted.unlock(password));
        } finally {
            if (vault.exists()) { JSONObject meta = new JSONObject(new String(java.nio.file.Files.readAllBytes(new File(sandbox.getNoBackupFilesDir(),"wallet-v1.json").toPath()), StandardCharsets.UTF_8)); KeyStore store = KeyStore.getInstance("AndroidKeyStore"); store.load(null); store.deleteEntry("pearl-wallet-"+meta.getString("id")); }
            Arrays.fill(entropy,(byte)0); Arrays.fill(password,'\0');
        }
    }
}
