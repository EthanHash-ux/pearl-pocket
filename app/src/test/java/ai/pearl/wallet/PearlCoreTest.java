package ai.pearl.wallet;

import org.json.JSONObject;
import org.junit.Test;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Locale;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.MultiFormatWriter;
import com.google.zxing.RGBLuminanceSource;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.common.HybridBinarizer;
import static org.junit.Assert.*;

public class PearlCoreTest {
    private static final String ADDRESS = "prl1paxv6dqey9v0a38eevuu547sllv0h7rst00x5tkpnu4lptl9rna6sdt4ggl";

    @Test public void smallestUnitAndSupplyStayExact() {
        assertEquals(BigInteger.ONE, PearlAmount.parsePositivePrl("0.00000001"));
        assertEquals("0.00000001", PearlAmount.format(BigInteger.ONE));
        assertEquals("2100000000", PearlAmount.format(PearlAmount.MAX_GRAINS));
        assertEquals(PearlAmount.MAX_GRAINS, PearlAmount.parsePositivePrl("2100000000.00000000"));
        assertEquals("-0.00000001", PearlAmount.format(PearlAmount.parseGrains("-1")));
        assertEquals("$1.01", PearlAmount.fiat(PearlAmount.parsePositivePrl("1"), new BigDecimal("1.005"), "usd"));
    }

    @Test public void invalidAmountsCannotBeRoundedOrMisinterpreted() {
        for (String value : new String[]{"0", "-1", "0.000000001", "1e8", "NaN", "Infinity", "1,000", "01", "1.", "2100000000.00000001"}) {
            assertThrows(value, IllegalArgumentException.class, () -> PearlAmount.parsePositivePrl(value));
        }
        assertThrows(IllegalArgumentException.class, () -> PearlAmount.parseGrains("210000000000000001"));
    }

    @Test public void mainnetTaprootAndUppercaseWork() {
        assertEquals(ADDRESS, PearlAddress.normalize(ADDRESS));
        assertEquals(ADDRESS, PearlAddress.normalize(" " + ADDRESS.toUpperCase(Locale.ROOT) + " "));
    }

    @Test public void mutatedChecksumsAndWrongNetworksAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> PearlAddress.normalize(ADDRESS.substring(0, 62) + "q"));
        assertThrows(IllegalArgumentException.class, () -> PearlAddress.normalize("P" + ADDRESS.substring(1)));
        assertThrows(IllegalArgumentException.class, () -> PearlAddress.normalize("t" + ADDRESS));
        assertThrows(IllegalArgumentException.class, () -> PearlAddress.normalize(ADDRESS.replace('x', 'b')));
        assertThrows(IllegalArgumentException.class, () -> PearlAddress.normalize("bc1p" + ADDRESS.substring(5)));
    }

    @Test public void priceUsesCorrectCoinAndRejectsAbsentOrBadValues() throws Exception {
        PearlApi.Price price = PearlApi.parsePrice(new JSONObject("{\"pearl-2\":{\"usd\":1.3,\"cny\":8.71,\"usd_24h_change\":14.006,\"last_updated_at\":1791367480}}"));
        assertEquals(new BigDecimal("1.3"), price.usd);
        assertEquals(new BigDecimal("8.71"), price.value("cny"));
        assertTrue(price.isFresh(1791367490)); assertFalse(price.isFresh(1791368400));
        assertFalse(price.isFresh(1791360000));
        assertThrows(Exception.class, () -> PearlApi.parsePrice(new JSONObject("{\"pearl-research\":{\"usd\":1}}")));
        assertThrows(Exception.class, () -> PearlApi.parsePrice(new JSONObject("{\"pearl-2\":{\"usd\":0,\"cny\":1,\"usd_24h_change\":null,\"last_updated_at\":1}}")));
    }

    @Test public void serviceIdentityAndSyncAreCheckedBeforeAddressQuery() throws Exception {
        JSONObject mainnet = new JSONObject("{\"blockbook\":{\"coin\":\"Pearl\",\"decimals\":8,\"bestHeight\":124475,\"inSync\":true,\"initialSync\":false},\"backend\":{\"chain\":\"mainnet\"}}");
        assertTrue(PearlApi.parseStatus(mainnet).synced);
        mainnet.getJSONObject("backend").put("chain", "testnet");
        assertThrows(IllegalArgumentException.class, () -> PearlApi.parseStatus(mainnet));
        mainnet.getJSONObject("backend").put("chain", "mainnet");
        mainnet.getJSONObject("blockbook").put("inSync", false);
        PearlApi api = new PearlApi(url -> { assertEquals(PearlApi.BLOCKBOOK, url); return mainnet.toString(); });
        assertThrows(IllegalArgumentException.class, () -> api.account(ADDRESS));
    }

    @Test public void transactionNetIncludesChangeWithoutDoubleCountingIt() throws Exception {
        JSONObject json = new JSONObject("{\"address\":\"" + ADDRESS + "\",\"balance\":\"8000\",\"unconfirmedBalance\":\"-1\",\"txs\":1,\"transactions\":[{\"txid\":\"" + "a".repeat(64)
                + "\",\"confirmations\":2,\"blockTime\":1791367480,\"vin\":[{\"addresses\":[\"" + ADDRESS + "\"],\"value\":\"50000\"}],\"vout\":[{\"addresses\":[\"" + ADDRESS + "\"],\"value\":\"8000\"},{\"addresses\":[\"another-address\"],\"value\":\"40000\"}]}]}");
        PearlApi.Account account = PearlApi.parseAccount(json, ADDRESS);
        assertEquals(new BigInteger("-42000"), account.transactions.get(0).netGrains);
        assertEquals(new BigInteger("8000"), account.balance);
        assertEquals(BigInteger.ONE.negate(), account.unconfirmed);
        json.put("address", "wrong");
        assertThrows(IllegalArgumentException.class, () -> PearlApi.parseAccount(json, ADDRESS));
    }

    @Test public void chartRejectsUnorderedOrInvalidData() throws Exception {
        PearlApi api = new PearlApi(url -> "{\"prices\":[[1000,1.2],[2000,1.3]]}");
        assertEquals(2, api.chart(1, "usd").size());
        assertThrows(IllegalArgumentException.class, () -> api.chart(30, "usd"));
        assertThrows(IllegalArgumentException.class, () -> new PearlApi(url -> "{\"prices\":[[1000,1],[1000,2]]}").chart(1, "usd"));
        assertThrows(IllegalArgumentException.class, () -> new PearlApi(url -> "{\"prices\":[[1000,0],[2000,2]]}").chart(1, "usd"));
    }

    @Test public void cleartextAndUnexpectedHostsAreRejected() {
        for (String url : new String[]{"http://blockbook.pearlresearch.ai/api/v2/", "https://evil.example/api/v2/", "https://user@blockbook.pearlresearch.ai/api/v2/", "https://blockbook.pearlresearch.ai:444/api/v2/"}) {
            assertThrows(IllegalArgumentException.class, () -> PearlApi.httpsGet(url));
        }
    }

    @Test public void receiveQrRoundTripsCompleteAddress() throws Exception {
        BitMatrix matrix = new MultiFormatWriter().encode(ADDRESS, BarcodeFormat.QR_CODE, 300, 300);
        int[] pixels = new int[90000];
        for (int y = 0; y < 300; y++) for (int x = 0; x < 300; x++) pixels[y * 300 + x] = matrix.get(x, y) ? 0xff0c6155 : 0xffffffff;
        BinaryBitmap bitmap = new BinaryBitmap(new HybridBinarizer(new RGBLuminanceSource(300, 300, pixels)));
        assertEquals(ADDRESS, new MultiFormatReader().decode(bitmap).getText());
    }

    @Test public void feeConversionAndPreviousTransactionsStayExact() throws Exception {
        String id = "a".repeat(64), recipient = "prl1pr6yuq8u2r95wjzzgpdy8cpnncpl7l8zgy6x5q0367pnc53s2famqg7pt74";
        PearlApi api = new PearlApi(url -> {
            if (url.equals(PearlApi.BLOCKBOOK)) return "{\"blockbook\":{\"coin\":\"Pearl\",\"decimals\":8,\"bestHeight\":10,\"inSync\":true,\"initialSync\":false},\"backend\":{\"chain\":\"mainnet\"}}";
            if (url.endsWith("estimatefee/6")) return "{\"result\":\"0.00010031\"}";
            if (url.contains("utxo/")) return "[{\"txid\":\""+id+"\",\"vout\":0,\"confirmations\":12}]";
            if (url.endsWith("tx/"+id)) return "{\"txid\":\""+id+"\",\"hex\":\"0200\",\"confirmations\":10}";
            throw new AssertionError("Unexpected endpoint");
        });
        JSONObject payment = api.payment(ADDRESS,recipient,BigInteger.ONE);
        assertEquals(10031,payment.getLong("rate")); assertEquals("1",payment.getString("amount"));
        assertEquals(10,payment.getJSONArray("utxos").getJSONObject(0).getLong("confirmations"));
        assertEquals("0200",payment.getJSONArray("utxos").getJSONObject(0).getString("raw"));
    }

    @Test public void broadcastRequiresExpectedTransactionIdAndNeverRetries() throws Exception {
        JSONObject signed = new JSONObject().put("raw","010203").put("txid","a".repeat(64));
        java.util.concurrent.atomic.AtomicInteger attempts = new java.util.concurrent.atomic.AtomicInteger();
        PearlApi api = new PearlApi(url -> "{}", (url,raw) -> {
            assertEquals(PearlApi.BLOCKBOOK+"sendtx/",url); assertEquals("010203",raw); attempts.incrementAndGet();
            return "{\"result\":\""+"b".repeat(64)+"\"}";
        });
        assertThrows(java.io.IOException.class, () -> api.broadcast(signed)); assertEquals(1,attempts.get());
        PearlApi accepted = new PearlApi(url -> "{}", (url,raw) -> "{\"result\":\""+"a".repeat(64)+"\"}");
        assertEquals("a".repeat(64),accepted.broadcast(signed));
        assertThrows(IllegalArgumentException.class, () -> PearlApi.httpsPost("https://evil.example/sendtx", "010203"));
    }
}
