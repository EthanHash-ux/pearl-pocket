package ai.pearl.wallet;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.AtomicFile;
import android.util.Base64;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import org.json.JSONArray;
import org.json.JSONObject;

/** Per-wallet API credential and journal, encrypted with a separate device key. */
final class InferenceStore {
  private static final Object LOCK = new Object();
  private final AtomicFile file;
  private final String slot, address, alias;

  InferenceStore(Context c, String slot, String address) {
    this.slot = WalletCatalog.validSlot(slot);
    this.address = PearlAddress.normalize(address);
    alias = "pearl-inference-" + slot;
    file = new AtomicFile(new File(WalletCatalog.directory(c, slot), "inference.enc"));
  }

  private SecretKey key(boolean create) throws Exception {
    KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
    ks.load(null);
    if (ks.containsAlias(alias)) return (SecretKey) ks.getKey(alias, null);
    if (!create) throw new IllegalArgumentException("API 凭据设备密钥不可用，请重新导入 API Key");
    KeyGenerator kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
    kg.init(
        new KeyGenParameterSpec.Builder(
                alias, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build());
    return kg.generateKey();
  }

  synchronized JSONObject read() throws Exception {
    synchronized (LOCK) {
      if (!file.getBaseFile().exists() && !new File(file.getBaseFile() + ".bak").exists())
        return null;
      byte[] bytes = file.readFully();
      if (bytes.length > 500000) throw new IllegalArgumentException("推理记录过大");
      JSONObject envelope = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(
          Cipher.DECRYPT_MODE,
          key(false),
          new GCMParameterSpec(128, Base64.decode(envelope.getString("iv"), Base64.NO_WRAP)));
      cipher.updateAAD((slot + ":" + address).getBytes(StandardCharsets.UTF_8));
      byte[] plain =
          cipher.doFinal(Base64.decode(envelope.getString("ciphertext"), Base64.NO_WRAP));
      try {
        JSONObject data = new JSONObject(new String(plain, StandardCharsets.UTF_8));
        if (!slot.equals(data.getString("slot")) || !address.equals(data.getString("address")))
          throw new IllegalArgumentException("推理账户属于另一个钱包");
        InferenceApi.base(data.getString("base"));
        InferenceApi.validateKey(data.getString("key"));
        return data;
      } finally {
        java.util.Arrays.fill(plain, (byte) 0);
      }
    }
  }

  synchronized void write(JSONObject data) throws Exception {
    synchronized (LOCK) {
      data.put("slot", slot).put("address", address);
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.ENCRYPT_MODE, key(true));
      cipher.updateAAD((slot + ":" + address).getBytes(StandardCharsets.UTF_8));
      byte[] plain = data.toString().getBytes(StandardCharsets.UTF_8), encoded;
      try {
        if (plain.length > 300000) throw new IllegalArgumentException("推理记录达到本机上限，请保留记录");
        encoded = cipher.doFinal(plain);
      } finally {
        java.util.Arrays.fill(plain, (byte) 0);
      }
      JSONObject env =
          new JSONObject()
              .put("iv", Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP))
              .put("ciphertext", Base64.encodeToString(encoded, Base64.NO_WRAP));
      FileOutputStream out = null;
      try {
        out = file.startWrite();
        out.write(env.toString().getBytes(StandardCharsets.UTF_8));
        file.finishWrite(out);
      } catch (Exception e) {
        if (out != null) file.failWrite(out);
        throw e;
      }
    }
  }

  synchronized void connect(String base, String key, JSONObject catalog) throws Exception {
    synchronized (LOCK) {
      if (read() != null) throw new IllegalArgumentException("当前钱包已有推理账户，请保留其付款与调用记录");
      write(
          new JSONObject()
              .put("base", InferenceApi.base(base))
              .put("key", key)
              .put("catalog", catalog)
              .put("invoices", new JSONArray())
              .put("calls", new JSONArray()));
    }
  }

  synchronized void invoice(JSONObject inv) throws Exception {
    synchronized (LOCK) {
      JSONObject data = read();
      JSONArray rows = data.getJSONArray("invoices");
      boolean found = false;
      for (int i = 0; i < rows.length(); i++)
        if (rows.getJSONObject(i).getString("id").equals(inv.getString("id"))) {
          JSONObject old = rows.getJSONObject(i);
          InferenceApi.unchanged(old, inv);
          if (old.has("local_txid")) inv.put("local_txid", old.getString("local_txid"));
          rows.put(i, inv);
          found = true;
          break;
        }
      if (!found) {
        if (rows.length() >= 100) throw new IllegalArgumentException("本机最多保存 100 笔推理账单");
        rows.put(inv);
      }
      write(data);
    }
  }

  synchronized JSONObject invoice(String id) throws Exception {
    synchronized (LOCK) {
      JSONArray rows = read().getJSONArray("invoices");
      for (int i = 0; i < rows.length(); i++)
        if (rows.getJSONObject(i).getString("id").equals(id)) return rows.getJSONObject(i);
      throw new IllegalArgumentException("账单未在本钱包保存");
    }
  }

  synchronized void sent(String id, String txid) throws Exception {
    synchronized (LOCK) {
      JSONObject inv = invoice(id);
      if (inv.has("local_txid")) {
        if (inv.getString("local_txid").equals(txid)) return;
        throw new IllegalArgumentException("这笔账单已有付款交易，请查询原交易");
      }
      inv.put("local_txid", txid);
      invoice(inv);
    }
  }

  synchronized void call(String id, String invoice, String status) throws Exception {
    synchronized (LOCK) {
      JSONObject data = read();
      JSONArray calls = data.getJSONArray("calls");
      boolean found = false;
      for (int i = 0; i < calls.length(); i++)
        if (calls.getJSONObject(i).getString("id").equals(id)) {
          calls.getJSONObject(i).put("status", status);
          found = true;
          break;
        }
      if (!found) {
        if (calls.length() >= 100) throw new IllegalArgumentException("本机调用记录已满，请保留查询凭据");
        calls.put(new JSONObject().put("id", id).put("invoice", invoice).put("status", status));
      }
      write(data);
    }
  }
}
