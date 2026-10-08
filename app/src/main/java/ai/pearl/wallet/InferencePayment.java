package ai.pearl.wallet;

import java.math.BigInteger;
import org.json.JSONObject;

/** Immutable review terms; rechecked before the existing PRL signing flow. */
final class InferencePayment {
  final String slot, payer, base, invoiceId, recipient, merchant;
  final BigInteger amount;
  final long expires;
  private final String snapshot, catalogSnapshot;
  private final InferenceStore store;
  private final InferenceApi api;

  InferencePayment(
      String slot, String payer, InferenceStore store, InferenceApi api, JSONObject invoice)
      throws Exception {
    this.slot = WalletCatalog.validSlot(slot);
    this.payer = PearlAddress.normalize(payer);
    this.store = store;
    this.api = api;
    JSONObject account = store.read();
    base = account.getString("base");
    merchant = account.getJSONObject("catalog").getString("merchant");
    InferenceApi.validateInvoice(invoice, account.getJSONObject("catalog"));
    invoiceId = invoice.getString("id");
    recipient = invoice.getString("address");
    amount = new BigInteger(invoice.getString("amount_units"));
    expires = invoice.getLong("expires_at");
    snapshot = invoice.toString();
    catalogSnapshot = account.getJSONObject("catalog").toString();
  }

  void recheck() throws Exception {
    JSONObject account = store.read(),
        old = new JSONObject(snapshot),
        pinned = new JSONObject(catalogSnapshot);
    if (!base.equals(account.getString("base"))
        || !pinned
            .getString("account_xpub")
            .equals(account.getJSONObject("catalog").getString("account_xpub")))
      throw new IllegalArgumentException("已绑定服务商发生变化");
    if (store.invoice(invoiceId).has("local_txid"))
      throw new IllegalArgumentException("已有账单付款，请查询原交易");
    JSONObject currentCatalog = api.catalog(base);
    if (!pinned.getString("account_xpub").equals(currentCatalog.getString("account_xpub")))
      throw new IllegalArgumentException("服务商收款公钥发生变化，请停止付款");
    JSONObject fresh = api.invoice(base, account.getString("key"), invoiceId);
    InferenceApi.validateInvoice(fresh, pinned);
    InferenceApi.unchanged(old, fresh);
    if (!"unpaid".equals(fresh.getString("status"))
        || expires - System.currentTimeMillis() / 1000 <= 120)
      throw new IllegalArgumentException("账单已付款或剩余时间不足，请查询结果");
  }

  void signed(String txid) throws Exception {
    store.sent(invoiceId, txid);
  }

  String summary() throws Exception {
    JSONObject inv = new JSONObject(snapshot), m = inv.getJSONObject("model");
    return "AI 推理服务商 · "
        + merchant
        + "\n"
        + base
        + "\n账单 "
        + invoiceId
        + "\n模型 "
        + m.getString("id")
        + "\n购买 "
        + inv.getInt("request_count")
        + " 次调用 · 每次最多 "
        + m.getInt("max_tokens")
        + " 输出 token\n付款属于服务商，6 个确认后查询并申请开通额度。服务由该商家提供。";
  }
}
