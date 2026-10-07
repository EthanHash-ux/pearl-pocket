package ai.pearl.wallet;

import org.json.JSONArray;
import org.json.JSONObject;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** One-shot USDT thresholds, claimed under the same lock by the foreground and background job. */
final class PriceAlerts {
    static final class Alert {
        final String id; final BigDecimal target; final boolean above, enabled; final long firedAt;
        Alert(String id,BigDecimal target,boolean above,boolean enabled,long firedAt){this.id=id;this.target=target;this.above=above;this.enabled=enabled;this.firedAt=firedAt;}
        String description(){return (above?"达到 / 高于 ":"达到 / 低于 ")+target.toPlainString()+" USDT";}
    }
    private final PublicStore.Storage store;
    PriceAlerts(PublicStore.Storage store){this.store=store;}
    List<Alert> list() throws Exception { synchronized(PublicStore.LOCK){return read();} }
    private List<Alert> read() throws Exception {
        JSONArray json=new JSONArray(store.read("alerts"));if(json.length()>20)throw new IllegalArgumentException("提醒数据过大");
        List<Alert> result=new ArrayList<>();
        for(int i=0;i<json.length();i++){JSONObject a=json.getJSONObject(i);result.add(new Alert(a.getString("id"),target(a.getString("target")),a.getBoolean("above"),a.getBoolean("enabled"),a.optLong("firedAt")));}return result;
    }
    static BigDecimal target(String value){
        if(value==null||!value.matches("[0-9]{1,12}(\\.[0-9]{1,8})?"))throw new IllegalArgumentException("价格请输入正数，最多 8 位小数");
        BigDecimal target=new BigDecimal(value);if(target.signum()<=0||target.compareTo(new BigDecimal("1000000000000"))>=0)throw new IllegalArgumentException("提醒价格超出范围");return target.stripTrailingZeros();
    }
    Alert add(String value,boolean above)throws Exception{
        BigDecimal target=target(value);
        synchronized(PublicStore.LOCK){List<Alert> alerts=read();if(alerts.size()>=20)throw new IllegalArgumentException("最多保存 20 条提醒，请先删除旧提醒");Alert alert=new Alert(UUID.randomUUID().toString(),target,above,true,0);alerts.add(alert);write(alerts);return alert;}
    }
    void remove(String id)throws Exception{synchronized(PublicStore.LOCK){List<Alert> alerts=read();alerts.removeIf(a->a.id.equals(id));write(alerts);}}
    void rearm(String id)throws Exception{
        synchronized(PublicStore.LOCK){List<Alert> alerts=read();boolean found=false;
            for(int i=0;i<alerts.size();i++){Alert a=alerts.get(i);if(a.id.equals(id)){alerts.set(i,new Alert(a.id,a.target,a.above,true,0));found=true;break;}}
            if(!found)throw new IllegalArgumentException("提醒已删除，请重新打开提醒列表");write(alerts);
        }
    }
    boolean active()throws Exception{for(Alert a:list())if(a.enabled)return true;return false;}
    List<Alert> claim(BigDecimal usdt,long receivedAt,long now,boolean notificationsEnabled)throws Exception{
        List<Alert> fired=new ArrayList<>();
        if(!notificationsEnabled||usdt==null||usdt.signum()<=0||now-receivedAt<0||now-receivedAt>120)return fired;
        synchronized(PublicStore.LOCK){
            List<Alert> alerts=read(), updated=new ArrayList<>();
            for(Alert a:alerts){boolean match=a.enabled&&(a.above?usdt.compareTo(a.target)>=0:usdt.compareTo(a.target)<=0);
                if(match){Alert done=new Alert(a.id,a.target,a.above,false,now);fired.add(done);updated.add(done);}else updated.add(a);}
            if(!fired.isEmpty())write(updated); // Persist the claim before any notification; concurrent checks cannot duplicate it.
        }return fired;
    }
    private void write(List<Alert> alerts)throws Exception{
        JSONArray json=new JSONArray();for(Alert a:alerts)json.put(new JSONObject().put("id",a.id).put("target",a.target.toPlainString()).put("above",a.above).put("enabled",a.enabled).put("firedAt",a.firedAt));store.write("alerts",json.toString());
    }
}
