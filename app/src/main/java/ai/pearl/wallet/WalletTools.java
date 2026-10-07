package ai.pearl.wallet;

import android.app.Activity;
import android.app.AlertDialog;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Lists signing wallets separately from public watch addresses. No secret deletion controls. */
final class WalletTools {
    private final Activity activity;
    private final WalletCatalog catalog;
    private final Consumer<String> selected;
    private final Consumer<Boolean> create;
    private final Supplier<String> current;
    private final Runnable changed;
    private AlertDialog dialog;

    WalletTools(Activity activity, WalletCatalog catalog, Consumer<String> selected,
                Consumer<Boolean> create, Supplier<String> current, Runnable changed) {
        this.activity = activity; this.catalog = catalog; this.selected = selected;
        this.create = create; this.current = current; this.changed = changed;
    }

    void show() {
        try {
            pause();
            LinearLayout form = PearlDesign.form(activity);
            form.addView(PearlDesign.note(activity, "每个手机钱包有独立的助记词与密码，需要分别备份。切换只选择付款钱包，不移动资金。最多保存 10 个钱包。"));
            java.util.List<WalletCatalog.Entry> entries = catalog.list();
            for (WalletCatalog.Entry entry : entries) {
                form.addView(PearlDesign.note(activity, entry.name + (entry.slot.equals(current.get()) ? " · 当前钱包" : "")
                        + "\n" + (entry.readable ? entry.address : "钱包文件无法读取，请保留离线备份并检查恢复方式")));
                if (entry.readable) form.addView(PearlDesign.button(activity, "使用 " + entry.name, PearlDesign.TEAL, PearlDesign.PALE,
                        v -> { pause(); selected.accept(entry.slot); }));
                form.addView(PearlDesign.button(activity, "重命名 " + entry.name, PearlDesign.MUTED, PearlDesign.BG,
                        v -> rename(entry)));
            }
            if (entries.size() < WalletCatalog.LIMIT) {
                form.addView(PearlDesign.button(activity, "创建另一个钱包", PearlDesign.TEAL, PearlDesign.PALE,
                        v -> { pause(); create.accept(false); }));
                form.addView(PearlDesign.button(activity, "导入另一个钱包", PearlDesign.TEAL, PearlDesign.PALE,
                        v -> { pause(); create.accept(true); }));
            }
            ScrollView scroll = new ScrollView(activity); scroll.addView(form);
            dialog = new AlertDialog.Builder(activity).setTitle("管理手机钱包").setView(scroll)
                    .setNegativeButton("关闭", null).create();
            dialog.show(); PearlDesign.dialog(dialog);
        } catch (Exception error) { notice(error.getMessage()); }
    }

    private void rename(WalletCatalog.Entry entry) {
        pause();
        EditText name = new EditText(activity); name.setText(entry.name);
        name.setHint("钱包名称（1 至 24 个字符）"); name.setContentDescription("钱包名称"); name.setSingleLine(true); PearlDesign.input(name);
        dialog = new AlertDialog.Builder(activity).setTitle("重命名手机钱包").setView(name)
                .setNegativeButton("取消", (d, w) -> show()).setPositiveButton("保存名称", null).create();
        dialog.show(); PearlDesign.dialog(dialog);
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            try { catalog.rename(entry.slot, name.getText().toString()); changed.run(); show(); }
            catch (Exception error) { name.setError(error.getMessage()); }
        });
    }

    private void notice(String message) {
        pause(); dialog = new AlertDialog.Builder(activity).setTitle("手机钱包")
                .setMessage(message).setPositiveButton("知道了", null).create();
        dialog.show(); PearlDesign.dialog(dialog);
    }

    void pause() { if (dialog != null) { dialog.dismiss(); dialog = null; } }
}
