package ai.pearl.wallet;

import org.json.JSONArray;
import org.json.JSONObject;
import java.math.BigDecimal;
import java.math.MathContext;

/** BigONE PRL-USDT is identified by the full market, never by ambiguous ticker alone. */
final class MarketQuote {
    final BigDecimal usdt, change24h;
    final long receivedAt;
    MarketQuote(BigDecimal usdt,BigDecimal change24h,long receivedAt){this.usdt=usdt;this.change24h=change24h;this.receivedAt=receivedAt;}
    static BigDecimal positive(JSONObject json,String key) throws Exception {
        BigDecimal n=new BigDecimal(json.getString(key));
        if(n.signum()<=0 || n.precision()>80 || n.compareTo(new BigDecimal("1000000000000"))>0)throw new IllegalArgumentException("行情数值无效");return n;
    }
    private static MarketQuote parse(JSONObject ticker,String marketKey,long receivedAt)throws Exception {
        if(!"PRL-USDT".equals(ticker.getString(marketKey)))throw new IllegalArgumentException("行情交易对不是 Pearl PRL/USDT");
        BigDecimal close=positive(ticker,"close"),open=positive(ticker,"open");
        return new MarketQuote(close,close.subtract(open).divide(open,MathContext.DECIMAL64).movePointRight(2),receivedAt);
    }
    static MarketQuote rest(JSONObject json,long receivedAt)throws Exception {
        if(json.getInt("code")!=0)throw new IllegalArgumentException("交易所行情暂不可用");return parse(json.getJSONObject("data"),"asset_pair_name",receivedAt);
    }
    static MarketQuote stream(JSONObject json,long receivedAt)throws Exception {
        if(json.has("error"))throw new IllegalArgumentException("交易所拒绝行情订阅");
        if(json.has("tickerUpdate"))return parse(json.getJSONObject("tickerUpdate").getJSONObject("ticker"),"market",receivedAt);
        if(json.has("tickersSnapshot")) {
            JSONArray tickers=json.getJSONObject("tickersSnapshot").getJSONArray("tickers");
            if(tickers.length()>64)throw new IllegalArgumentException("行情响应过大");
            for(int i=0;i<tickers.length();i++){JSONObject ticker=tickers.getJSONObject(i);if("PRL-USDT".equals(ticker.getString("market")))return parse(ticker,"market",receivedAt);}
        }
        return null;
    }
    static final class Fx {
        final BigDecimal usd,cny;final long updatedAt;
        Fx(BigDecimal usd,BigDecimal cny,long updatedAt){this.usd=usd;this.cny=cny;this.updatedAt=updatedAt;}
        boolean fresh(long now){return now-updatedAt>=-60 && now-updatedAt<=1800;}
        static Fx parse(JSONObject response,long now)throws Exception{
            JSONObject tether=response.getJSONObject("tether");Fx fx=new Fx(positive(tether,"usd"),positive(tether,"cny"),tether.getLong("last_updated_at"));
            if(!fx.fresh(now))throw new IllegalArgumentException("计价汇率已过期");return fx;
        }
    }
    PearlApi.Price convert(Fx fx,boolean live,long now){
        boolean usable=fx!=null&&fx.fresh(now);
        return new PearlApi.Price(usable?usdt.multiply(fx.usd):null,usable?usdt.multiply(fx.cny):null,usdt,change24h,receivedAt,"BigONE",live);
    }
}
