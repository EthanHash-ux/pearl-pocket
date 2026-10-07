package ai.pearl.wallet;

import java.util.*;
import org.json.*;

final class ReceiptTracker {
  static final class Event {
    final PearlApi.Transaction tx;
    final boolean confirmed;

    Event(PearlApi.Transaction t, boolean c) {
      tx = t;
      confirmed = c;
    }
  }

  private final PublicStore.Storage store;

  ReceiptTracker(PublicStore.Storage s) {
    store = s;
  }

  List<Event> claim(
      String input, List<PearlApi.Transaction> transactions, boolean available, long now)
      throws Exception {
    String address = PearlAddress.normalize(input);
    List<Event> events = new ArrayList<>();
    if (!available) return events;
    synchronized (PublicStore.LOCK) {
      JSONArray all = new JSONArray(store.read("receipt_states"));
      JSONObject prior = null;
      int index = -1;
      for (int i = 0; i < all.length(); i++)
        if (all.getJSONObject(i).getString("address").equals(address)) {
          prior = all.getJSONObject(i);
          index = i;
          break;
        }
      Map<String, Integer> seen = new LinkedHashMap<>();
      if (prior != null) {
        JSONArray rows = prior.getJSONArray("seen");
        for (int i = 0; i < rows.length(); i++) {
          JSONObject r = rows.getJSONObject(i);
          seen.put(r.getString("id"), r.getInt("confirmed"));
        }
      }
      for (PearlApi.Transaction tx : transactions) {
        TransactionLedger.id(tx.id);
        if (tx.netGrains.signum() <= 0) continue;
        int confirmed = tx.confirmations > 0 ? 1 : 0;
        Integer old = seen.get(tx.id);
        if (prior != null && (old == null || old == 0 && confirmed == 1)) {
          if (tx.time == 0 || tx.time >= prior.getLong("since"))
            events.add(new Event(tx, confirmed == 1));
        }
        seen.put(tx.id, Math.max(confirmed, old == null ? 0 : old));
      }
      while (seen.size() > 500) seen.remove(seen.keySet().iterator().next());
      JSONArray rows = new JSONArray();
      for (Map.Entry<String, Integer> e : seen.entrySet())
        rows.put(new JSONObject().put("id", e.getKey()).put("confirmed", e.getValue()));
      JSONObject updated =
          new JSONObject()
              .put("address", address)
              .put("since", prior == null ? now : prior.getLong("since"))
              .put("seen", rows);
      if (index < 0) {
        if (all.length() >= 21) all.remove(0);
        all.put(updated);
      } else all.put(index, updated);
      store.write("receipt_states", all.toString());
    }
    return events;
  }
}
