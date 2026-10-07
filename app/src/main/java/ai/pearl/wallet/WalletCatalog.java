package ai.pearl.wallet;

import android.content.Context;
import android.util.AtomicFile;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/** Public names and selection only. Existing vaults are discovered, never moved or deleted. */
final class WalletCatalog {
    static final String LEGACY = "legacy";
    static final int LIMIT = 10;
    private final Context context;
    private final AtomicFile file;

    static final class Entry {
        final String slot, name, address;
        final boolean readable;
        Entry(String slot, String name, String address, boolean readable) {
            this.slot = slot; this.name = name; this.address = address; this.readable = readable;
        }
    }

    WalletCatalog(Context context) {
        this.context = context;
        file = new AtomicFile(new File(context.getNoBackupFilesDir(), "wallet-catalog.json"));
    }

    static String validSlot(String slot) {
        if (LEGACY.equals(slot)) return slot;
        if (slot == null || !slot.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))
            throw new IllegalArgumentException("钱包标识无效");
        return slot;
    }

    static File directory(Context context, String slot) {
        validSlot(slot);
        return LEGACY.equals(slot) ? context.getNoBackupFilesDir()
                : new File(new File(context.getNoBackupFilesDir(), "wallets"), slot);
    }

    private JSONObject read() throws Exception {
        if (!file.getBaseFile().exists() && !new File(file.getBaseFile() + ".bak").exists())
            return new JSONObject().put("version", 1).put("names", new JSONObject());
        byte[] bytes = file.readFully();
        if (bytes.length > 65_536) throw new IllegalArgumentException("钱包列表无法读取");
        JSONObject data = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
        if (data.getInt("version") != 1) throw new IllegalArgumentException("钱包列表版本不兼容");
        data.getJSONObject("names");
        if (data.has("active")) validSlot(data.getString("active"));
        return data;
    }

    synchronized List<Entry> list() throws Exception {
        JSONObject names = read().getJSONObject("names");
        List<String> slots = new ArrayList<>();
        if (new WalletVault(context).exists()) slots.add(LEGACY);
        File directory = new File(context.getNoBackupFilesDir(), "wallets");
        File[] children = directory.listFiles();
        if (directory.exists() && children == null) throw new java.io.IOException("无法读取手机钱包目录");
        if (children != null) {
            java.util.Arrays.sort(children, Comparator.comparing(File::getName));
            for (File child : children) {
                if (child.isDirectory() && child.getName().matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) {
                    String slot = validSlot(child.getName());
                    if (new WalletVault(context, slot).exists()) slots.add(slot);
                }
            }
        }
        List<Entry> entries = new ArrayList<>();
        for (String slot : slots) {
            String name = names.optString(slot, LEGACY.equals(slot) ? "我的钱包" : "手机钱包");
            try {
                entries.add(new Entry(slot, name, new WalletVault(context, slot).metadata().getString("address"), true));
            } catch (Exception error) {
                entries.add(new Entry(slot, name, "", false));
            }
        }
        return entries;
    }

    synchronized String active() throws Exception {
        JSONObject data = read();
        if (data.has("active")) return data.getString("active");
        List<Entry> entries = list();
        return entries.isEmpty() ? LEGACY : entries.get(0).slot;
    }

    synchronized String name(String slot) {
        try { return read().getJSONObject("names").optString(validSlot(slot), LEGACY.equals(slot) ? "我的钱包" : "手机钱包"); }
        catch (Exception error) { return "手机钱包"; }
    }

    synchronized String newSlot() throws Exception {
        if (list().size() >= LIMIT) throw new IllegalArgumentException("本机最多保存 10 个独立钱包");
        return UUID.randomUUID().toString();
    }

    synchronized void checkNewAddress(String slot, String address) throws Exception {
        validSlot(slot); address = PearlAddress.normalize(address);
        List<Entry> entries = list();
        if (entries.size() >= LIMIT) throw new IllegalArgumentException("本机最多保存 10 个独立钱包");
        for (Entry entry : entries)
            if (!entry.slot.equals(slot) && entry.address.equals(address))
                throw new IllegalArgumentException("这个钱包已在本机保存，请从手机钱包列表切换");
    }

    synchronized void select(String slot) throws Exception {
        validSlot(slot);
        new WalletVault(context, slot).metadata(); // Never silently fall back to another signing wallet.
        JSONObject data = read().put("active", slot);
        write(data);
    }

    synchronized void rename(String slot, String name) throws Exception {
        validSlot(slot);
        String clean = name == null ? "" : name.trim();
        if (clean.isEmpty() || clean.length() > 24 || clean.chars().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("名称需要 1 至 24 个字符，不能包含换行");
        boolean exists = false;
        for (Entry entry : list()) if (entry.slot.equals(slot)) exists = true;
        if (!exists) throw new IllegalArgumentException("钱包尚未保存");
        JSONObject data = read(); data.getJSONObject("names").put(slot, clean); write(data);
    }

    private void write(JSONObject data) throws Exception {
        FileOutputStream out = null;
        try {
            out = file.startWrite(); out.write(data.toString().getBytes(StandardCharsets.UTF_8)); file.finishWrite(out);
        } catch (Exception error) { if (out != null) file.failWrite(out); throw error; }
    }
}
