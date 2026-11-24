package com.orangecode.tianmu.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.orangecode.tianmu.application.*;
import com.orangecode.tianmu.producer.RocketMQProducer;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.util.concurrent.*;
import org.junit.jupiter.api.Test;
import java.net.*;
import java.net.http.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.mockito.Mockito.*;

/** Local opt-in benchmark: real sockets and Netty, simulated auth, video lookup and MQ. */
@org.junit.jupiter.api.condition.EnabledIfSystemProperty(named="danmaku.load", matches="true")
class DanmakuLoadTest {
 @Test void measure() throws Exception {
  var auth=mock(SessionAuthentication.class); when(auth.authenticate("load")).thenReturn(1L);
  var producer=mock(RocketMQProducer.class);
  when(producer.sendMessageAsync(anyString(), anyString())).thenReturn(CompletableFuture.completedFuture(null));
  var videos=mock(VideoLookup.class, invocation -> null);
  var broadcaster=new RoomBatchBroadcaster(100,50,16384);
  var boss=new NioEventLoopGroup(1); var workers=new NioEventLoopGroup(4);
  var business=new DefaultEventExecutorGroup(4,new DefaultThreadFactory("load-business"),1024,RejectedExecutionHandlers.reject());
  var preparation=new ThreadPoolExecutor(16,16,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(1024));
  var limits=new DanmakuSendQueue.Limits(1024,512,2);
  var server=new ServerBootstrap().group(boss,workers).channel(NioServerSocketChannel.class).childHandler(new ChannelInitializer<SocketChannel>() {
   protected void initChannel(SocketChannel ch){NettyService.configurePipeline(ch.pipeline(),producer,auth,videos,broadcaster,business,limits,preparation);}
  }).bind("127.0.0.1",0).sync().channel();
  try { for(int delay : new int[]{200,50,20,5}) run(1000, delay, ((InetSocketAddress)server.localAddress()).getPort()); }
  finally { server.close().syncUninterruptibly(); preparation.shutdownNow(); broadcaster.close(); business.shutdownGracefully(0,3,TimeUnit.SECONDS).syncUninterruptibly(); workers.shutdownGracefully(0,3,TimeUnit.SECONDS).syncUninterruptibly(); boss.shutdownGracefully(0,3,TimeUnit.SECONDS).syncUninterruptibly(); }
 }
 private void run(int n,int delay,int port) throws Exception {
  var client=HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(5)).build(); var sockets=new ArrayList<WebSocket>();
  var times=new ConcurrentHashMap<String,Long>(); var latencies=new ConcurrentLinkedQueue<Long>(); var errors=new AtomicInteger(); var received=new AtomicInteger(); var issued=new AtomicInteger();
  var json=new ObjectMapper();
  for(int i=0;i<n;i++){
   final int id=i;
   WebSocket.Listener listener=new WebSocket.Listener(){StringBuilder partial=new StringBuilder();
    public void onOpen(WebSocket ws){ws.request(1);}
    public CompletionStage<?> onText(WebSocket ws,CharSequence data,boolean last){partial.append(data); if(last){try{var m=json.readTree(partial.toString()); if(m.path("type").asInt()==5) for(var item:m.path("data")){String value=item.path("text").asText(); if(value.startsWith(id+":")){Long started=times.remove(value); if(started!=null){latencies.add(System.nanoTime()-started);received.incrementAndGet();}}} else if(m.path("type").asInt()==4 || m.path("type").asInt()==3) errors.incrementAndGet();}catch(Exception e){errors.incrementAndGet();} partial.setLength(0);} ws.request(1);return null;}
    public void onError(WebSocket ws,Throwable t){errors.incrementAndGet();}
   };
   sockets.add(client.newWebSocketBuilder().buildAsync(URI.create("ws://127.0.0.1:"+port+"/ws/bulletScreen/99"),listener).get(10,TimeUnit.SECONDS));
  }
  long start=System.nanoTime(), end=start+TimeUnit.SECONDS.toNanos(5); var pool=Executors.newFixedThreadPool(20); var jobs=new ArrayList<java.util.concurrent.Future<?>>();
  for(int i=0;i<20;i++){final int id=i; jobs.add(pool.submit(()->{int seq=0; try{while(System.nanoTime()<end){String key=id+":"+(seq++); times.put(key,System.nanoTime()); issued.incrementAndGet(); sockets.get(id).sendText("{\"authorization\":\"load\",\"content\":\""+key+"\",\"playbackTime\":0}",true).get(3,TimeUnit.SECONDS); Thread.sleep(delay);}}catch(Exception e){errors.incrementAndGet();}}));}
  for(var job:jobs)job.get(10,TimeUnit.SECONDS); pool.shutdown(); Thread.sleep(2000);
  System.out.printf("LOAD_CONNECTIONS connected=%d%n", sockets.size()); var ordered=new ArrayList<>(latencies); Collections.sort(ordered); double elapsed=(System.nanoTime()-start)/1e9-2;
  System.out.printf(Locale.ROOT,"LOAD_RESULT delay_ms=%d clients=%d sent=%d received=%d errors=%d missing=%d elapsed_s=%.2f sent_per_s=%.1f p50_ms=%.1f p95_ms=%.1f p99_ms=%.1f%n",delay,n,issued.get(),received.get(),errors.get(),times.size(),elapsed,issued.get()/elapsed,pct(ordered,.50),pct(ordered,.95),pct(ordered,.99));
  for(var ws:sockets) ws.abort();
  Thread.sleep(2000);
 }
 private double pct(List<Long> list,double p){return list.isEmpty()?0:list.get(Math.min(list.size()-1,(int)Math.ceil(list.size()*p)-1))/1e6;}
}
