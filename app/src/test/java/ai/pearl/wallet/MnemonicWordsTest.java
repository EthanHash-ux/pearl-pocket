package ai.pearl.wallet;

import org.junit.Test;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import static org.junit.Assert.*;

public class MnemonicWordsTest {
    private MnemonicWords words() throws Exception {
        List<String> dictionary=new ArrayList<>();
        try(BufferedReader r=new BufferedReader(new InputStreamReader(getClass().getResourceAsStream("/bip39-english.txt"),StandardCharsets.UTF_8))){String line;while((line=r.readLine())!=null)dictionary.add(line);}
        return new MnemonicWords(dictionary);
    }
    @Test public void allFiveBip39ZeroEntropyVectorsValidateAndRejectBadChecksums()throws Exception{
        MnemonicWords words=words();int[] counts={12,15,18,21,24};String[] lastWords={"about","address","agent","admit","art"};
        for(int i=0;i<counts.length;i++){
            List<String> phrase=new ArrayList<>(Collections.nCopies(counts[i],"abandon"));phrase.set(counts[i]-1,lastWords[i]);
            assertTrue("Valid "+counts[i]+"-word fixture rejected",words.validate(phrase).valid);
            phrase.set(counts[i]-1,"abandon");assertFalse("Invalid checksum accepted at "+counts[i]+" words",words.validate(phrase).valid);
        }
    }
    @Test public void unsupportedWordCountsAreRejected()throws Exception{
        MnemonicWords words=words();
        for(int count=0;count<=30;count++)if(count<12||count>24||count%3!=0){
            assertFalse(MnemonicWords.supportedCount(count));
            assertFalse(words.validate(Collections.nCopies(count,"abandon")).valid);
        }
    }
    @Test public void partialAndMisspelledWordsAreIdentifiedByPosition()throws Exception{
        MnemonicWords words=words();List<String> phrase=new ArrayList<>(Collections.nCopies(24,""));phrase.set(4,"abndon");
        MnemonicWords.Validation invalid=words.validate(phrase);assertFalse(invalid.valid);assertEquals(4,invalid.firstInvalid);assertEquals(1,invalid.filled);
        phrase.set(4,"abandon");assertEquals(-1,words.validate(phrase).firstInvalid);assertFalse(words.validate(phrase).valid);
        assertFalse(words.contains("abndon"));assertTrue(words.contains(" ABANDON "));
    }
    @Test public void numberedAndPunctuatedPastesKeepOrderAndDetectExtraWords()throws Exception{
        List<String> normalized=MnemonicWords.split("1. ABANDON， 2. ability\n3、able；4) about\t");
        assertEquals(java.util.Arrays.asList("abandon","ability","able","about"),normalized);
        assertEquals(java.util.Arrays.asList("abandon","about"),MnemonicWords.split("ABANDON\u3000ABOUT"));
        assertFalse(words().validate(Collections.nCopies(13,"abandon")).valid);
        assertFalse(words().contains("abandon1"));
    }
}
