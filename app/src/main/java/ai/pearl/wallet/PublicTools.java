package ai.pearl.wallet;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.os.Build;
import android.text.InputType;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.function.Consumer;

/** Public-data tools share the app's style but have no access to wallet secrets. */
final class PublicTools {
    static final int NOTIFICATIONS=4211;
    private final Activity activity;
    private final AddressBook contacts;
    private final PriceAlerts alerts;
    private AlertDialog dialog;
    PublicTools(Activity activity){this.activity=activity;PublicStore.Storage store=PublicStore.of(activity);contacts=new AddressBook(store);alerts=new PriceAlerts(store);}
    private LinearLayout form(){LinearLayout view=new LinearLayout(activity);view.setOrientation(LinearLayout.VERTICAL);int p=(int)(20*activity.getResources().getDisplayMetrics().density);view.setPadding(p,p/2,p,p/2);return view;}
    private TextView text(String value){TextView view=new TextView(activity);view.setText(value);view.setTextSize(14);view.setPadding(0,12,0,12);return view;}
    private EditText field(String hint,String value,boolean numeric){EditText e=new EditText(activity);e.setHint(hint);e.setContentDescription(hint);e.setText(value);e.setInputType(numeric?InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_FLAG_DECIMAL:InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);e.setImportantForAutofill(android.view.View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);return e;}
    private Button button(String value,Runnable action){Button b=new Button(activity);b.setText(value);b.setAllCaps(false);b.setOnClickListener(v->action.run());return b;}
    private void show(AlertDialog value){if(dialog!=null)dialog.dismiss();dialog=value;value.show();value.getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE);}
    private void show(String title,LinearLayout body){ScrollView scroll=new ScrollView(activity);scroll.addView(body);show(new AlertDialog.Builder(activity).setTitle(title).setView(scroll).setNegativeButton("关闭",null).create());}
    private void failure(Exception e){new AlertDialog.Builder(activity).setTitle("请检查输入").setMessage(e.getMessage()==null?"本机数据无法读取，请重试":e.getMessage()).setPositiveButton("知道了",null).show();}
    void addressBook(Consumer<String> selected){
        try {
            LinearLayout view=form();view.addView(text("联系人仅保存在本机。选择后仍需核对完整收款地址。"));
            List<AddressBook.Contact> list=contacts.list();if(list.isEmpty())view.addView(text("还没有联系人"));
            for(AddressBook.Contact c:list){
                view.addView(text(c.name+"\n"+c.address));
                if(selected!=null)view.addView(button("使用 "+c.name,()->{dialog.dismiss();selected.accept(c.address);}));
                view.addView(button("编辑 "+c.name,()->editContact(c.id,c.name,c.address,selected)));
                view.addView(button("删除 "+c.name,()->new AlertDialog.Builder(activity).setTitle("删除联系人？").setMessage(c.name+"\n"+c.address)
                        .setNegativeButton("取消",null).setPositiveButton("删除",(d,w)->{try{contacts.remove(c.id);addressBook(selected);}catch(Exception e){failure(e);}}).show()));
            }
            view.addView(button("添加联系人",()->editContact(null,"","",selected)));show("地址簿",view);
        }catch(Exception e){failure(e);}
    }
    void saveContact(String address){editContact(null,"",address,null);}
    private void editContact(String id,String name,String address,Consumer<String> selected){
        LinearLayout view=form();EditText n=field("联系人名称",name,false),a=field("联系人完整 Pearl 主网地址",address,false);view.addView(n);view.addView(a);
        AlertDialog d=new AlertDialog.Builder(activity).setTitle(id==null?"添加联系人":"编辑联系人").setView(view).setNegativeButton("取消",null).setPositiveButton("保存联系人",null).create();show(d);
        d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{try{contacts.save(id,n.getText().toString(),a.getText().toString());addressBook(selected);}catch(Exception e){a.setError(e.getMessage());}});
    }
    void priceAlerts(){
        try{
            LinearLayout view=form();view.addView(text("PRL/USDT · BigONE\n前台随新行情检查，后台约每 15 分钟检查。省电、网络和系统调度可能延迟，强制停止后需重新打开应用。每条提醒仅触发一次。"));
            view.addView(text(AlertNotifications.available(activity)?"系统通知已启用":"通知未启用：提醒保留，暂不触发。可在系统设置中启用通知。"));
            if(alerts.active())view.addView(text(AlertNotifications.backgroundScheduled(activity)?"后台检查已安排":"后台检查暂未安排；应用前台仍会随新行情检查。请启用通知并重新打开应用。"));
            List<PriceAlerts.Alert> list=alerts.list();if(list.isEmpty())view.addView(text("暂无价格提醒"));
            for(PriceAlerts.Alert a:list){view.addView(text(a.description()+"\n"+(a.enabled?"等待触发":"已触发 · "+new java.text.SimpleDateFormat("MM/dd HH:mm",java.util.Locale.CHINA).format(new java.util.Date(a.firedAt*1000)))));view.addView(button("删除提醒 "+a.target.toPlainString(),()->{try{alerts.remove(a.id);AlertNotifications.schedule(activity);priceAlerts();}catch(Exception e){failure(e);}}));}
            view.addView(button("添加价格提醒",this::addAlert));show("价格提醒",view);
        }catch(Exception e){failure(e);}
    }
    private void addAlert(){
        LinearLayout view=form();EditText target=field("目标价格（USDT）","",true);view.addView(target);
        android.widget.RadioGroup direction=new android.widget.RadioGroup(activity);android.widget.RadioButton above=new android.widget.RadioButton(activity),below=new android.widget.RadioButton(activity);
        above.setId(android.view.View.generateViewId());below.setId(android.view.View.generateViewId());above.setText("达到 / 高于目标价格");below.setText("达到 / 低于目标价格");direction.addView(above);direction.addView(below);above.setChecked(true);view.addView(direction);
        view.addView(text("保存后从下一次新报价开始检查。如果已达到目标，会立即触发一次。"));
        AlertDialog d=new AlertDialog.Builder(activity).setTitle("新建价格提醒").setView(view).setNegativeButton("取消",null).setPositiveButton("保存提醒",null).create();show(d);
        d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{try{alerts.add(target.getText().toString(),above.isChecked());d.dismiss();
            if(Build.VERSION.SDK_INT>=33 && activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=android.content.pm.PackageManager.PERMISSION_GRANTED)activity.requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},NOTIFICATIONS);
            else {AlertNotifications.schedule(activity);priceAlerts();}
        }catch(Exception e){target.setError(e.getMessage());}});
    }
    void profit(BigDecimal currentCny){
        LinearLayout view=form();view.addView(text("情景计算，不预测挖矿产出。输入你的费前日收益估计；价格、难度和产出变化会使实际结果不同。30 / 90 天按输入保持不变计算。"));
        EditText coins=field("预计费前 PRL / 天","",true),price=field("PRL 价格（CNY）",currentCny==null?"":currentCny.setScale(4,RoundingMode.HALF_UP).toPlainString(),true),watts=field("设备功耗（W）","",true),electricity=field("电价（CNY / kWh）","",true),fee=field("矿池费率（%）","0",true),rent=field("租金（CNY / 天）","0",true);
        view.addView(text(currentCny==null?"人民币行情暂不可用，请手动填写价格。":"已填入当前人民币参考价，可修改为你的预期价格。"));
        String[] names={"预计费前 PRL / 天","PRL 价格（CNY）","设备功耗（W）","电价（CNY / kWh）","矿池费率（%）","租金（CNY / 天）"};EditText[] fields={coins,price,watts,electricity,fee,rent};
        for(int i=0;i<fields.length;i++){view.addView(text(names[i]));view.addView(fields[i]);}TextView result=text("填写后计算净收益");view.addView(result);
        view.addView(button("计算收益",()->{try{MiningProfit p=new MiningProfit(coins.getText().toString(),price.getText().toString(),watts.getText().toString(),electricity.getText().toString(),fee.getText().toString(),rent.getText().toString());
            result.setText(activity.getString(R.string.profit_result,money(p.revenue),money(p.electricity),money(p.rent),money(p.net),money(p.days30),money(p.days90),p.breakEvenPrice==null?"无产出，无法计算":"¥"+money(p.breakEvenPrice)+" / PRL"));
        }catch(Exception e){result.setText(e.getMessage());}}));show("挖矿收益计算",view);
    }
    private String money(BigDecimal n){return n.setScale(2,RoundingMode.HALF_UP).toPlainString();}
    void pause(){if(dialog!=null){dialog.dismiss();dialog=null;}}
}
