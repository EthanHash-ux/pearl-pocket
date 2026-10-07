package ai.pearl.wallet;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.security.MessageDigest;

/** Offline BIP39 validation; only the bundled English dictionary is consulted. */
final class MnemonicWords {
    private final List<String> words;
    MnemonicWords(List<String> words) {
        if (words.size() != 2048) throw new IllegalArgumentException("助记词词库不完整");
        this.words = Collections.unmodifiableList(new ArrayList<>(words));
    }
    List<String> dictionary() { return words; }
    static boolean supportedCount(int count) { return count >= 12 && count <= 24 && count % 3 == 0; }
    static String normalizeWord(String text) { return Normalizer.normalize(text, Normalizer.Form.NFKD).trim().toLowerCase(Locale.ROOT); }
    static List<String> split(String text) {
        String normalized = Normalizer.normalize(text, Normalizer.Form.NFKD).toLowerCase(Locale.ROOT)
                .replaceAll("(?m)(^|[\\s,;，；])\\d{1,2}\\s*[.)、:：-]\\s*", "$1")
                .replaceAll("[\\s\\p{Z},;，；]+", " ").trim();
        return normalized.isEmpty() ? Collections.emptyList() : Arrays.asList(normalized.split(" "));
    }
    boolean contains(String word) { return Collections.binarySearch(words, normalizeWord(word)) >= 0; }
    static final class Validation {
        final boolean valid; final int filled, firstInvalid; final String message;
        Validation(boolean valid, int filled, int firstInvalid, String message) { this.valid=valid; this.filled=filled; this.firstInvalid=firstInvalid; this.message=message; }
    }
    Validation validate(List<String> phrase) {
        int count = phrase.size(), filled = 0, invalid = -1;
        if (!supportedCount(count)) return new Validation(false,0,-1,"请选择 12、15、18、21 或 24 个 BIP39 英文助记词");
        int[] indexes = new int[count];
        for (int i=0;i<count;i++) {
            String word=normalizeWord(phrase.get(i)); if (!word.isEmpty()) filled++;
            indexes[i]=Collections.binarySearch(words,word); if (!word.isEmpty() && indexes[i]<0 && invalid<0) invalid=i;
        }
        if (invalid>=0) { Arrays.fill(indexes,0); return new Validation(false,filled,invalid,"第 "+(invalid+1)+" 个词不在英文词库中，请检查拼写"); }
        if (filled<count) { Arrays.fill(indexes,0); return new Validation(false,filled,-1,"已填写 "+filled+" / "+count+" 个词"); }
        int entropyBits=count*11*32/33, checksumBits=entropyBits/32;
        byte[] entropy=new byte[entropyBits/8];
        try {
            for(int bit=0;bit<entropyBits;bit++) if (((indexes[bit/11] >>> (10-bit%11))&1)!=0) entropy[bit/8]|=(byte)(1<<(7-bit%8));
            byte[] hash=MessageDigest.getInstance("SHA-256").digest(entropy);
            int checksum=indexes[count-1]&((1<<checksumBits)-1), expected=(hash[0]&255) >>> (8-checksumBits);
            Arrays.fill(hash,(byte)0);
            return new Validation(checksum==expected,filled,-1,checksum==expected ? "助记词校验通过，可继续" : "校验未通过，请核对助记词的顺序和拼写");
        } catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException("系统不支持 SHA-256",e); }
        finally { Arrays.fill(entropy,(byte)0); Arrays.fill(indexes,0); }
    }
}
