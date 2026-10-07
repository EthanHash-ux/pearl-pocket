package ai.pearl.wallet;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.json.JSONArray;
import org.json.JSONObject;

/** Local annotations and exact-grain history export; no signing or recovery data. */
final class TransactionLedger {
  private final PublicStore.Storage store;

  TransactionLedger(PublicStore.Storage s) {
    store = s;
  }

  static void id(String id) {
    if (id == null || !id.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("交易 ID 无效");
  }

  String note(String address, String id) throws Exception {
    address = PearlAddress.normalize(address);
    id(id);
    synchronized (PublicStore.LOCK) {
      for (JSONObject v : rows())
        if (v.getString("address").equals(address) && v.getString("id").equals(id))
          return v.getString("note");
      return "";
    }
  }

  void note(String address, String id, String input) throws Exception {
    String a = PearlAddress.normalize(address);
    id(id);
    String n = input.trim();
    if (n.length() > 160 || n.codePoints().anyMatch(c -> Character.isISOControl(c)))
      throw new IllegalArgumentException("备注最多 160 个字符，不能含控制字符");
    synchronized (PublicStore.LOCK) {
      List<JSONObject> values = rows();
      values.removeIf(v -> v.optString("address").equals(a) && v.optString("id").equals(id));
      if (!n.isEmpty()) values.add(new JSONObject().put("address", a).put("id", id).put("note", n));
      if (values.size() > 2000) throw new IllegalArgumentException("本机备注最多保存 2000 条");
      JSONArray out = new JSONArray();
      for (JSONObject v : values) out.put(v);
      store.write("transaction_notes", out.toString());
    }
  }

  private List<JSONObject> rows() throws Exception {
    JSONArray a = new JSONArray(store.read("transaction_notes"));
    if (a.length() > 2000) throw new IllegalArgumentException("交易备注数据过大");
    List<JSONObject> r = new ArrayList<>();
    for (int i = 0; i < a.length(); i++) r.add(a.getJSONObject(i));
    return r;
  }

  static String cell(String value, boolean numeric) {
    String v = value == null ? "" : value;
    if (!(numeric && v.matches("-?[0-9]+(\\.[0-9]+)?"))
        && !v.isEmpty()
        && "=+-@".indexOf(v.trim().isEmpty() ? ' ' : v.trim().charAt(0)) >= 0) v = "'" + v;
    return "\"" + v.replace("\"", "\"\"") + "\"";
  }

  String csv(String address, List<PearlApi.Transaction> txs) throws Exception {
    String a = PearlAddress.normalize(address);
    StringBuilder out = new StringBuilder("address,txid,time_utc,confirmations,net_prl,note\r\n");
    for (PearlApi.Transaction tx : txs) {
      out.append(cell(a, false))
          .append(',')
          .append(cell(tx.id, false))
          .append(',')
          .append(
              cell(tx.time <= 0 ? "" : java.time.Instant.ofEpochSecond(tx.time).toString(), false))
          .append(',')
          .append(tx.confirmations)
          .append(',')
          .append(cell(PearlAmount.format(tx.netGrains), true))
          .append(',')
          .append(cell(note(a, tx.id), false))
          .append("\r\n");
    }
    return out.toString();
  }

  boolean matches(
      String address, PearlApi.Transaction tx, String query, String direction, long from, long to)
      throws Exception {
    String q = query.trim().toLowerCase(Locale.ROOT);
    return (direction.equals("all")
            || (direction.equals("received")
                ? tx.netGrains.signum() >= 0
                : tx.netGrains.signum() < 0))
        && (from == 0 || tx.time >= from)
        && (to == 0 || tx.time < to)
        && (q.isEmpty()
            || tx.id.contains(q)
            || tx.counterparties.toLowerCase(Locale.ROOT).contains(q)
            || PearlAmount.format(tx.netGrains).contains(q)
            || note(address, tx.id).toLowerCase(Locale.ROOT).contains(q));
  }
}
