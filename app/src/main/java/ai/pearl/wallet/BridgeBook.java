package ai.pearl.wallet;

import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/** Public address pins and transaction IDs only. Persist before broadcasting. */
final class BridgeBook {
  private final PublicStore.Storage store;

  BridgeBook(PublicStore.Storage store) {
    this.store = store;
  }

  boolean pin(String eth, String deposit) throws Exception {
    eth = EvmPublicApi.address(eth);
    deposit = PearlAddress.normalize(deposit);
    synchronized (PublicStore.LOCK) {
      JSONArray pins = new JSONArray(store.read("bridge_deposit_pins"));
      for (int i = 0; i < pins.length(); i++) {
        JSONObject o = pins.getJSONObject(i);
        if (eth.equals(o.getString("eth"))) {
          if (!deposit.equals(o.getString("deposit")))
            throw new IllegalArgumentException("桥充值地址与本机首次记录不一致，已阻止发送");
          return false;
        }
      }
      if (pins.length() >= 100) throw new IllegalArgumentException("桥地址记录已满");
      pins.put(new JSONObject().put("eth", eth).put("deposit", deposit));
      store.write("bridge_deposit_pins", pins.toString());
      return true;
    }
  }

  static String hash(boolean mint, String hash) {
    if (hash == null || !hash.matches(mint ? "[0-9a-f]{64}" : "0x[0-9a-fA-F]{64}"))
      throw new IllegalArgumentException(mint ? "请输入 Pearl 交易 ID" : "请输入 Ethereum 交易哈希");
    return hash.toLowerCase(java.util.Locale.ROOT);
  }

  static final class Entry {
    final boolean mint;
    final String hash, eth;

    Entry(boolean m, String h, String e) {
      mint = m;
      hash = BridgeBook.hash(m, h);
      eth = e.isEmpty() ? "" : EvmPublicApi.address(e);
    }
  }

  List<Entry> list() throws Exception {
    synchronized (PublicStore.LOCK) {
      JSONArray all = new JSONArray(store.read("bridge_transfers"));
      if (all.length() > 100) throw new IllegalArgumentException("桥记录无效");
      List<Entry> entries = new ArrayList<>();
      for (int i = all.length() - 1; i >= 0; i--) {
        JSONObject o = all.getJSONObject(i);
        entries.add(
            new Entry(BridgeApi.bool(o, "mint"), o.getString("hash"), o.optString("eth", "")));
      }
      return entries;
    }
  }

  void save(boolean mint, String hash, String eth) throws Exception {
    Entry entry = new Entry(mint, hash, eth);
    synchronized (PublicStore.LOCK) {
      JSONArray all = new JSONArray(store.read("bridge_transfers"));
      for (int i = 0; i < all.length(); i++)
        if (entry.hash.equals(all.getJSONObject(i).getString("hash"))) return;
      if (all.length() >= 100) throw new IllegalArgumentException("跨链记录已满，请先保存现有记录");
      all.put(new JSONObject().put("mint", mint).put("hash", entry.hash).put("eth", entry.eth));
      store.write("bridge_transfers", all.toString());
    }
  }
}
