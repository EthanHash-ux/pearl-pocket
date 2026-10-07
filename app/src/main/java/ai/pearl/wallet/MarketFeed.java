package ai.pearl.wallet;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;
import org.json.JSONObject;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Foreground-only public ticker stream. No wallet address or secret is supplied. */
final class MarketFeed {
    interface Listener { void quote(PearlApi.Price price); void state(String text); }
    private final PearlApi api;
    private final Listener listener;
    private final OkHttpClient client;
    private final ScheduledExecutorService tasks=Executors.newScheduledThreadPool(2);
    private final AtomicBoolean fetching=new AtomicBoolean(), fetchingFx=new AtomicBoolean();
    private ScheduledFuture<?> poll,fxPoll,retry;
    private WebSocket socket;
    private MarketQuote latest;
    private MarketQuote.Fx fx;
    private boolean running, streaming;
    private int session, connection, failures;
    private long lastStreamNanos,lastFallbackNanos;
    MarketFeed(PearlApi api,Listener listener){
        this.api=api;this.listener=listener;
        client=new OkHttpClient.Builder().connectTimeout(6,TimeUnit.SECONDS).readTimeout(0,TimeUnit.SECONDS).pingInterval(20,TimeUnit.SECONDS)
                .followRedirects(false).followSslRedirects(false).build();
    }
    synchronized void start(){
        if(running)return;running=true;session++;failures=0;streaming=false;int token=session;
        listener.state("实时行情连接中");connect(token);
        poll=tasks.scheduleWithFixedDelay(()->snapshot(token,false),0,15,TimeUnit.SECONDS);
        fxPoll=tasks.scheduleWithFixedDelay(()->rates(token),0,300,TimeUnit.SECONDS);
    }
    private synchronized boolean current(int token){return running&&session==token;}
    synchronized void refresh(){if(running){int token=session;tasks.execute(()->snapshot(token,true));if(fx==null || !fx.fresh(System.currentTimeMillis()/1000))tasks.execute(()->rates(token));}}
    private synchronized void connect(int token){
        if(!current(token))return;int id=++connection;String host=failures%2==0?"api.big.one":"big.one";
        socket=client.newWebSocket(new Request.Builder().url("wss://"+host+"/ws/v2").header("Sec-WebSocket-Protocol","json").build(),new WebSocketListener(){
            @Override public void onOpen(WebSocket ws,Response response){
                synchronized(MarketFeed.this){if(!current(token)||id!=connection){ws.cancel();return;}}
                ws.send("{\"requestId\":\"pearl-prl\",\"subscribeMarketsTickerRequest\":{\"markets\":[\"PRL-USDT\"]}}");
            }
            @Override public void onMessage(WebSocket ws,String message){message(ws,message);}
            @Override public void onMessage(WebSocket ws,ByteString bytes){
                try {message(ws,decode(bytes));}catch(Exception e){failed(ws);}
            }
            private void message(WebSocket ws,String message){
                try {
                    if(message.length()>512_000)throw new IllegalArgumentException("行情响应过大");
                    MarketQuote quote=MarketQuote.stream(new JSONObject(message),System.currentTimeMillis()/1000);if(quote==null)return;
                    synchronized(MarketFeed.this){
                        if(!current(token)||id!=connection)return;
                        latest=quote;streaming=true;failures=0;lastStreamNanos=System.nanoTime();
                        listener.state("实时推送");listener.quote(quote.convert(fx,true,System.currentTimeMillis()/1000));
                    }
                }catch(Exception e){failed(ws);}
            }
            private void failed(WebSocket ws){
                synchronized(MarketFeed.this){if(!current(token)||id!=connection)return;connection++;streaming=false;failures++;socket=null;ws.cancel();
                    listener.state("定时刷新 · 实时连接重试中");if(latest!=null)listener.quote(latest.convert(fx,false,System.currentTimeMillis()/1000));
                    retry=tasks.schedule(()->connect(token),Math.min(30,1L<<Math.min(failures-1,5)),TimeUnit.SECONDS);
                }
            }
            @Override public void onFailure(WebSocket ws,Throwable cause,Response response){failed(ws);}
            @Override public void onClosing(WebSocket ws,int code,String reason){ws.close(code,reason);failed(ws);}
            @Override public void onClosed(WebSocket ws,int code,String reason){failed(ws);}
        });
    }
    static String decode(ByteString bytes)throws Exception{
        if(bytes.size()>512_000)throw new IllegalArgumentException("行情响应过大");
        if(bytes.size()<2 || bytes.getByte(0)!=(byte)0x1f || bytes.getByte(1)!=(byte)0x8b)return bytes.utf8();
        try(java.util.zip.GZIPInputStream in=new java.util.zip.GZIPInputStream(new java.io.ByteArrayInputStream(bytes.toByteArray()));java.io.ByteArrayOutputStream out=new java.io.ByteArrayOutputStream()){
            byte[] buf=new byte[4096];int size;while((size=in.read(buf))!=-1){if(out.size()+size>512_000)throw new IllegalArgumentException("行情响应过大");out.write(buf,0,size);}return out.toString("UTF-8");
        }
    }
    private void snapshot(int token,boolean force){
        synchronized(this){if(!current(token) || (!force&&streaming&&System.nanoTime()-lastStreamNanos<TimeUnit.SECONDS.toNanos(45)))return;}
        if(!fetching.compareAndSet(false,true))return;long started=System.nanoTime();
        try {
            MarketQuote quote=api.marketQuote();
            synchronized(this){if(!current(token)||lastStreamNanos>started)return;latest=quote;streaming=false;listener.state("定时刷新（15 秒）");listener.quote(quote.convert(fx,false,System.currentTimeMillis()/1000));}
        }catch(Exception failure){
            boolean fallback;
            synchronized(this){fallback=current(token)&&System.nanoTime()-lastFallbackNanos>TimeUnit.SECONDS.toNanos(60);if(fallback)lastFallbackNanos=System.nanoTime();}
            if(fallback)try{PearlApi.Price reference=api.price();synchronized(this){if(current(token)&&lastStreamNanos<started){latest=null;streaming=false;listener.state("参考行情 · 实时连接重试中");listener.quote(reference);}}}catch(Exception unavailable){synchronized(this){if(current(token))listener.state("行情暂不可用 · 自动重试");}}
        }finally{fetching.set(false);}
    }
    private void rates(int token){
        if(!current(token)||!fetchingFx.compareAndSet(false,true))return;
        try{MarketQuote.Fx result=api.exchangeRates();synchronized(this){if(!current(token))return;fx=result;if(latest!=null)listener.quote(latest.convert(fx,streaming,System.currentTimeMillis()/1000));}}
        catch(Exception unavailable){/* Keep the clearly labelled USDT quote when fiat conversion is unavailable. */}
        finally{fetchingFx.set(false);}
    }
    synchronized void stop(){
        running=false;session++;connection++;streaming=false;
        if(poll!=null)poll.cancel(false);if(fxPoll!=null)fxPoll.cancel(false);if(retry!=null)retry.cancel(false);if(socket!=null)socket.cancel();socket=null;
    }
    void close(){stop();tasks.shutdownNow();client.dispatcher().executorService().shutdown();client.connectionPool().evictAll();}
}
