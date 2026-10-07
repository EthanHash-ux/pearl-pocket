package ai.pearl.wallet;

import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/** Public addresses only. A watched address can never become a signing identity. */
final class WatchBook {
  static final class Entry {
    final String name, address;

    Entry(String n, String a) {
      name = n;
      address = a;
    }
  }

  private final PublicStore.Storage store;
  private final String key;

  WatchBook(PublicStore.Storage s) {
    this(s, "watch_addresses");
  }

  WatchBook(PublicStore.Storage s, String key) {
    store = s;
    this.key = key;
  }

  List<Entry> list() throws Exception {
    synchronized (PublicStore.LOCK) {
      JSONArray a = new JSONArray(store.read(key));
      if (a.length() > 20) throw new IllegalArgumentException("观察地址最多 20 个");
      List<Entry> result = new ArrayList<>();
      for (int i = 0; i < a.length(); i++) {
        JSONObject v = a.getJSONObject(i);
        result.add(
            new Entry(
                AddressBook.name(v.getString("name")),
                PearlAddress.normalize(v.getString("address"))));
      }
      return result;
    }
  }

  void save(String name, String address) throws Exception {
    String n = AddressBook.name(name), a = PearlAddress.normalize(address);
    synchronized (PublicStore.LOCK) {
      List<Entry> entries = list();
      for (Entry e : entries)
        if (e.address.equals(a)) throw new IllegalArgumentException("该观察地址已经存在");
      if (entries.size() >= 20) throw new IllegalArgumentException("观察地址最多 20 个");
      entries.add(new Entry(n, a));
      write(entries);
    }
  }

  void remove(String address) throws Exception {
    synchronized (PublicStore.LOCK) {
      List<Entry> entries = list();
      entries.removeIf(e -> e.address.equals(address));
      write(entries);
    }
  }

  private void write(List<Entry> entries) throws Exception {
    JSONArray a = new JSONArray();
    for (Entry e : entries) a.put(new JSONObject().put("name", e.name).put("address", e.address));
    store.write(key, a.toString());
  }
}
