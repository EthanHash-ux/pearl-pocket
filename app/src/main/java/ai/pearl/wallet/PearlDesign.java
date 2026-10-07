package ai.pearl.wallet;

import android.app.AlertDialog;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Shared native visual primitives. No account, network or secret state. */
final class PearlDesign {
    static final int INK=Color.rgb(22,43,55), TEAL=Color.rgb(0,112,99), MUTED=Color.rgb(95,114,128),
            BG=Color.rgb(245,247,249), LINE=Color.rgb(228,235,239), PALE=Color.rgb(227,244,238),
            MINT=Color.rgb(133,234,201), WHITE=Color.WHITE, RED=Color.rgb(174,61,68);
    static int dp(Context c,float n){return Math.round(n*c.getResources().getDisplayMetrics().density);}
    static GradientDrawable surface(Context c,int color,int stroke,int radius){
        GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(c,radius));if(stroke!=0)d.setStroke(dp(c,1),stroke);return d;
    }
    static Drawable touch(Context c,int color,int stroke,int radius){
        return new RippleDrawable(ColorStateList.valueOf(Color.argb(28,55,122,111)),surface(c,color,stroke,radius),surface(c,WHITE,0,radius));
    }
    static LinearLayout column(Context c){LinearLayout v=new LinearLayout(c);v.setOrientation(LinearLayout.VERTICAL);return v;}
    static LinearLayout form(Context c){LinearLayout v=column(c);v.setPadding(dp(c,22),dp(c,10),dp(c,22),dp(c,16));return v;}
    static TextView text(Context c,String value,int size,int color,boolean medium){
        TextView v=new TextView(c);v.setText(value);v.setTextSize(size);v.setTextColor(color);v.setIncludeFontPadding(false);v.setLineSpacing(dp(c,3),1);
        v.setTypeface(Typeface.create(medium?"sans-serif-medium":"sans-serif",Typeface.NORMAL));v.setFontFeatureSettings("tnum");return v;
    }
    static TextView note(Context c,String value){TextView v=text(c,value,13,MUTED,false);v.setPadding(0,dp(c,7),0,dp(c,12));return v;}
    static void input(EditText e){
        Context c=e.getContext();e.setTextSize(15);e.setTextColor(INK);e.setHintTextColor(MUTED);e.setPadding(dp(c,14),dp(c,12),dp(c,14),dp(c,12));
        e.setBackground(touch(c,BG,LINE,12));e.setMinHeight(dp(c,52));
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.bottomMargin=dp(c,10);e.setLayoutParams(p);
    }
    static Button button(Context c,String title,int color,int fill,android.view.View.OnClickListener action){
        Button b=new Button(c);b.setText(title);b.setAllCaps(false);b.setTextSize(14);b.setTextColor(color);b.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));
        b.setGravity(Gravity.CENTER);b.setMinHeight(dp(c,48));b.setMinimumHeight(dp(c,48));b.setMinimumWidth(0);b.setMinWidth(0);b.setPadding(dp(c,14),dp(c,9),dp(c,14),dp(c,9));
        b.setStateListAnimator(null);b.setBackground(touch(c,fill,fill==WHITE?LINE:0,14));b.setOnClickListener(action);return b;
    }
    static void dialog(AlertDialog d){
        if(d.getWindow()!=null){d.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);d.getWindow().setBackgroundDrawable(surface(d.getContext(),WHITE,0,24));}
        for(int which:new int[]{AlertDialog.BUTTON_POSITIVE,AlertDialog.BUTTON_NEGATIVE,AlertDialog.BUTTON_NEUTRAL}){
            Button b=d.getButton(which);if(b!=null){b.setAllCaps(false);b.setTextColor(TEAL);b.setTextSize(14);b.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));}
        }
    }
    static Drawable assetSurface(Context c){
        GradientDrawable d=new GradientDrawable(GradientDrawable.Orientation.TL_BR,new int[]{INK,Color.rgb(39,79,89)});d.setCornerRadius(dp(c,26));return d;
    }
    static Drawable icon(Context c,String name,int color,int size){Icon icon=new Icon(name,color);icon.setBounds(0,0,dp(c,size),dp(c,size));return icon;}
    private static final class Icon extends Drawable {
        private final Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);private final String name;private final int color;
        Icon(String name,int color){this.name=name;this.color=color;}
        private void line(Canvas c,float... points){Path path=new Path();path.moveTo(points[0],points[1]);for(int i=2;i<points.length;i+=2)path.lineTo(points[i],points[i+1]);c.drawPath(path,p);}
        @Override public void draw(Canvas canvas){
            int save=canvas.save();canvas.translate(getBounds().left,getBounds().top);canvas.scale(getBounds().width()/24f,getBounds().height()/24f);
            p.setColor(color);p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(1.7f);p.setStrokeCap(Paint.Cap.ROUND);p.setStrokeJoin(Paint.Join.ROUND);
            switch(name){
                case "wallet":canvas.drawRoundRect(new RectF(3,5,21,20),3,3,p);canvas.drawRoundRect(new RectF(14,10,22,16),2,2,p);canvas.drawCircle(17,13,.4f,p);line(canvas,4,5,17,3,19,5);break;
                case "market":line(canvas,3,19,3,5);line(canvas,3,19,21,19);line(canvas,6,14,10,10,14,12,20,5);line(canvas,16,5,20,5,20,9);break;
                case "mining":canvas.drawRoundRect(new RectF(6,6,18,18),3,3,p);canvas.drawRect(10,10,14,14,p);for(int i=8;i<=16;i+=4){line(canvas,i,3,i,6);line(canvas,i,18,i,21);line(canvas,3,i,6,i);line(canvas,18,i,21,i);}break;
                case "activity":line(canvas,4,7,19,7,16,4);line(canvas,19,7,16,10);line(canvas,20,17,5,17,8,14);line(canvas,5,17,8,20);break;
                case "settings":canvas.drawCircle(12,12,3,p);for(int i=0;i<8;i++){double a=i*Math.PI/4;line(canvas,(float)(12+7*Math.cos(a)),(float)(12+7*Math.sin(a)),(float)(12+10*Math.cos(a)),(float)(12+10*Math.sin(a)));}canvas.drawCircle(12,12,7,p);break;
                case "send":line(canvas,5,19,19,5);line(canvas,8,5,19,5,19,16);break;
                case "receive":line(canvas,19,5,5,19);line(canvas,5,8,5,19,16,19);break;
                case "copy":canvas.drawRoundRect(new RectF(8,8,20,21),2,2,p);line(canvas,5,16,4,16,4,3,16,3,16,5);break;
                case "refresh":canvas.drawArc(new RectF(4,4,20,20),35,285,false,p);line(canvas,21,5,20,10,15,8);break;
                case "eye":case "eye-off":Path eye=new Path();eye.moveTo(2,12);eye.cubicTo(7,3,17,3,22,12);eye.cubicTo(17,21,7,21,2,12);canvas.drawPath(eye,p);canvas.drawCircle(12,12,3,p);if(name.equals("eye-off"))line(canvas,3,3,21,21);break;
                case "contacts":canvas.drawRoundRect(new RectF(4,3,20,21),2,2,p);canvas.drawCircle(12,9,2.5f,p);canvas.drawArc(new RectF(8,13,16,20),185,170,false,p);line(canvas,2,7,5,7);line(canvas,2,12,5,12);line(canvas,2,17,5,17);break;
                case "shield":Path shield=new Path();shield.moveTo(12,2);shield.lineTo(21,6);shield.lineTo(20,14);shield.quadTo(18,19,12,22);shield.quadTo(6,19,4,14);shield.lineTo(3,6);shield.close();canvas.drawPath(shield,p);line(canvas,8,12,11,15,16,9);break;
                case "bell":Path bell=new Path();bell.moveTo(5,17);bell.lineTo(7,14);bell.lineTo(7,9);bell.cubicTo(7,2,17,2,17,9);bell.lineTo(17,14);bell.lineTo(19,17);bell.close();canvas.drawPath(bell,p);canvas.drawArc(new RectF(10,17,14,22),0,180,false,p);break;
                case "calculator":canvas.drawRoundRect(new RectF(5,2,19,22),3,3,p);canvas.drawRect(8,5,16,9,p);for(int y=13;y<=18;y+=5)for(int x=8;x<=16;x+=4)canvas.drawCircle(x,y,.5f,p);break;
                case "chevron":line(canvas,9,5,16,12,9,19);break;
                case "scan":for(int x:new int[]{3,21})for(int y:new int[]{3,21}){int dx=x==3?5:-5,dy=y==3?5:-5;line(canvas,x+dx,y,x,y,x,y+dy);}line(canvas,7,12,17,12);break;
                default:canvas.drawCircle(12,12,8,p);break;
            }
            canvas.restoreToCount(save);
        }
        @Override public void setAlpha(int alpha){p.setAlpha(alpha);}
        @Override public void setColorFilter(ColorFilter filter){p.setColorFilter(filter);}
        @Override public int getOpacity(){return PixelFormat.TRANSLUCENT;}
    }
}
