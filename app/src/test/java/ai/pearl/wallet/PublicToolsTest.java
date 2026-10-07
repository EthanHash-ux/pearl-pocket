package ai.pearl.wallet;

import org.json.JSONObject;
import org.junit.Test;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

public class PublicToolsTest {
    private static final String ADDRESS="prl1pr6yuq8u2r95wjzzgpdy8cpnncpl7l8zgy6x5q0367pnc53s2famqg7pt74";
    private static final class Memory implements PublicStore.Storage {
        final Map<String,String> data=new HashMap<>();boolean fail;
        public String read(String key){return data.getOrDefault(key,"[]");}
        public void write(String key,String value){if(fail)throw new IllegalStateException("disk failure");data.put(key,value);}
    }
    @Test public void paymentQrPreservesEveryGrainAndUppercaseAddresses(){
        String encoded=PaymentRequest.encode(ADDRESS,"1.00000001");PaymentRequest request=PaymentRequest.parse(encoded);
        assertEquals(ADDRESS,request.address);assertEquals(new BigInteger("100000001"),request.amount);
        assertNull(PaymentRequest.parse(ADDRESS.toUpperCase(java.util.Locale.ROOT)).amount);
        assertEquals(ADDRESS,PaymentRequest.parse("PEARL:"+ADDRESS+"?amount=0.00000001").address);
    }
    @Test public void paymentQrRejectsAmbiguousAndUnsupportedInputs(){
        String[] bad={"https://example.com/"+ADDRESS,"pearl://"+ADDRESS,"pearl:"+ADDRESS+"#fragment","pearl:"+ADDRESS+"?amount=1&amount=2","pearl:"+ADDRESS+"?amount=1&req-foo=2","pearl:"+ADDRESS+"?label=test","pearl:"+ADDRESS+"?amount=0","pearl:"+ADDRESS+"?amount=0.000000001","pearl:"+ADDRESS+"?amount=-1","pearl:"+ADDRESS+"?amount=1e2","pearl:"+ADDRESS+"?amount=%","prl1pbad"};
        for(String input:bad)assertThrows(input,IllegalArgumentException.class,()->PaymentRequest.parse(input));
    }
    @Test public void contactsValidatePersistEditAndDeleteWithoutDuplicates()throws Exception{
        Memory store=new Memory();AddressBook book=new AddressBook(store);AddressBook.Contact c=book.save(null," Alice ",ADDRESS.toUpperCase(java.util.Locale.ROOT));
        assertEquals("Alice",c.name);assertEquals(ADDRESS,c.address);assertEquals(1,new AddressBook(store).list().size());
        assertThrows(IllegalArgumentException.class,()->book.save(null,"Duplicate",ADDRESS));
        assertEquals("Bob",book.save(c.id,"Bob",ADDRESS).name);assertEquals(1,book.list().size());
        assertThrows(IllegalArgumentException.class,()->book.save(null,"bad\nname",ADDRESS));assertThrows(IllegalArgumentException.class,()->book.save(null,"Person","prl1pbad"));
        book.remove(c.id);assertTrue(book.list().isEmpty());assertThrows(IllegalArgumentException.class,()->book.save(c.id,"Gone",ADDRESS));
    }
    @Test public void alertsIgnoreStaleAndUnavailablePricesAndFireOnceAfterRestart()throws Exception{
        Memory store=new Memory();PriceAlerts alerts=new PriceAlerts(store);alerts.add("2.5",true);alerts.add("1",false);
        assertTrue(alerts.claim(new BigDecimal("3"),100,221,true).isEmpty());assertTrue(alerts.claim(new BigDecimal("3"),301,300,true).isEmpty());
        assertTrue(alerts.claim(null,300,300,true).isEmpty());assertTrue(alerts.claim(new BigDecimal("3"),300,300,false).isEmpty());
        assertEquals(1,alerts.claim(new BigDecimal("2.5"),300,300,true).size());
        assertTrue(new PriceAlerts(store).claim(new BigDecimal("9"),301,301,true).isEmpty());
        assertEquals(1,alerts.claim(new BigDecimal("0.9"),302,302,true).size());assertFalse(alerts.active());
    }
    @Test public void concurrentForegroundAndJobCannotDuplicateAnAlert()throws Exception{
        Memory store=new Memory();new PriceAlerts(store).add("1",true);CountDownLatch start=new CountDownLatch(1);AtomicInteger claims=new AtomicInteger();AtomicInteger failures=new AtomicInteger();
        Runnable check=()->{try{start.await();claims.addAndGet(new PriceAlerts(store).claim(new BigDecimal("2"),100,100,true).size());}catch(Exception e){failures.incrementAndGet();}};
        Thread a=new Thread(check),b=new Thread(check);a.start();b.start();start.countDown();a.join();b.join();assertEquals(0,failures.get());assertEquals(1,claims.get());
    }
    @Test public void alertCannotBeClaimedIfPersistenceFails()throws Exception{
        Memory store=new Memory();PriceAlerts alerts=new PriceAlerts(store);alerts.add("1",true);store.fail=true;
        assertThrows(IllegalStateException.class,()->alerts.claim(new BigDecimal("2"),100,100,true));store.fail=false;assertTrue(alerts.active());assertEquals(1,alerts.claim(new BigDecimal("2"),101,101,true).size());
    }
    @Test public void rearmingAnAlertPersistsTheSameThresholdAndAllowsOneMoreClaim()throws Exception{
        Memory store=new Memory();PriceAlerts alerts=new PriceAlerts(store);PriceAlerts.Alert a=alerts.add("2",true);assertEquals(1,alerts.claim(new BigDecimal("3"),100,100,true).size());
        assertFalse(alerts.active());alerts.rearm(a.id);PriceAlerts.Alert restored=new PriceAlerts(store).list().get(0);assertEquals(a.id,restored.id);assertEquals(0,restored.firedAt);assertTrue(restored.enabled);assertEquals(0,a.target.compareTo(restored.target));
        assertEquals(1,alerts.claim(new BigDecimal("3"),101,101,true).size());assertTrue(alerts.claim(new BigDecimal("3"),102,102,true).isEmpty());
        alerts.remove(a.id);assertThrows(IllegalArgumentException.class,()->alerts.rearm(a.id));
    }
    @Test public void profitIncludesPoolFeePowerRentAndBreakEvenPrice(){
        MiningProfit p=new MiningProfit("10","5","1000","0.5","2","3");
        assertEquals(0,new BigDecimal("49").compareTo(p.revenue));assertEquals(0,new BigDecimal("12").compareTo(p.electricity));assertEquals(0,new BigDecimal("34").compareTo(p.net));
        assertEquals(0,new BigDecimal("1020").compareTo(p.days30));assertEquals(0,new BigDecimal("3060").compareTo(p.days90));
        MiningProfit breakEven=new MiningProfit("10",p.breakEvenPrice.toPlainString().substring(0,10),"1000","0.5","2","3");assertTrue(breakEven.net.abs().compareTo(new BigDecimal("0.000001"))<0);
        assertThrows(IllegalArgumentException.class,()->new MiningProfit("1","1","1","1","100","0"));
        assertThrows(IllegalArgumentException.class,()->new MiningProfit("1","1","-1","1","0","0"));
    }
    @Test public void zeroOutputAndLossScenariosRemainExplicit(){
        MiningProfit p=new MiningProfit("0","1","500","1","0","1");assertNull(p.breakEvenPrice);assertEquals(0,new BigDecimal("-13").compareTo(p.net));
    }
    private String poolJson()throws Exception{
        return new JSONObject().put("config",new JSONObject().put("coin","pearl").put("symbol","PRL").put("coinUnits",100000000).put("fee",1))
                .put("pool",new JSONObject().put("hashrate",9999999).put("totalRealHashrate","2000000000000000000").put("miners",10).put("soloMiners",2).put("workers",20).put("soloWorkers",3))
                .put("network",new JSONObject().put("networkHashps","10000000000000000000").put("height",123)).toString();
    }
    @Test public void poolComparesPhysicalRatesAndIncludesSoloWorkers()throws Exception{
        MiningApi.Stats stats=MiningApi.parse(poolJson(),100);assertEquals("2.00 EH/s",MiningApi.rate(stats.hashRate));assertEquals(0,new BigDecimal("20").compareTo(stats.share()));assertEquals(12,stats.miners);assertEquals(23,stats.workers);assertEquals(100,stats.receivedAt);
    }
    @Test public void poolRejectsWrongCoinAndInconsistentTotals()throws Exception{
        JSONObject json=new JSONObject(poolJson());json.getJSONObject("config").put("symbol","OTHER");assertThrows(IllegalArgumentException.class,()->MiningApi.parse(json.toString(),100));
        json.getJSONObject("config").put("symbol","PRL");json.getJSONObject("network").put("networkHashps",0);assertThrows(IllegalArgumentException.class,()->MiningApi.parse(json.toString(),100));
        json.getJSONObject("network").put("networkHashps",1);assertThrows(IllegalArgumentException.class,()->MiningApi.parse(json.toString(),100));
    }
}
