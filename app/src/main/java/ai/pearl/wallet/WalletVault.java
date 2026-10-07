package ai.pearl.wallet;

import android.content.Context;
import android.os.Build;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.AtomicFile;
import android.util.Base64;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.UUID;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

/** Password encryption is wrapped by a device-authenticated Android Keystore key.
 * Only BIP39 entropy is persisted. The signing key is derived for each operation. */
final class WalletVault {
    static final int ITERATIONS = 600_000;
    private final AtomicFile file;
    private final String slot;
    WalletVault(Context context) { this(context, WalletCatalog.LEGACY); }
    WalletVault(Context context, String slot) { this.slot = WalletCatalog.validSlot(slot); file = new AtomicFile(new File(WalletCatalog.directory(context, slot), "wallet-v1.json")); }
    boolean exists() { return file.getBaseFile().exists() || new File(file.getBaseFile() + ".bak").exists(); }
    synchronized JSONObject metadata() throws Exception {
        byte[] bytes = file.readFully();
        if (bytes.length > 16_384) throw new IllegalArgumentException("钱包文件无效");
        JSONObject json = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
        if (json.getInt("version") != 1 || !"m/86'/808276'/0'/0/0".equals(json.getString("path"))) throw new IllegalArgumentException("钱包版本不兼容");
        WalletCatalog.validSlot(json.getString("id"));
        if (!slot.equals(json.optString("walletSlot", WalletCatalog.LEGACY))) throw new IllegalArgumentException("钱包文件与所选钱包不匹配");
        PearlAddress.normalize(json.getString("address")); return json;
    }
    static byte[] random(int size) { byte[] result = new byte[size]; new SecureRandom().nextBytes(result); return result; }
    static String b64(byte[] b) { return Base64.encodeToString(b, Base64.NO_WRAP); }
    static byte[] decode(String s) { return Base64.decode(s, Base64.NO_WRAP); }
    static byte[] aad(JSONObject m) throws Exception {
        return (m.getInt("version") + "|" + m.getString("id") + "|" + m.getString("address") + "|" + m.getString("path")
                + (m.has("walletSlot") ? "|" + m.getString("walletSlot") : "")).getBytes(StandardCharsets.UTF_8);
    }
    static byte[] passwordKey(char[] password, byte[] salt) throws Exception {
        PBEKeySpec spec = new PBEKeySpec(password, salt, ITERATIONS, 256);
        try { return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded(); }
        finally { spec.clearPassword(); }
    }
    static byte[] crypt(int mode, SecretKey key, byte[] iv, byte[] aad, byte[] input) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(mode, key, new GCMParameterSpec(128, iv)); cipher.updateAAD(aad); return cipher.doFinal(input);
    }
    private static String alias(JSONObject json) throws Exception { return "pearl-wallet-" + json.getString("id"); }
    private static SecretKey deviceKey(JSONObject json) throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore"); store.load(null);
        SecretKey key = (SecretKey) store.getKey(alias(json), null);
        if (key == null) throw new IllegalArgumentException("设备密钥已失效，请用离线助记词在另一台手机恢复"); return key;
    }
    private static SecretKey newDeviceKey(JSONObject json) throws Exception {
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        KeyGenParameterSpec.Builder builder = new KeyGenParameterSpec.Builder(alias(json), KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(256).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setUserAuthenticationRequired(true).setUserAuthenticationValidityDurationSeconds(300);
        if (Build.VERSION.SDK_INT >= 28) {
            try { generator.init(builder.setIsStrongBoxBacked(true).build()); return generator.generateKey(); }
            catch (java.security.GeneralSecurityException | android.security.keystore.StrongBoxUnavailableException unavailable) { builder.setIsStrongBoxBacked(false); }
        }
        generator.init(builder.build()); return generator.generateKey();
    }
    synchronized void create(byte[] entropy, char[] password, String address) throws Exception {
        if (exists()) throw new IllegalArgumentException("这个钱包已经保存");
        if (password.length < 10) throw new IllegalArgumentException("钱包密码至少需要 10 个字符");
        JSONObject m = new JSONObject().put("version", 1).put("id", UUID.randomUUID().toString()).put("address", PearlAddress.normalize(address)).put("path", "m/86'/808276'/0'/0/0");
        if (!WalletCatalog.LEGACY.equals(slot)) m.put("walletSlot", slot);
        byte[] salt = random(32), innerIv = random(12), derived = null, inner = null;
        boolean saved = false;
        try {
            if (!address.equals(NativeCore.address(entropy))) throw new IllegalArgumentException("助记词和地址不匹配");
            derived = passwordKey(password, salt);
            inner = crypt(Cipher.ENCRYPT_MODE, new SecretKeySpec(derived, "AES"), innerIv, aad(m), entropy);
            byte[] envelope = new JSONObject().put("salt", b64(salt)).put("iv", b64(innerIv)).put("cipher", b64(inner)).toString().getBytes(StandardCharsets.UTF_8);
            try {
                Cipher outer = Cipher.getInstance("AES/GCM/NoPadding"); outer.init(Cipher.ENCRYPT_MODE, newDeviceKey(m)); outer.updateAAD(aad(m));
                m.put("deviceIv", b64(outer.getIV())).put("deviceCipher", b64(outer.doFinal(envelope)));
                write(m); saved = true;
            } finally { Arrays.fill(envelope, (byte)0); }
        } finally {
            if (derived != null) Arrays.fill(derived, (byte)0); if (inner != null) Arrays.fill(inner, (byte)0);
            if (!saved) { KeyStore store = KeyStore.getInstance("AndroidKeyStore"); store.load(null); store.deleteEntry(alias(m)); }
        }
    }
    synchronized byte[] unlock(char[] password) throws Exception {
        JSONObject m = metadata(); byte[] envelope = null, key = null, entropy = null;
        try {
            envelope = crypt(Cipher.DECRYPT_MODE, deviceKey(m), decode(m.getString("deviceIv")), aad(m), decode(m.getString("deviceCipher")));
            JSONObject inner = new JSONObject(new String(envelope, StandardCharsets.UTF_8));
            key = passwordKey(password, decode(inner.getString("salt")));
            entropy = crypt(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), decode(inner.getString("iv")), aad(m), decode(inner.getString("cipher")));
            if (!m.getString("address").equals(NativeCore.address(entropy))) throw new IllegalArgumentException("钱包文件校验失败");
            byte[] result = entropy; entropy = null; return result;
        } catch (javax.crypto.AEADBadTagException error) { throw new IllegalArgumentException("密码错误或钱包文件已损坏"); }
        finally { if (envelope != null) Arrays.fill(envelope, (byte)0); if (key != null) Arrays.fill(key, (byte)0); if (entropy != null) Arrays.fill(entropy, (byte)0); }
    }
    private void write(JSONObject json) throws Exception {
        FileOutputStream out = null;
        try { out = file.startWrite(); out.write(json.toString().getBytes(StandardCharsets.UTF_8)); file.finishWrite(out); }
        catch (Exception e) { if (out != null) file.failWrite(out); throw e; }
    }
}
