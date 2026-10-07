package ai.pearl.wallet;

import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.ArrayAdapter;
import android.widget.AutoCompleteTextView;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Numbered local dictionary suggestions and offline checksum validation. */
final class MnemonicEntryView extends LinearLayout {
    interface ChangeListener { void changed(boolean valid); }
    private final MnemonicWords vocabulary;
    private final AutoCompleteTextView[] fields = new AutoCompleteTextView[24];
    private final List<LinearLayout> rows = new ArrayList<>();
    private final TextView status;
    private final int[] wordCounts = {12,24,15,18,21};
    private final Button[] countButtons = new Button[wordCounts.length];
    private int count=24;
    private boolean updating;
    private ChangeListener listener;
    MnemonicEntryView(Context context) throws Exception {
        super(context); setOrientation(VERTICAL); setSaveEnabled(false);
        setImportantForAutofill(IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
        List<String> dictionary=new ArrayList<>();
        try(BufferedReader r=new BufferedReader(new InputStreamReader(context.getAssets().open("bip39-english.txt"),StandardCharsets.UTF_8))) { String line; while((line=r.readLine())!=null) dictionary.add(line); }
        vocabulary=new MnemonicWords(dictionary);
        int[] labels={R.string.mnemonic_twelve,R.string.mnemonic_twenty_four,R.string.mnemonic_fifteen,R.string.mnemonic_eighteen,R.string.mnemonic_twenty_one};
        LinearLayout switches=new LinearLayout(context);switches.setPadding(dp(3),dp(3),dp(3),dp(3));switches.setBackground(PearlDesign.surface(context,PearlDesign.BG,0,12));addView(switches);
        for(int i=0;i<wordCounts.length;i++) {
            int selected=wordCounts[i];Button button=new Button(context);countButtons[i]=button;
            button.setText(labels[i]);button.setTextSize(12);button.setAllCaps(false);button.setMinWidth(0);button.setMinimumWidth(0);button.setPadding(dp(2),dp(7),dp(2),dp(7));button.setStateListAnimator(null);button.setOnClickListener(v->setCount(selected));
            switches.addView(button,new LayoutParams(0,-2,1));
        }
        status=new TextView(context); status.setTextSize(13); status.setPadding(dp(4),dp(8),dp(4),dp(10));
        Button paste=PearlDesign.button(context,context.getString(R.string.mnemonic_paste),PearlDesign.TEAL,PearlDesign.PALE,null);
        paste.setOnClickListener(v->{
            ClipboardManager c=(ClipboardManager)context.getSystemService(Context.CLIPBOARD_SERVICE);
            if(c.getPrimaryClip()==null || c.getPrimaryClip().getItemCount()==0) { status.setText("剪贴板中没有助记词"); return; }
            CharSequence text=c.getPrimaryClip().getItemAt(0).getText();
            if(text==null) { status.setText("请粘贴文字形式的助记词"); return; }
            List<String> words=MnemonicWords.split(text.toString());
            if(!MnemonicWords.supportedCount(words.size())) { status.setText(R.string.mnemonic_paste_count); return; }
            fill(words,0);
        }); addView(paste);
        addView(status);
        for(int row=0;row<8;row++) {
            LinearLayout line=new LinearLayout(context); line.setOrientation(HORIZONTAL); rows.add(line); addView(line);
            for(int col=0;col<3;col++) {
                int i=row*3+col;
                LinearLayout box=new LinearLayout(context); box.setOrientation(VERTICAL); box.setPadding(dp(9),dp(8),dp(9),dp(4));box.setBackground(PearlDesign.surface(context,PearlDesign.BG,PearlDesign.LINE,10));
                TextView number=new TextView(context); number.setText(String.valueOf(i+1)); number.setTextSize(11); number.setTextColor(PearlDesign.MUTED); box.addView(number);
                AutoCompleteTextView field=new AutoCompleteTextView(context); fields[i]=field;
                field.setHint("word"); field.setTextSize(13);field.setTextColor(PearlDesign.INK);field.setHintTextColor(PearlDesign.MUTED);field.setPadding(0,0,0,0);field.setBackground(null);field.setSingleLine(true); field.setSelectAllOnFocus(true); field.setSaveEnabled(false);
                field.setTypeface(Typeface.MONOSPACE); field.setThreshold(1);
                field.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
                field.setImeOptions(EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING | EditorInfo.IME_ACTION_NEXT);
                field.setImportantForAutofill(IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
                field.setContentDescription("第 "+(i+1)+" 个助记词");
                field.setAdapter(new ArrayAdapter<>(context,android.R.layout.simple_dropdown_item_1line,dictionary));
                field.addTextChangedListener(new TextWatcher(){private int inserted;public void beforeTextChanged(CharSequence s,int a,int c,int f){} public void onTextChanged(CharSequence s,int a,int b,int c){inserted=c;}
                    public void afterTextChanged(Editable e){ if(updating)return; List<String> words=MnemonicWords.split(e.toString()); if(words.size()>1 && inserted>1){ if(MnemonicWords.supportedCount(words.size())) fill(words,0); else if(i+words.size()<=count)fill(words,i); else {validate();status.setText("粘贴的词数超过剩余位置");} } else validate(); }
                });
                field.setOnFocusChangeListener((v,focused)->{if(!focused){String word=MnemonicWords.normalizeWord(field.getText().toString());updating=true;field.setText(word);updating=false;field.setError(word.isEmpty()||vocabulary.contains(word)?null:"拼写不在词库中");validate();}});
                field.setOnEditorActionListener((v,action,event)->{if(action==EditorInfo.IME_ACTION_NEXT){if(i+1<count)fields[i+1].requestFocus();return true;}return false;});
                box.addView(field,new LayoutParams(-1,dp(36)));LayoutParams cell=new LayoutParams(0,-2,1);cell.setMargins(dp(3),dp(4),dp(3),dp(4));line.addView(box,cell);
            }
        }
        setCount(24);
    }
    private int dp(int n){return Math.round(n*getResources().getDisplayMetrics().density);}
    private void setCount(int next){
        if(!MnemonicWords.supportedCount(next))throw new IllegalArgumentException("无效的 BIP39 词数");
        count=next;for(int i=0;i<rows.size();i++)rows.get(i).setVisibility(i<count/3?VISIBLE:GONE);
        for(int i=0;i<countButtons.length;i++){boolean selected=count==wordCounts[i];countButtons[i].setTextColor(selected?PearlDesign.TEAL:PearlDesign.MUTED);countButtons[i].setTypeface(Typeface.create(selected?"sans-serif-medium":"sans-serif",Typeface.NORMAL));countButtons[i].setBackground(PearlDesign.touch(getContext(),selected?PearlDesign.WHITE:PearlDesign.BG,0,9));countButtons[i].setSelected(selected);}
        validate();
    }
    private void fill(List<String> words,int start){
        boolean fullPhrase=start==0&&MnemonicWords.supportedCount(words.size());
        if(fullPhrase)setCount(words.size());
        updating=true;
        if(fullPhrase)for(AutoCompleteTextView f:fields){f.setText("");f.dismissDropDown();f.setError(null);}
        for(int i=0;i<words.size() && start+i<count;i++){fields[start+i].setText(MnemonicWords.normalizeWord(words.get(i)));fields[start+i].dismissDropDown();fields[start+i].setError(null);}
        updating=false;validate();
    }
    private List<String> phrase(){List<String> words=new ArrayList<>();for(int i=0;i<count;i++)words.add(fields[i].getText().toString());return words;}
    private void validate(){MnemonicWords.Validation v=vocabulary.validate(phrase());status.setText(v.message);status.setTextColor(v.valid?PearlDesign.TEAL:PearlDesign.MUTED);if(listener!=null)listener.changed(v.valid);}
    void setListener(ChangeListener listener){this.listener=listener;validate();}
    String mnemonic(){List<String> p=phrase();MnemonicWords.Validation v=vocabulary.validate(p);if(!v.valid)throw new IllegalArgumentException(v.message);List<String> clean=new ArrayList<>();for(String w:p)clean.add(MnemonicWords.normalizeWord(w));return String.join(" ",clean);}
    void clear(){updating=true;for(AutoCompleteTextView f:fields){f.dismissDropDown();f.setText("");}updating=false;listener=null;status.setText("");}
}
