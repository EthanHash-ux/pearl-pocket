package ai.pearl.wallet;

import android.graphics.*;
import java.io.ByteArrayOutputStream;

/** Machine-readable black-on-white QR sharing, independent of screen theme. */
final class QrImage {
  static byte[] png(String payload, String address, String amount) throws Exception {
    PaymentRequest request = PaymentRequest.parse(payload);
    PearlAddress.normalize(address);
    if (!request.address.equals(address)
        || !(request.amount == null ? "" : PearlAmount.format(request.amount)).equals(amount))
      throw new IllegalArgumentException("二维码与展示的收款信息不一致");
    com.google.zxing.common.BitMatrix bits =
        new com.google.zxing.MultiFormatWriter()
            .encode(payload, com.google.zxing.BarcodeFormat.QR_CODE, 800, 800);
    Bitmap bitmap = Bitmap.createBitmap(1000, 1100, Bitmap.Config.ARGB_8888);
    Canvas canvas = new Canvas(bitmap);
    canvas.drawColor(Color.WHITE);
    Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    p.setColor(Color.BLACK);
    p.setTextAlign(Paint.Align.CENTER);
    p.setTextSize(35);
    p.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
    canvas.drawText("Pearl Pocket · PRL", 500, 64, p);
    p.setAntiAlias(false);
    for (int y = 0; y < 800; y++)
      for (int x = 0; x < 800; x++) if (bits.get(x, y)) canvas.drawPoint(x + 100, y + 90, p);
    p.setAntiAlias(true);
    p.setTextSize(22);
    p.setTypeface(Typeface.MONOSPACE);
    canvas.drawText(address.substring(0, 32), 500, 946, p);
    canvas.drawText(address.substring(32), 500, 981, p);
    p.setTypeface(Typeface.DEFAULT);
    p.setTextSize(30);
    canvas.drawText(amount.isEmpty() ? "Pearl Mainnet" : amount + " PRL", 500, 1046, p);
    try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, out))
        throw new java.io.IOException("PNG 生成失败");
      return out.toByteArray();
    } finally {
      bitmap.recycle();
    }
  }
}
