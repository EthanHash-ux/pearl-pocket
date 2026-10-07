package ai.pearl.wallet;

import android.content.Context;
import android.util.AtomicFile;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;

/** Public address cache only. The signer independently derives and checks its source. */
final class EthereumAccount {
    static final String PATH = "m/44'/60'/0'/0/0";
    private final AtomicFile file;
    private final String slot;
    EthereumAccount(Context c, String slot) {
        this.slot = WalletCatalog.validSlot(slot);
        file = new AtomicFile(new File(WalletCatalog.directory(c, slot), "ethereum-address.json"));
    }
    synchronized String load(String pearlAddress) throws Exception {
        if (!file.getBaseFile().exists() && !new File(file.getBaseFile() + ".bak").exists()) return "";
        byte[] bytes = file.readFully();
        if (bytes.length > 2048) throw new IllegalArgumentException("Ethereum 地址记录损坏");
        JSONObject p = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
        if (!slot.equals(p.getString("walletSlot")) || !pearlAddress.equals(p.getString("pearlAddress")) || !PATH.equals(p.getString("path")))
            throw new IllegalArgumentException("Ethereum 地址记录与钱包不一致");
        return EvmPublicApi.checkedAddress(p.getString("address"));
    }
    synchronized void save(String pearlAddress, JSONObject identity) throws Exception {
        if (!PATH.equals(identity.getString("path"))) throw new IllegalArgumentException("Ethereum 派生路径无效");
        JSONObject p = new JSONObject().put("walletSlot", slot).put("pearlAddress", PearlAddress.normalize(pearlAddress))
                .put("path", PATH).put("address", EvmPublicApi.checkedAddress(identity.getString("address")));
        FileOutputStream out = null;
        try { out = file.startWrite(); out.write(p.toString().getBytes(StandardCharsets.UTF_8)); file.finishWrite(out); }
        catch (Exception e) { if (out != null) file.failWrite(out); throw e; }
    }
}
