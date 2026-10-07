package ai.pearl.wallet;

import android.content.Context;
import android.content.SharedPreferences;

/** Only public contacts and alert settings. Never opens the wallet or its encryption keys. */
final class PublicStore {
    interface Storage { String read(String key); void write(String key, String value); }
    static final Object LOCK = new Object();
    static Storage of(Context context) {
        SharedPreferences prefs = context.getApplicationContext().getSharedPreferences("public_tools", Context.MODE_PRIVATE);
        return new Storage() {
            public String read(String key) { return prefs.getString(key, "[]"); }
            public void write(String key, String value) {
                if (!prefs.edit().putString(key, value).commit()) throw new IllegalStateException("本机设置保存失败，请重试");
            }
        };
    }
}
