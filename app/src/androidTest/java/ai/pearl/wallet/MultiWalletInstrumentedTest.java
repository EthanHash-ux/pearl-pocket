package ai.pearl.wallet;

import android.app.KeyguardManager;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.By;
import androidx.test.uiautomator.UiDevice;
import androidx.test.uiautomator.Until;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.KeyStore;
import java.util.Arrays;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class MultiWalletInstrumentedTest {
    private Context sandbox() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File dir = new File(context.getCacheDir(), "multi-wallet-" + System.nanoTime()); assertTrue(dir.mkdirs());
        return new ContextWrapper(context) { @Override public File getNoBackupFilesDir() { return dir; } };
    }

    private void authenticate(Context context) throws Exception {
        UiDevice device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        KeyguardManager keyguard = context.getSystemService(KeyguardManager.class);
        assertTrue("Disposable emulator must have the documented test PIN", keyguard.isDeviceSecure());
        context.startActivity(keyguard.createConfirmDeviceCredentialIntent("Multi-wallet test", "Test device authentication")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        androidx.test.uiautomator.UiObject2 entry = device.wait(Until.findObject(By.clazz("android.widget.EditText")), 10000);
        assertNotNull(entry); entry.setText("24682468"); device.pressEnter();
        assertTrue(device.wait(Until.gone(By.text("Multi-wallet test")), 10000));
    }

    private File vaultFile(Context context, String slot) {
        return new File(WalletCatalog.directory(context, slot), "wallet-v1.json");
    }

    @Test public void independentPasswordsSelectionAndLegacyVaultSurviveRestartAndTampering() throws Exception {
        Context context = sandbox(); authenticate(context);
        WalletCatalog catalog = new WalletCatalog(context); String slot = catalog.newSlot();
        WalletVault legacy = new WalletVault(context), second = new WalletVault(context, slot);
        byte[] a = new byte[16], b = new byte[32]; b[0] = 7;
        char[] firstPassword = "Legacy-wallet-password-42".toCharArray(), secondPassword = "Second-wallet-password-57".toCharArray();
        java.util.List<String> aliases = new java.util.ArrayList<>();
        try {
            String firstAddress = NativeCore.address(a), secondAddress = NativeCore.address(b);
            legacy.create(a, firstPassword, firstAddress); aliases.add(legacy.metadata().getString("id"));
            byte[] original = Files.readAllBytes(vaultFile(context, WalletCatalog.LEGACY).toPath());
            catalog.checkNewAddress(slot, secondAddress); second.create(b, secondPassword, secondAddress);
            aliases.add(second.metadata().getString("id"));
            assertNotEquals(aliases.get(0), aliases.get(1)); assertEquals(2, catalog.list().size());
            assertEquals(WalletCatalog.LEGACY, catalog.active());
            catalog.rename(slot, "储蓄钱包"); catalog.select(slot);
            WalletCatalog restarted = new WalletCatalog(context);
            assertEquals(slot, restarted.active()); assertEquals("储蓄钱包", restarted.name(slot));
            assertArrayEquals("Legacy file must stay byte-for-byte unchanged", original, Files.readAllBytes(vaultFile(context, WalletCatalog.LEGACY).toPath()));
            byte[] unlockedA = legacy.unlock(firstPassword), unlockedB = new WalletVault(context, restarted.active()).unlock(secondPassword);
            try { assertArrayEquals(a, unlockedA); assertArrayEquals(b, unlockedB); }
            finally { Arrays.fill(unlockedA, (byte)0); Arrays.fill(unlockedB, (byte)0); }
            assertThrows(IllegalArgumentException.class, () -> second.unlock(firstPassword));
            assertThrows(IllegalArgumentException.class, () -> legacy.unlock(secondPassword));
            assertThrows(IllegalArgumentException.class, () -> catalog.checkNewAddress(catalog.newSlot(), firstAddress));
            byte[] secondFile = Files.readAllBytes(vaultFile(context, slot).toPath());
            Files.write(vaultFile(context, slot).toPath(), original);
            assertThrows(IllegalArgumentException.class, second::metadata);
            assertEquals("Unreadable selected wallet must not select another wallet", slot, restarted.active());
            assertFalse(restarted.list().get(1).readable);
            Files.write(vaultFile(context, slot).toPath(), secondFile);
            JSONObject tampered = new JSONObject(new String(secondFile, StandardCharsets.UTF_8));
            tampered.put("walletSlot", WalletCatalog.LEGACY);
            Files.write(vaultFile(context, WalletCatalog.LEGACY).toPath(), tampered.toString().getBytes(StandardCharsets.UTF_8));
            assertThrows(IllegalArgumentException.class, () -> legacy.unlock(firstPassword));
            Files.write(vaultFile(context, WalletCatalog.LEGACY).toPath(), original);
            // Lost public names/selection cannot orphan encrypted wallet files.
            assertTrue(new File(context.getNoBackupFilesDir(), "wallet-catalog.json").delete());
            assertEquals(2, new WalletCatalog(context).list().size());
            assertThrows(IllegalArgumentException.class, () -> WalletCatalog.directory(context, "../wallet-v1.json"));
        } finally {
            KeyStore keys = KeyStore.getInstance("AndroidKeyStore"); keys.load(null);
            for (String id : aliases) keys.deleteEntry("pearl-wallet-" + id);
            Arrays.fill(a, (byte)0); Arrays.fill(b, (byte)0); Arrays.fill(firstPassword, '\0'); Arrays.fill(secondPassword, '\0');
        }
    }

    @Test public void pendingTransactionsStayWithTheirWalletAndLegacyRawRetryStillWorks() throws Exception {
        Context context = sandbox(); String first = new WalletCatalog(context).newSlot(), second = new WalletCatalog(context).newSlot();
        JSONObject fixture;
        try (java.io.InputStream in = InstrumentationRegistry.getInstrumentation().getContext().getAssets().open("signing-fixture.json")) {
            fixture = new JSONObject(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
        JSONObject quote = NativeCore.call(new JSONObject().put("action", "plan").put("payment", fixture.getJSONObject("payment")));
        byte[] entropy = WalletVault.decode(fixture.getString("entropy")); JSONObject signed;
        try { signed = NativeCore.sign(entropy, quote); } finally { Arrays.fill(entropy, (byte)0); }
        assertThrows(IllegalArgumentException.class, () -> NativeCore.sign(new byte[16], quote));
        PendingTransfer legacy = new PendingTransfer(context), a = new PendingTransfer(context, first), b = new PendingTransfer(context, second);
        legacy.save(signed); a.save(signed); assertNull(b.load());
        b.save(signed); b.clear(); assertEquals(signed.getString("raw"), a.load().getString("raw"));
        assertEquals(signed.getString("raw"), new PendingTransfer(context).load().getString("raw"));
        File aFile = new File(WalletCatalog.directory(context, first), "pending-transfer.json"), bFile = new File(WalletCatalog.directory(context, second), "pending-transfer.json");
        Files.copy(aFile.toPath(), bFile.toPath()); assertThrows(IllegalArgumentException.class, b::load);
        File legacyFile = new File(context.getNoBackupFilesDir(), "pending-transfer.json");
        JSONObject old = legacy.load(); old.remove("walletSlot"); Files.write(legacyFile.toPath(), old.toString().getBytes(StandardCharsets.UTF_8));
        assertEquals(signed.getString("raw"), new PendingTransfer(context).load().getString("raw"));
        Files.write(bFile.toPath(), old.toString().getBytes(StandardCharsets.UTF_8)); assertThrows(IllegalArgumentException.class, b::load);
        a.clear(); assertNotNull(legacy.load());
    }
}
