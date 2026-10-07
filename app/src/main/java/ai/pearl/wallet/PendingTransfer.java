package ai.pearl.wallet;

import android.content.Context;
import android.util.AtomicFile;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

/** Persist before any network submission; retry the exact same signed transaction. */
final class PendingTransfer {
    private final AtomicFile file;
    private final String slot;
    PendingTransfer(Context c) { this(c, WalletCatalog.LEGACY); }
    PendingTransfer(Context c, String slot) { this.slot = WalletCatalog.validSlot(slot); file = new AtomicFile(new File(WalletCatalog.directory(c, slot), "pending-transfer.json")); }
    synchronized JSONObject load() throws Exception {
        if (!file.getBaseFile().exists() && !new File(file.getBaseFile() + ".bak").exists()) return null;
        byte[] raw = file.readFully(); if (raw.length > 100_000) throw new IllegalArgumentException("待确认交易文件损坏");
        JSONObject record = new JSONObject(new String(raw, StandardCharsets.UTF_8));
        if (!slot.equals(record.optString("walletSlot", WalletCatalog.LEGACY))) throw new IllegalArgumentException("待确认交易属于另一个钱包");
        if (!record.getString("txid").matches("[0-9a-f]{64}") || !record.getString("raw").matches("[0-9a-f]+")) throw new IllegalArgumentException("待确认交易数据无效");
        if (!record.getString("txid").equals(NativeCore.call(new JSONObject().put("action", "transaction").put("raw", record.getString("raw"))).getString("txid"))) throw new IllegalArgumentException("待确认交易校验失败");
        return record;
    }
    synchronized void save(JSONObject record) throws Exception {
        if (load() != null) throw new IllegalArgumentException("已有一笔交易等待确认");
        FileOutputStream out = null;
        try { out = file.startWrite(); out.write(new JSONObject(record.toString()).put("walletSlot", slot).toString().getBytes(StandardCharsets.UTF_8)); file.finishWrite(out); }
        catch (Exception e) { if (out != null) file.failWrite(out); throw e; }
    }
    synchronized void clear() { file.delete(); }
}
