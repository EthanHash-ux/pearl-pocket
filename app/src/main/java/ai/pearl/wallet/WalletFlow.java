package ai.pearl.wallet;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.KeyguardManager;
import android.content.Intent;
import android.text.InputType;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import org.json.JSONObject;
import java.math.BigInteger;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Operation-scoped secrets: no unlocked seed session, no network access with a seed.
 * Device authentication is followed by a fresh wallet password for every signature/backup. */
final class WalletFlow {
    static final int AUTH = 4108;
    interface Host { void changed(); String address(); default String name() { return "手机钱包"; } }
    interface SecretAction { void run(byte[] entropy) throws Exception; }
    private final Activity activity;
    private final Host host;
    private final WalletVault vault;
    private final PendingTransfer pending;
    private final PearlApi api;
    private final PublicTools publicTools;
    private final String slot;
    private String scanAddress="", scanAmount="";
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private AlertDialog dialog;
    private Runnable afterAuth;
    private boolean authWaiting;
    private volatile boolean busy, active;
    private volatile int generation;
    private String lastResult = "";
    private byte[] recoveryEntropy;
    private String recoveryAddress = "";
    private final android.os.Handler recoveryTimeout = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable expireRecovery = () -> { clearRecovery(); afterAuth = null; };
    WalletFlow(Activity a, Host h, PearlApi api) { this(a, h, api, WalletCatalog.LEGACY); }
    WalletFlow(Activity a, Host h, PearlApi api, String slot) { activity = a; host = h; this.api = api; this.slot = WalletCatalog.validSlot(slot); vault = new WalletVault(a, slot); pending = new PendingTransfer(a, slot); publicTools=new PublicTools(a); }
    boolean canLeave() { return !busy && !authWaiting; }
    boolean awaitingAuth() { return authWaiting; }
    boolean exists() { return vault.exists(); }
    String address() throws Exception { return vault.exists() ? vault.metadata().getString("address") : ""; }
    String lastResult() { return lastResult; }
    boolean backedUp() { return activity.getSharedPreferences("backup_status", Activity.MODE_PRIVATE).getBoolean(host.address(), false); }
    private LinearLayout form() {
        return PearlDesign.form(activity);
    }
    private TextView label(String s) { return PearlDesign.note(activity,s); }
    private EditText field(String hint, boolean password) {
        EditText e = new EditText(activity); e.setHint(hint); e.setSingleLine(password);
        e.setInputType(InputType.TYPE_CLASS_TEXT | (password ? InputType.TYPE_TEXT_VARIATION_PASSWORD : InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS));
        e.setImportantForAutofill(android.view.View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
        e.setImeOptions(android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING);PearlDesign.input(e); return e;
    }
    private void show(AlertDialog d) {
        if (dialog != null) dialog.dismiss(); dialog = d; d.show();
        PearlDesign.dialog(d);
    }
    void notice(String title, String message) {
        if (!active || activity.isDestroyed()) return;
        show(new AlertDialog.Builder(activity).setTitle(title).setMessage(message).setPositiveButton("知道了", null).create());
    }
    private void ui(int token, Runnable action) {
        activity.runOnUiThread(() -> { if (active && token == generation && !activity.isDestroyed()) action.run(); });
    }
    private String error(Exception e) {
        if (e instanceof android.security.keystore.UserNotAuthenticatedException) return "手机解锁验证已过期，请重新操作";
        if (e instanceof android.security.keystore.KeyPermanentlyInvalidatedException) return "设备密钥已失效，请用离线助记词恢复钱包";
        return e instanceof IllegalArgumentException || e instanceof java.io.IOException ? e.getMessage() : "操作失败，请重试。若设备密钥失效，请使用离线助记词恢复。";
    }
    private void auth(Runnable action) {
        if (busy || authWaiting) return;
        KeyguardManager manager = (KeyguardManager)activity.getSystemService(Activity.KEYGUARD_SERVICE);
        if (!manager.isDeviceSecure()) {
            notice("先设置手机锁屏", "请在 Android 设置中启用锁屏 PIN 或密码，然后返回。钱包会在签名和备份前要求手机解锁验证。"); return;
        }
        Intent intent = manager.createConfirmDeviceCredentialIntent("Pearl 钱包", "验证手机解锁后，再输入钱包密码");
        if (intent == null) { notice("无法验证手机解锁", "请检查系统锁屏设置"); return; }
        afterAuth = action; authWaiting = true; activity.startActivityForResult(intent, AUTH);
    }
    void authResult(int resultCode) {
        Runnable action = afterAuth; afterAuth = null; authWaiting = false; recoveryTimeout.removeCallbacks(expireRecovery);
        if (resultCode != Activity.RESULT_OK) clearRecovery();
        // onActivityResult can run before onResume; post the continuation.
        activity.getWindow().getDecorView().post(() -> { if (resultCode == Activity.RESULT_OK && active && action != null) action.run(); });
    }
    void setup(boolean restore) {
        if (exists() || busy || authWaiting) return;
        if (restore) {
            KeyguardManager manager = (KeyguardManager)activity.getSystemService(Activity.KEYGUARD_SERVICE);
            if (!manager.isDeviceSecure()) { notice("先设置手机锁屏", "在 Android 设置中启用锁屏 PIN 或密码后，再恢复钱包。"); return; }
            restoreEditor();
        } else auth(() -> setupForm(false));
    }
    private void clearRecovery() {
        if (recoveryEntropy != null) Arrays.fill(recoveryEntropy, (byte)0);
        recoveryEntropy = null; recoveryAddress = "";
    }
    private void restoreEditor() {
        clearRecovery();
        try {
            LinearLayout view = form(); view.addView(label("第 1 步 · 输入 BIP39 英文助记词\n支持 12、15、18、21、24 个词，按原备份顺序填写或粘贴。词库提示和校验均在手机完成。"));
            view.addView(label("仅恢复本应用的手机钱包，不支持 Oyster XMSS 账户或 BIP39 附加口令。"));
            MnemonicEntryView entry = new MnemonicEntryView(activity); view.addView(entry);
            android.widget.ScrollView scroll = new android.widget.ScrollView(activity); scroll.addView(view);
            AlertDialog d = new AlertDialog.Builder(activity).setTitle("恢复手机钱包").setView(scroll).setNegativeButton("取消", null).setPositiveButton("校验并继续", null).create();
            show(d); d.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
            d.getWindow().setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT);
            d.setOnDismissListener(v -> entry.clear());
            entry.setListener(valid -> d.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(valid && !busy));
            d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                if (busy) return;
                try {
                    String words = entry.mnemonic(); d.dismiss(); busy = true; int token = generation;
                    worker.execute(() -> {
                        byte[] decoded = null;
                        try {
                            JSONObject identity = NativeCore.call(new JSONObject().put("action", "import").put("mnemonic", words));
                            decoded = WalletVault.decode(identity.getString("entropy")); String address = identity.getString("address");
                            byte[] transferred = decoded; decoded = null;
                            activity.runOnUiThread(() -> {
                                busy = false;
                                if (!active || token != generation || activity.isDestroyed()) { Arrays.fill(transferred, (byte)0); return; }
                                clearRecovery(); recoveryEntropy = transferred; recoveryAddress = address; confirmRecovery();
                            });
                        } catch (Exception e) { ui(token, () -> notice("助记词校验失败", error(e))); }
                        finally { if (decoded != null) Arrays.fill(decoded, (byte)0); busy = false; }
                    });
                } catch (Exception e) { notice("请检查助记词", error(e)); }
            });
        } catch (Exception e) { notice("恢复页面加载失败", error(e)); }
    }
    private void confirmRecovery() {
        if (recoveryEntropy == null) return;
        LinearLayout view = form(); view.addView(label("第 2 步 · 核对收款地址\n请与原钱包的 Pearl 收款地址核对。地址不一致时，请返回检查助记词。"));
        TextView full = label(recoveryAddress); full.setTextIsSelectable(true); full.setTypeface(android.graphics.Typeface.MONOSPACE); view.addView(full);
        AlertDialog d = new AlertDialog.Builder(activity).setTitle("确认恢复的钱包").setView(view)
                .setNegativeButton("重新输入", (dialog,which) -> { clearRecovery(); restoreEditor(); })
                .setPositiveButton("设置钱包密码", (dialog,which) -> auth(() -> setupForm(true))).create();
        d.setOnCancelListener(dialog -> clearRecovery()); show(d);
    }
    private void setupForm(boolean restore) {
        if (restore && recoveryEntropy == null) { notice("恢复已取消", "验证已取消或超时，请重新输入助记词。"); return; }
        LinearLayout view = form();
        view.addView(label(restore ? "第 3 步 · 设置新密码\n密码用于保护这台手机的钱包，与原钱包密码可以不同。" : "创建后会显示 24 个英文助记词，请离线抄写。钱包密码至少 10 个字符，恢复时可设置新密码。"));
        EditText password = field("新钱包密码（至少 10 个字符）", true), repeat = field("再次输入密码", true);
        view.addView(password); view.addView(repeat);
        AlertDialog d = new AlertDialog.Builder(activity).setTitle(restore ? "设置恢复钱包的密码" : "创建手机钱包").setView(view).setNegativeButton("取消", null).setPositiveButton(restore ? "恢复钱包" : "继续", null).create();
        show(d); d.setOnDismissListener(v -> { password.setText(""); repeat.setText(""); clearRecovery(); });
        d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            if (busy) return;
            if (password.length() < 10 || !password.getText().toString().equals(repeat.getText().toString())) { repeat.setError("密码至少 10 个字符，且两次必须一致"); return; }
            char[] secret = new char[password.length()]; password.getText().getChars(0, secret.length, secret, 0);
            byte[] imported = recoveryEntropy; recoveryEntropy = null; String importedAddress = recoveryAddress;
            d.dismiss(); busy = true; int token = generation;
            Toast.makeText(activity, "正在加密保存钱包…", Toast.LENGTH_LONG).show();
            worker.execute(() -> {
                byte[] entropy = imported;
                try {
                    JSONObject identity = restore ? null : NativeCore.call(new JSONObject().put("action", "generate"));
                    if (!restore) entropy = WalletVault.decode(identity.getString("entropy"));
                    String address = restore ? importedAddress : identity.getString("address");
                    new WalletCatalog(activity).checkNewAddress(slot, address);
                    synchronized (this) { if (!active || token != generation) return; vault.create(entropy, secret, address); }
                    if (restore) {
                        // The complete phrase was locally validated and the address explicitly confirmed.
                        activity.getSharedPreferences("backup_status", Activity.MODE_PRIVATE).edit().putBoolean(address, true).apply();
                        ui(token, () -> { host.changed(); notice("钱包已恢复", "已恢复相同的收款地址。可以在设置中再次查看离线备份。"); });
                    } else {
                        String backup = identity.getString("mnemonic"); ui(token, () -> { host.changed(); showBackup(backup); });
                    }
                } catch (Exception e) { ui(token, () -> notice("保存失败", error(e))); }
                finally { Arrays.fill(secret, '\0'); if (entropy != null) Arrays.fill(entropy, (byte)0); busy = false; }
            });
        });
    }
    private void unlock(String purpose, SecretAction action) {
        auth(() -> {
            LinearLayout view = form(); view.addView(label("" + purpose + "。密钥仅在本次操作中解密，完成后清除。"));
            EditText input = field("钱包密码", true); view.addView(input);
            AlertDialog d = new AlertDialog.Builder(activity).setTitle("验证钱包密码").setView(view).setNegativeButton("取消", null).setPositiveButton("确认", null).create();
            show(d); d.setOnDismissListener(v -> input.setText(""));
            d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                if (busy) return;
                char[] password = new char[input.length()]; input.getText().getChars(0, password.length, password, 0); d.dismiss();
                busy = true; int token = generation;
                worker.execute(() -> {
                    byte[] entropy = null;
                    try {
                        entropy = vault.unlock(password);
                        synchronized (this) { if (!active || token != generation) return; action.run(entropy); }
                    } catch (Exception e) { ui(token, () -> notice("操作失败", error(e))); }
                    finally { Arrays.fill(password, '\0'); if (entropy != null) Arrays.fill(entropy, (byte)0); busy = false; }
                });
            });
        });
    }
    void backup() {
        if (!exists()) return;
        unlock("查看离线备份", entropy -> {
            String words = NativeCore.identity(entropy).getString("mnemonic"); int token = generation;
            ui(token, () -> showBackup(words));
        });
    }
    private void showBackup(String words) {
        String[] list = words.split(" "); LinearLayout view = form();
        view.addView(label("按顺序抄写到纸上并离线保管。持有这些词的人可以转走资金。请勿截图、复制到剪贴板或发给他人。"));
        java.util.List<TextView> cells=new java.util.ArrayList<>();LinearLayout row=null;
        for(int i=0;i<list.length;i++){
            if(i%3==0){row=new LinearLayout(activity);view.addView(row);}
            TextView cell=PearlDesign.text(activity,(i+1)+". "+list[i],13,PearlDesign.INK,true);cell.setTypeface(android.graphics.Typeface.MONOSPACE);cell.setPadding(10,18,8,18);cell.setTextIsSelectable(false);cell.setBackground(PearlDesign.surface(activity,PearlDesign.BG,0,10));
            LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,-2,1);p.setMargins(4,6,4,6);row.addView(cell,p);cells.add(cell);
        }
        android.widget.ScrollView scroll=new android.widget.ScrollView(activity);scroll.addView(view);
        AlertDialog d = new AlertDialog.Builder(activity).setTitle("离线助记词备份").setView(scroll).setNegativeButton("稍后备份", null).setPositiveButton("已抄好，验证备份", null).create();
        show(d); d.setOnDismissListener(v -> { for(TextView cell:cells)cell.setText(""); });
        d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> { d.dismiss(); verifyBackup(list); });
    }
    private void verifyBackup(String[] words) {
        java.security.SecureRandom random = new java.security.SecureRandom(); java.util.LinkedHashSet<Integer> indexes = new java.util.LinkedHashSet<>();
        while (indexes.size() < 3) indexes.add(random.nextInt(words.length));
        LinearLayout view = form(); view.addView(label("填写刚才抄写的三个词，确认你的备份可用。"));
        EditText[] entries = new EditText[3]; int[] positions = new int[3]; int n = 0;
        for (int index : indexes) { positions[n] = index; entries[n] = field("第 " + (index+1) + " 个词", false); view.addView(entries[n++]); }
        AlertDialog d = new AlertDialog.Builder(activity).setTitle("验证离线备份").setView(view).setNegativeButton("稍后", null).setPositiveButton("完成", null).create();
        show(d); d.setOnDismissListener(v -> { Arrays.fill(words, ""); for (EditText e : entries) e.setText(""); });
        d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            for (int i=0; i<3; i++) if (!words[positions[i]].equals(entries[i].getText().toString().trim().toLowerCase(java.util.Locale.ROOT))) { entries[i].setError("助记词不匹配"); return; }
            activity.getSharedPreferences("backup_status", Activity.MODE_PRIVATE).edit().putBoolean(host.address(), true).apply();
            d.dismiss(); host.changed(); notice("备份完成", "钱包已准备好收发 PRL。每次发送仍需验证手机解锁和钱包密码。");
        });
    }
    void send() {
        send("", "");
    }
    private void send(String draftAddress,String draftAmount) {
        if (!exists()) { notice("先创建钱包", "创建或恢复手机钱包后即可收发 PRL。"); return; }
        if (busy) { notice("正在处理", "请等待当前操作完成。"); return; }
        try { if (pending.load() != null) { pendingStatus(); return; } } catch (Exception e) { notice("待确认交易需要检查", error(e)); return; }
        if (!backedUp()) { notice("先完成离线备份", "在设置中验证助记词备份后，再进行发送。"); return; }
        LinearLayout view = form(); view.addView(label("仅发送到 Pearl 主网 prl1p 或 prl1z 地址。金额最多 8 位小数，手续费在下一步展示。"));
        EditText to = field("完整 Pearl 主网收款地址", false), amount = field("金额（PRL）", false);
        to.setText(draftAddress);amount.setText(draftAmount);
        amount.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL); view.addView(to); view.addView(amount);
        android.widget.Button maximum=PearlDesign.button(activity,"发送全部余额",PearlDesign.TEAL,PearlDesign.PALE,v->{
            if(busy)return;try{String recipient=PearlAddress.normalize(to.getText().toString());if(recipient.equals(host.address()))throw new IllegalArgumentException("收款地址不能是自己");if(dialog!=null)dialog.dismiss();busy=true;int token=generation;String from=host.address();Toast.makeText(activity,"正在核验可用余额与全部发送手续费…",Toast.LENGTH_LONG).show();
                worker.execute(()->{try{JSONObject payment=api.payment(from,recipient,java.math.BigInteger.valueOf(333));JSONObject quote=NativeCore.call(new JSONObject().put("action","planmax").put("payment",payment));ui(token,()->review(quote));}catch(Exception e){ui(token,()->notice("无法发送全部余额",error(e)));}finally{busy=false;}});
            }catch(Exception e){to.setError(error(e));}
        });view.addView(maximum);
        android.widget.Button scan=PearlDesign.button(activity,"扫描收款二维码",PearlDesign.TEAL,PearlDesign.PALE,null);scan.setCompoundDrawables(PearlDesign.icon(activity,"scan",PearlDesign.TEAL,19),null,null,null);view.addView(scan);
        scan.setOnClickListener(v->{scanAddress=to.getText().toString();scanAmount=amount.getText().toString();dismissForScan();activity.startActivityForResult(new Intent(activity,QrScannerActivity.class),QrScannerActivity.REQUEST);});
        android.widget.Button book=PearlDesign.button(activity,"从地址簿选择",PearlDesign.INK,PearlDesign.BG,null);view.addView(book);book.setOnClickListener(v->publicTools.addressBook(to::setText));
        android.widget.Button save=PearlDesign.button(activity,"保存地址到联系人",PearlDesign.MUTED,PearlDesign.WHITE,null);view.addView(save);save.setOnClickListener(v->{try{publicTools.saveContact(PearlAddress.normalize(to.getText().toString()));}catch(Exception e){to.setError(error(e));}});
        AlertDialog d = new AlertDialog.Builder(activity).setTitle("发送 PRL").setView(view).setNegativeButton("取消", null).setPositiveButton("预览转账", null).create();
        show(d); d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            if (busy) return;
            try {
                String recipient;
                try{recipient=PearlAddress.normalize(to.getText().toString());}catch(Exception e){to.setError(error(e));return;}
                BigInteger value = PearlAmount.parsePositivePrl(amount.getText().toString());
                d.dismiss(); busy = true; int token = generation; String from = host.address();
                Toast.makeText(activity, "正在校验余额和手续费…", Toast.LENGTH_LONG).show();
                worker.execute(() -> {
                    try {
                        JSONObject payment = api.payment(from, recipient, value);
                        JSONObject quote = NativeCore.call(new JSONObject().put("action", "plan").put("payment", payment));
                        ui(token, () -> review(quote));
                    } catch (Exception e) { ui(token, () -> notice("无法预览转账", error(e))); }
                    finally { busy = false; }
                });
            } catch (Exception e) { amount.setError(error(e)); }
        });
    }
    private void dismissForScan(){if(dialog!=null){dialog.dismiss();dialog=null;}}
    void scanResult(int resultCode,Intent data){
        String draftAddress=scanAddress,draftAmount=scanAmount;scanAddress="";scanAmount="";
        activity.getWindow().getDecorView().post(()->{
            if(!active)return;
            if(resultCode!=Activity.RESULT_OK||data==null){send(draftAddress,draftAmount);return;}
            try{PaymentRequest request=PaymentRequest.parse(data.getStringExtra(QrScannerActivity.PAYLOAD));send(request.address,request.amount==null?draftAmount:PearlAmount.format(request.amount));}
            catch(Exception e){notice("无法使用收款二维码",error(e));}
        });
    }
    void bridge(BridgeApi.MintPlan bridge) {
        if(!exists()){notice("先创建钱包","创建或恢复手机钱包后才能向桥转入 PRL。");return;}
        if(busy||authWaiting)return;
        if(!backedUp()){notice("先完成离线备份","完成助记词备份验证后再操作。");return;}
        try{if(pending.load()!=null){pendingStatus();return;}}catch(Exception e){notice("待确认交易需要检查",error(e));return;}
        busy=true;int token=generation;String from=host.address();
        worker.execute(()->{try{
            new BridgeApi().recheck(bridge,new BridgeBook(PublicStore.of(activity)));
            JSONObject payment=api.payment(from,bridge.deposit,bridge.quote.amount);
            JSONObject quote=NativeCore.call(new JSONObject().put("action","plan").put("payment",payment));
            ui(token,()->review(quote,bridge));
        }catch(Exception e){ui(token,()->notice("无法预览跨链转入",error(e)));}finally{busy=false;}});
    }
    private void review(JSONObject quote) { review(quote,null); }
    private void review(JSONObject quote,BridgeApi.MintPlan bridge) {
        try {
            JSONObject payment = quote.getJSONObject("payment");
            if (!address().equals(payment.getString("from"))) throw new IllegalArgumentException("付款钱包与转账预览不一致，请重新操作");
            String message = "付款钱包 · " + host.name() + "\n" + address() + "\n\n收款地址\n" + payment.getString("to") + "\n\n金额  " + PearlAmount.format(new BigInteger(payment.getString("amount"))) + " PRL\n手续费  " + PearlAmount.format(new BigInteger(quote.getString("fee"))) + " PRL\n合计  " + PearlAmount.format(new BigInteger(payment.getString("amount")).add(new BigInteger(quote.getString("fee")))) + " PRL"+(payment.optBoolean("sweep",false)?"\n发送全部可用余额（已扣手续费），不生成找零。未确认与未成熟奖励不参与。":"")+"\n\n预览有效期 5 分钟。确认后在手机签名并提交到 Pearl 主网。";
            if(bridge!=null)message += "\n\n"+bridge.summary()+"\n签名前再次核对桥报价。核对后 2 分钟内完成验证；桥状态仍可能随后变化。";
            final long[] bridgeCheckedAt={0};
            Runnable approve=()->unlock("确认并发送这笔转账", entropy -> {
                        if (pending.load() != null) throw new IllegalArgumentException("已有交易等待确认");
                        if(bridge!=null){
                            if(bridgeCheckedAt[0]<=0||android.os.SystemClock.elapsedRealtime()-bridgeCheckedAt[0]>120_000)throw new IllegalArgumentException("跨链核对已过期，请重新报价");
                            if(!bridge.deposit.equals(payment.getString("to"))||!bridge.quote.amount.toString().equals(payment.getString("amount")))throw new IllegalArgumentException("跨链交易与已确认报价不一致");
                        }
                        JSONObject signed = NativeCore.sign(entropy, quote);
                        signed.put("to", payment.getString("to")).put("amount", payment.getString("amount"));
                        if(bridge!=null){new BridgeBook(PublicStore.of(activity)).save(true,signed.getString("txid"),bridge.eth);signed.put("bridgeEth",bridge.eth);}
                        pending.save(signed);
                        // Network submission is queued after unlock's finally wipes entropy/password.
                        int token = generation; worker.execute(() -> submit(signed, token));
                    });
            show(new AlertDialog.Builder(activity).setTitle(bridge==null?"确认转账":"确认 PRL 转入跨链桥").setMessage(message).setNegativeButton("取消", null)
                    .setPositiveButton("验证并发送", (d,w) -> {
                        if(bridge==null){approve.run();return;}
                        if(busy||authWaiting)return;busy=true;int token=generation;
                        worker.execute(()->{try{new BridgeApi().recheck(bridge,new BridgeBook(PublicStore.of(activity)));
                            ui(token,()->{busy=false;bridgeCheckedAt[0]=android.os.SystemClock.elapsedRealtime();approve.run();});
                        }catch(Exception e){ui(token,()->notice("跨链核对未通过",error(e)));}finally{busy=false;}});
                    }).create());
        } catch (Exception e) { notice("预览失败", error(e)); }
    }
    private void submit(JSONObject signed, int token) {
        busy = true;
        try {
            String id = api.broadcast(signed); pending.clear(); lastResult = "已提交 · " + id;
            ui(token, () -> { host.changed(); notice(signed.has("bridgeEth")?"PRL 转入交易已提交":"交易已提交", "等待主网确认。\n\n交易 ID\n" + id+(signed.has("bridgeEth")?"\n\nPRL 转账确认后仍需等待桥铸造 WPRL。请在行情的跨链进度查看；此次提交不代表跨链完成。":"")); });
        } catch (Exception e) {
            lastResult = "结果待确认";
            ui(token, () -> { host.changed(); notice("结果待确认", "已保存原签名交易。网络失败可能发生在服务收到交易之后，请在设置中查询结果，或重发同一交易。\n\n" + error(e)); });
        } finally { busy = false; }
    }
    void pendingStatus() {
        if (busy) return;
        try {
            JSONObject signed = pending.load();
            if (signed == null) { notice("没有待提交交易", lastResult.isEmpty() ? "链上收发状态可在交易记录中查看。" : lastResult); return; }
            String id = signed.getString("txid");
            show(new AlertDialog.Builder(activity).setTitle("结果待确认").setMessage("付款钱包 · " + host.name() + "\n" + address() + "\n\n交易 ID\n" + id + "\n\n查询结果会先检查主网是否已接收。重发会使用同一笔签名交易。")
                    .setNegativeButton("关闭", null).setNeutralButton("重发同一交易", (d,w) -> {
                        int token = generation; busy = true; worker.execute(() -> submit(signed, token));
                    }).setPositiveButton("查询结果", (d,w) -> {
                        busy = true; int token = generation;
                        worker.execute(() -> {
                            try {
                                if (api.transactionKnown(id)) { pending.clear(); lastResult = "主网已接收 · " + id; ui(token, () -> { host.changed(); notice("主网已接收", id); }); }
                                else ui(token, () -> notice("仍待确认", "保留原交易，可稍后重查或重发同一交易。"));
                            } catch (Exception e) { ui(token, () -> notice("仍待确认", "暂时无法确认接收状态。原交易仍保留。\n" + error(e))); }
                            finally { busy = false; }
                        });
                    }).create());
        } catch (Exception e) { notice("读取失败", error(e)); }
    }
    synchronized void resume() { active = true; recoveryTimeout.removeCallbacks(expireRecovery); }
    synchronized void pause() {
        active = false; generation++;
        publicTools.pause();
        if (dialog != null) { dialog.dismiss(); dialog = null; }
        if (!authWaiting) { afterAuth = null; clearRecovery(); }
        else if (recoveryEntropy != null) recoveryTimeout.postDelayed(expireRecovery, 120_000);
    }
    void close() { pause(); clearRecovery(); afterAuth = null; recoveryTimeout.removeCallbacks(expireRecovery); worker.shutdownNow(); }
}
