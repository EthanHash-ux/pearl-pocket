package ai.pearl.wallet;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Shader;
import android.view.MotionEvent;
import android.view.View;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

public final class PriceChartView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path line = new Path(), area = new Path();
    private final SimpleDateFormat date = new SimpleDateFormat("MM/dd HH:mm", Locale.CHINA);
    private final Date displayedDate = new Date();
    private LinearGradient gradient;
    private List<double[]> points = Collections.emptyList();
    private String emptyMessage = "行情曲线加载中…", symbol = "$";
    private int selected = -1, historySize;
    public PriceChartView(Context context) { super(context); setContentDescription("Pearl 价格曲线，触摸查看时间和价格"); }
    public void setPoints(List<double[]> data, String currency) {
        points = new ArrayList<>(data); historySize = points.size(); symbol = currency.equals("cny") ? "¥" : currency.equals("usdt") ? "USDT " : "$"; selected = -1; invalidate();
    }
    public void livePrice(long milliseconds, double price) {
        if (historySize < 2 || !Double.isFinite(price) || price <= 0 || milliseconds <= points.get(historySize - 1)[0]) return;
        if (points.size() == historySize) points.add(new double[]{milliseconds, price});
        else { double[] last = points.get(points.size()-1); last[0] = milliseconds; last[1] = price; }
        invalidate();
    }
    public void setEmpty(String message) { points = Collections.emptyList(); historySize = 0; emptyMessage = message; selected = -1; invalidate(); }
    private float dp(float value) { return value * getResources().getDisplayMetrics().density; }
    private String dateLabel(double time) { displayedDate.setTime((long) time); return date.format(displayedDate); }
    @Override protected void onSizeChanged(int w, int h, int oldW, int oldH) {
        super.onSizeChanged(w, h, oldW, oldH);
        gradient = new LinearGradient(0, dp(35), 0, Math.max(dp(36), h - dp(28)),
                Color.argb(44, 12, 97, 85), Color.argb(0, 12, 97, 85), Shader.TileMode.CLAMP);
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float width = getWidth(), height = getHeight(), top = dp(35), bottom = height - dp(28), left = dp(5), right = width - dp(5);
        paint.setTextSize(dp(11)); paint.setColor(Color.rgb(130, 144, 137));
        if (points.isEmpty()) {
            paint.setTextAlign(Paint.Align.CENTER); canvas.drawText(emptyMessage, width / 2, height / 2, paint);
            return;
        }
        double min = Double.POSITIVE_INFINITY, max = Double.NEGATIVE_INFINITY;
        for (double[] p : points) { min = Math.min(min, p[1]); max = Math.max(max, p[1]); }
        double padding = Math.max((max - min) * .12, max * .001);
        min -= padding; max += padding;
        paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(dp(1)); paint.setColor(Color.rgb(225, 233, 225));
        for (int i = 0; i < 3; i++) {
            float y = top + (bottom - top) * i / 2;
            canvas.drawLine(left, y, right, y, paint);
        }
        line.reset(); float sx = 0, sy = 0;
        double first = points.get(0)[0], span = points.get(points.size() - 1)[0] - first;
        for (int i = 0; i < points.size(); i++) {
            double[] p = points.get(i);
            float x = left + (float) ((p[0] - first) / span) * (right - left);
            float y = bottom - (float) ((p[1] - min) / (max - min)) * (bottom - top);
            if (i == 0) line.moveTo(x, y); else line.lineTo(x, y);
            if (i == selected) { sx = x; sy = y; }
        }
        area.set(line); area.lineTo(right, bottom); area.lineTo(left, bottom); area.close();
        paint.setStyle(Paint.Style.FILL); paint.setShader(gradient);
        canvas.drawPath(area, paint); paint.setShader(null);
        paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(dp(2)); paint.setColor(Color.rgb(12, 97, 85));
        canvas.drawPath(line, paint); paint.setStyle(Paint.Style.FILL); paint.setTextAlign(Paint.Align.LEFT);
        paint.setColor(Color.rgb(130, 144, 137));
        canvas.drawText(dateLabel(first), left, height - dp(5), paint);
        paint.setTextAlign(Paint.Align.RIGHT);
        canvas.drawText(dateLabel(points.get(points.size() - 1)[0]), right, height - dp(5), paint);
        if (selected >= 0) {
            paint.setColor(Color.rgb(12, 97, 85)); canvas.drawCircle(sx, sy, dp(4), paint);
            canvas.drawLine(sx, top, sx, bottom, paint); paint.setTextAlign(Paint.Align.CENTER);
            double[] p = points.get(selected);
            canvas.drawText(dateLabel(p[0]) + "  " + symbol
                    + String.format(Locale.ROOT, "%.4f", p[1]), width / 2, dp(19), paint);
        }
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        if (points.isEmpty()) return false;
        if (event.getAction() == MotionEvent.ACTION_DOWN || event.getAction() == MotionEvent.ACTION_MOVE) {
            getParent().requestDisallowInterceptTouchEvent(true);
            double target = points.get(0)[0] + Math.max(0, Math.min(1, event.getX() / getWidth()))
                    * (points.get(points.size() - 1)[0] - points.get(0)[0]);
            selected = 0;
            for (int i = 1; i < points.size(); i++) if (Math.abs(points.get(i)[0] - target) < Math.abs(points.get(selected)[0] - target)) selected = i;
            invalidate(); return true;
        }
        if (event.getAction() == MotionEvent.ACTION_UP) { performClick(); getParent().requestDisallowInterceptTouchEvent(false); return true; }
        return super.onTouchEvent(event);
    }
    @Override public boolean performClick() { super.performClick(); return true; }
}
