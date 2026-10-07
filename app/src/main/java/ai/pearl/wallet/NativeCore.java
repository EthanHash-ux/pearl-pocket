package ai.pearl.wallet;

import org.json.JSONObject;
import android.util.Base64;

/** JNI contains the pinned official Pearl signing implementation. Never logs requests. */
public final class NativeCore {
    static { System.loadLibrary("pearlcore"); }
    private NativeCore() {}
    private static native String execute(String request);
    static JSONObject call(JSONObject request) throws Exception {
        JSONObject response = new JSONObject(execute(request.toString()));
        if (response.has("error")) throw new IllegalArgumentException(response.getString("error"));
        return response.getJSONObject("result");
    }
    static JSONObject identity(byte[] entropy) throws Exception {
        return call(new JSONObject().put("action", "identity").put("entropy", Base64.encodeToString(entropy, Base64.NO_WRAP)));
    }
    static String address(byte[] entropy) throws Exception {
        return call(new JSONObject().put("action", "address").put("entropy", Base64.encodeToString(entropy, Base64.NO_WRAP))).getString("address");
    }
    static JSONObject sign(byte[] entropy, JSONObject quote) throws Exception {
        return call(new JSONObject().put("action", "sign").put("entropy", Base64.encodeToString(entropy, Base64.NO_WRAP)).put("quote", quote));
    }
}
