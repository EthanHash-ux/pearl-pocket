package ai.pearl.wallet;

import android.content.ClipData;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import java.io.File;
import java.io.FileNotFoundException;
import java.nio.file.Files;
import java.util.UUID;

/** Grants access only to generated public PNG/CSV/JSON exports, never wallet storage. */
public final class PublicShareProvider extends ContentProvider {
  static final String AUTHORITY = "ai.pearl.wallet.publicshare";

  @Override
  public boolean onCreate() {
    return true;
  }

  private File file(Uri uri) throws FileNotFoundException {
    String n = uri.getLastPathSegment();
    if (!AUTHORITY.equals(uri.getAuthority())
        || uri.getPathSegments().size() != 1
        || n == null
        || !n.matches("pearl-public-[a-f0-9-]+\\.(png|csv|json)"))
      throw new FileNotFoundException("Public export only");
    File f = new File(new File(getContext().getCacheDir(), "public-share"), n);
    if (!f.isFile()) throw new FileNotFoundException("Export no longer available");
    return f;
  }

  @Override
  public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
    if (!"r".equals(mode)) throw new FileNotFoundException("Read only");
    return ParcelFileDescriptor.open(file(uri), ParcelFileDescriptor.MODE_READ_ONLY);
  }

  @Override
  public String getType(Uri uri) {
    String n = uri.getLastPathSegment();
    return n != null && n.endsWith(".png")
        ? "image/png"
        : n != null && n.endsWith(".csv") ? "text/csv" : "application/json";
  }

  @Override
  public Cursor query(Uri uri, String[] projection, String selection, String[] args, String order) {
    try {
      File f = file(uri);
      String[] cols =
          projection == null
              ? new String[] {OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE}
              : projection;
      MatrixCursor c = new MatrixCursor(cols);
      Object[] row = new Object[cols.length];
      for (int i = 0; i < cols.length; i++)
        row[i] =
            cols[i].equals(OpenableColumns.DISPLAY_NAME)
                ? f.getName()
                : cols[i].equals(OpenableColumns.SIZE) ? f.length() : null;
      c.addRow(row);
      return c;
    } catch (FileNotFoundException e) {
      return null;
    }
  }

  @Override
  public Uri insert(Uri uri, ContentValues values) {
    throw new UnsupportedOperationException();
  }

  @Override
  public int delete(Uri uri, String selection, String[] args) {
    throw new UnsupportedOperationException();
  }

  @Override
  public int update(Uri uri, ContentValues values, String selection, String[] args) {
    throw new UnsupportedOperationException();
  }

  static Uri write(Context c, byte[] bytes, String extension) throws Exception {
    if (!extension.matches("png|csv|json") || bytes.length > 8_000_000)
      throw new IllegalArgumentException("分享文件无效或过大");
    File dir = new File(c.getCacheDir(), "public-share");
    if (!dir.isDirectory() && !dir.mkdirs()) throw new java.io.IOException("无法生成分享文件");
    File[] old = dir.listFiles();
    if (old != null)
      for (File f : old)
        if (System.currentTimeMillis() - f.lastModified() > 24 * 3600_000L) f.delete();
    File out = new File(dir, "pearl-public-" + UUID.randomUUID() + "." + extension);
    Files.write(out.toPath(), bytes);
    return new Uri.Builder()
        .scheme("content")
        .authority(AUTHORITY)
        .appendPath(out.getName())
        .build();
  }

  static Intent intent(Context c, byte[] bytes, String extension, String title) throws Exception {
    Uri u = write(c, bytes, extension);
    Intent i =
        new Intent(Intent.ACTION_SEND)
            .setType(
                extension.equals("png")
                    ? "image/png"
                    : extension.equals("csv") ? "text/csv" : "application/json")
            .putExtra(Intent.EXTRA_STREAM, u);
    i.setClipData(ClipData.newRawUri(title, u));
    i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
    return Intent.createChooser(i, title);
  }
}
