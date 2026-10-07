package ai.pearl.wallet;

import android.content.Context;
import android.util.AtomicFile;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;

/** One immutable signed Ethereum transaction per wallet, saved before submission. */
final class PendingEthereum {
    private final AtomicFile file;
    private final String slot, from;
    PendingEthereum(Context c, String slot, String from) {
        this.slot = WalletCatalog.validSlot(slot); this.from = EvmPublicApi.address(from);
        file = new AtomicFile(new File(WalletCatalog.directory(c, slot), "pending-ethereum.json"));
    }
    private JSONObject validate(JSONObject record) throws Exception {
        if (!slot.equals(record.getString("walletSlot"))) throw new IllegalArgumentException("DeFi 交易属于另一个钱包");
        JSONObject decoded = NativeCore.call(new JSONObject().put("action", "ethtransaction").put("raw", record.getString("raw")));
        if (!from.equals(EvmPublicApi.address(decoded.getString("from"))) || !decoded.getString("hash").equals(record.getString("hash")))
            throw new IllegalArgumentException("DeFi 交易签名或来源不匹配");
        return decoded.put("walletSlot", slot);
    }
    synchronized JSONObject load() throws Exception {
        if (!file.getBaseFile().exists() && !new File(file.getBaseFile() + ".bak").exists()) return null;
        byte[] bytes = file.readFully(); if (bytes.length > 8192) throw new IllegalArgumentException("DeFi 待确认记录损坏");
        return validate(new JSONObject(new String(bytes, StandardCharsets.UTF_8)));
    }
    synchronized void save(JSONObject signed) throws Exception {
        if (load() != null) throw new IllegalArgumentException("已有 Ethereum 交易等待确认");
        JSONObject record = validate(new JSONObject(signed.toString()).put("walletSlot", slot));
        FileOutputStream out = null;
        try { out = file.startWrite(); out.write(record.toString().getBytes(StandardCharsets.UTF_8)); file.finishWrite(out); }
        catch (Exception e) { if (out != null) file.failWrite(out); throw e; }
    }
    synchronized void clear() { file.delete(); }
}
