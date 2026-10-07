package ai.pearl.wallet;

import android.app.*;
import android.appwidget.AppWidgetManager;
import android.content.*;
import android.os.Bundle;
import android.view.*;
import android.widget.*;

/** Launcher-facing setup accepts a real ID belonging to this provider before writing any state. */
public final class MiningWidgetConfigActivity extends Activity {
  private int widgetId;

  @Override
  public void onCreate(Bundle state) {
    boolean dark = getSharedPreferences("public_preferences", 0).getBoolean("dark_mode", false);
    setTheme(dark ? R.style.Theme_Pearl_Dark : R.style.Theme_Pearl);
    PearlDesign.dark(dark);
    super.onCreate(state);
    setResult(RESULT_CANCELED);
    widgetId =
        getIntent()
            .getIntExtra(
                AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID);
    if (!MiningWidget.valid(this, widgetId)) {
      finish();
      return;
    }
    getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
    LinearLayout form = PearlDesign.form(this);
    form.setBackgroundColor(PearlDesign.WHITE);
    form.setPadding(
        PearlDesign.dp(this, 22),
        PearlDesign.dp(this, 40),
        PearlDesign.dp(this, 22),
        PearlDesign.dp(this, 30));
    form.addView(PearlDesign.text(this, "算力与价格小组件", 24, PearlDesign.INK, true));
    form.addView(
        PearlDesign.note(
            this, "选择矿池总览，或用公开挖矿收款地址查看自己的贡献。地址查询会发送给 PearlFortune。行情来源为 BigONE PRL/USDT。"));
    RadioGroup mode = new RadioGroup(this);
    RadioButton pool = new RadioButton(this), miner = new RadioButton(this);
    pool.setId(View.generateViewId());
    miner.setId(View.generateViewId());
    pool.setText("矿池总算力");
    miner.setText("我的矿工算力");
    pool.setTextColor(PearlDesign.INK);
    miner.setTextColor(PearlDesign.INK);
    mode.addView(pool);
    mode.addView(miner);
    form.addView(mode);
    EditText name = new EditText(this), address = new EditText(this);
    name.setHint("小组件名称");
    address.setHint("PRL 挖矿收款地址");
    PearlDesign.input(name);
    PearlDesign.input(address);
    address.setInputType(
        android.text.InputType.TYPE_CLASS_TEXT
            | android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
    form.addView(name);
    form.addView(address);
    MiningWidget.Config old = MiningWidget.config(this, widgetId);
    if (state != null) {
      name.setText(state.getString("name", "PearlFortune"));
      address.setText(state.getString("address", ""));
      mode.check(state.getBoolean("personal") ? miner.getId() : pool.getId());
    } else {
      name.setText(old == null ? "PearlFortune" : old.name);
      address.setText(old == null ? "" : old.address);
      mode.check(old != null && !old.address.isEmpty() ? miner.getId() : pool.getId());
    }
    address.setVisibility(
        mode.getCheckedRadioButtonId() == miner.getId() ? View.VISIBLE : View.GONE);
    mode.setOnCheckedChangeListener(
        (g, id) -> address.setVisibility(id == miner.getId() ? View.VISIBLE : View.GONE));
    try {
      for (WatchBook.Entry e :
          new WatchBook(PublicStore.of(this), "fortune_miner_addresses").list())
        form.addView(
            PearlDesign.button(
                this,
                "使用矿工 · " + e.name,
                PearlDesign.TEAL,
                PearlDesign.PALE,
                v -> {
                  name.setText(e.name);
                  address.setText(e.address);
                  mode.check(miner.getId());
                }));
    } catch (Exception ignored) {
    }
    form.addView(
        PearlDesign.note(this, "后台约每 15 分钟尝试更新，实际时间由安卓系统决定。支持点击刷新；断网时保留并标注缓存。桌面上的价格和矿工状态可被看到。"));
    form.addView(
        PearlDesign.button(
            this,
            "保存小组件",
            PearlDesign.WHITE,
            PearlDesign.TEAL,
            v -> {
              try {
                MiningWidget.save(
                    this,
                    widgetId,
                    new MiningWidget.Config(
                        name.getText().toString(),
                        mode.getCheckedRadioButtonId() == miner.getId()
                            ? address.getText().toString()
                            : ""));
                setResult(
                    RESULT_OK,
                    new Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId));
                finish();
              } catch (Exception e) {
                Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show();
              }
            }));
    form.addView(PearlDesign.button(this, "取消", PearlDesign.MUTED, PearlDesign.BG, v -> finish()));
    ScrollView scroll = new ScrollView(this);
    scroll.addView(form);
    setContentView(scroll);
    scroll.setOnApplyWindowInsetsListener(
        (v, i) -> {
          v.setPadding(
              i.getSystemWindowInsetLeft(),
              i.getSystemWindowInsetTop(),
              i.getSystemWindowInsetRight(),
              i.getSystemWindowInsetBottom());
          return i;
        });
    // Retain only public form state through launcher rotations.
    draftName = name;
    draftAddress = address;
    draftMode = mode;
    minerChoice = miner.getId();
  }

  private EditText draftName, draftAddress;
  private RadioGroup draftMode;
  private int minerChoice;

  @Override
  protected void onSaveInstanceState(Bundle b) {
    super.onSaveInstanceState(b);
    if (draftName != null) {
      b.putString("name", draftName.getText().toString());
      b.putString("address", draftAddress.getText().toString());
      b.putBoolean("personal", draftMode.getCheckedRadioButtonId() == minerChoice);
    }
  }
}
