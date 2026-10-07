package ai.pearl.wallet;

import org.json.JSONObject;
import org.junit.Test;
import java.math.BigDecimal;
import static org.junit.Assert.*;

public class MarketQuoteTest {
    private JSONObject ticker(String marketKey,String close)throws Exception{
        return new JSONObject().put(marketKey,"PRL-USDT").put("open","1.1").put("close",close);
    }
    @Test public void snapshotAndPushedUpdatesChangeTheQuoteExactly()throws Exception{
        JSONObject snapshot=new JSONObject().put("tickersSnapshot",new JSONObject().put("tickers",new org.json.JSONArray().put(ticker("market","1.2100"))));
        MarketQuote first=MarketQuote.stream(snapshot,100);assertEquals(new BigDecimal("1.2100"),first.usdt);assertEquals(0,first.change24h.compareTo(new BigDecimal("10")));
        MarketQuote update=MarketQuote.stream(new JSONObject().put("tickerUpdate",new JSONObject().put("ticker",ticker("market","1.2345"))),101);
        assertEquals(new BigDecimal("1.2345"),update.usdt);assertEquals(101,update.receivedAt);
        assertNull(MarketQuote.stream(new JSONObject().put("requestId","unrelated"),102));
    }
    @Test public void wrongMarketAndInvalidPricesAreRejected()throws Exception{
        JSONObject wrong=ticker("market","1.2").put("market","PERLE-USDT");
        assertThrows(IllegalArgumentException.class,()->MarketQuote.stream(new JSONObject().put("tickerUpdate",new JSONObject().put("ticker",wrong)),100));
        assertThrows(IllegalArgumentException.class,()->MarketQuote.rest(new JSONObject().put("code",0).put("data",ticker("asset_pair_name","0")),100));
        assertThrows(IllegalArgumentException.class,()->MarketQuote.stream(new JSONObject().put("error",new JSONObject().put("code",45000)),100));
    }
    @Test public void fiatConversionIsIndependentAndNeverAssumesUsdtIsUsd()throws Exception{
        MarketQuote quote=MarketQuote.rest(new JSONObject().put("code",0).put("data",ticker("asset_pair_name","1.2345")),2000);
        PearlApi.Price noFx=quote.convert(null,true,2000);assertNull(noFx.usd);assertNull(noFx.cny);assertEquals("usdt",noFx.displayCurrency("cny"));assertTrue(noFx.live);
        MarketQuote.Fx fx=MarketQuote.Fx.parse(new JSONObject().put("tether",new JSONObject().put("usd","0.9987").put("cny","6.70").put("last_updated_at",2000)),2000);
        PearlApi.Price converted=quote.convert(fx,true,2000);assertEquals(0,converted.usd.compareTo(new BigDecimal("1.23289515")));assertEquals(0,converted.cny.compareTo(new BigDecimal("8.27115")));
        assertEquals("usd",converted.displayCurrency("usd"));assertNull(quote.convert(fx,false,4000).usd);
        assertFalse(converted.isFresh(2121));assertFalse(converted.isFresh(1900));
    }
    @Test public void exchangeCandleTimesAreSortedAndInvalidDataRejected()throws Exception{
        PearlApi api=new PearlApi(url->{assertTrue(url.contains("PRL-USDT/candles"));return "{\"code\":0,\"data\":[{\"time\":\"2026-10-07T12:05:00Z\",\"close\":\"1.2345\"},{\"time\":\"2026-10-07T12:00:00Z\",\"close\":\"1.2\"}]}";});
        java.util.List<double[]> chart=api.marketChart(1);assertTrue(chart.get(0)[0]<chart.get(1)[0]);assertEquals(1.2345,chart.get(1)[1],0.000001);
        assertThrows(IllegalArgumentException.class,()->api.marketChart(31));
    }
    @Test public void textAndCompressedFramesDecodeButOversizedInflationIsRejected()throws Exception{
        String text="{\"tickerUpdate\":{\"ticker\":{\"market\":\"PRL-USDT\",\"open\":\"1.1\",\"close\":\"1.21\"}}}";
        assertEquals(text,MarketFeed.decode(okio.ByteString.encodeUtf8(text)));
        java.io.ByteArrayOutputStream bytes=new java.io.ByteArrayOutputStream();
        try(java.util.zip.GZIPOutputStream gzip=new java.util.zip.GZIPOutputStream(bytes)){gzip.write(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));}
        assertEquals(text,MarketFeed.decode(okio.ByteString.of(bytes.toByteArray())));
        bytes.reset();try(java.util.zip.GZIPOutputStream gzip=new java.util.zip.GZIPOutputStream(bytes)){gzip.write(new byte[512_001]);}
        assertThrows(IllegalArgumentException.class,()->MarketFeed.decode(okio.ByteString.of(bytes.toByteArray())));
    }
}
