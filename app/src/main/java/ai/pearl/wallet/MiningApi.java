package ai.pearl.wallet;

import org.json.JSONObject;
import java.math.BigDecimal;

/** Read-only HeroMiners public pool totals. Never sends a wallet address or signs anything. */
final class MiningApi {
    static final String URL="https://pearl.herominers.com/api/stats";
    static final class Stats {
        final BigDecimal hashRate, networkHashRate, fee;
        final long miners, workers, height, receivedAt;
        Stats(BigDecimal h,BigDecimal n,BigDecimal f,long m,long w,long height,long at){hashRate=h;networkHashRate=n;fee=f;miners=m;workers=w;this.height=height;receivedAt=at;}
        BigDecimal share(){return hashRate.multiply(new BigDecimal("100")).divide(networkHashRate,java.math.MathContext.DECIMAL64);}
    }
    static Stats parse(String json,long now)throws Exception{
        JSONObject root=new JSONObject(json),config=root.getJSONObject("config"),pool=root.getJSONObject("pool"),network=root.getJSONObject("network");
        if(!"pearl".equals(config.getString("coin"))||!"PRL".equals(config.getString("symbol"))||config.getLong("coinUnits")!=100000000)throw new IllegalArgumentException("矿池返回的币种不是 Pearl");
        // The provider exposes internal share-rate `hashrate` and physical `totalRealHashrate`.
        // Compare physical rates only, including solo workers in the pool total.
        BigDecimal h=number(pool,"totalRealHashrate",false),n=number(network,"networkHashps",true),f=number(config,"fee",false);
        if(f.compareTo(new BigDecimal("100"))>=0||h.compareTo(n)>0)throw new IllegalArgumentException("矿池统计不一致，请稍后刷新");
        long miners=count(pool,"miners")+count(pool,"soloMiners"),workers=count(pool,"workers")+count(pool,"soloWorkers");
        return new Stats(h,n,f,miners,workers,count(network,"height"),now);
    }
    private static long count(JSONObject json,String key)throws Exception{long value=json.getLong(key);if(value<0||value>1000000000000L)throw new IllegalArgumentException("矿池统计值无效");return value;}
    private static BigDecimal number(JSONObject json,String key,boolean positive)throws Exception{BigDecimal n=new BigDecimal(json.get(key).toString());if(n.signum()<0||(positive&&n.signum()==0)||n.precision()>80||n.compareTo(new BigDecimal("1e40"))>0)throw new IllegalArgumentException("矿池统计值无效");return n;}
    Stats stats()throws Exception{return parse(PearlApi.httpsGet(URL),System.currentTimeMillis()/1000);}
    static String rate(BigDecimal n){String[] unit={"H/s","kH/s","MH/s","GH/s","TH/s","PH/s","EH/s"};int i=0;while(n.compareTo(new BigDecimal("1000"))>=0&&i<unit.length-1){n=n.movePointLeft(3);i++;}return n.setScale(2,java.math.RoundingMode.HALF_UP).toPlainString()+" "+unit[i];}
}
