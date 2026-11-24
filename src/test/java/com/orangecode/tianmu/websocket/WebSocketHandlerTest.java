package com.orangecode.tianmu.websocket;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.orangecode.tianmu.application.*;
import com.orangecode.tianmu.common.ErrorCode;
import com.orangecode.tianmu.exception.BusinessException;
import com.orangecode.tianmu.producer.RocketMQProducer;
import com.orangecode.tianmu.utils.JwtUtil;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultHttpHeaders;
import io.netty.handler.codec.http.websocketx.*;
import io.netty.util.ReferenceCountUtil;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class WebSocketHandlerTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final List<EmbeddedChannel> channels = new ArrayList<>();
    private RocketMQProducer producer;
    private VideoLookup videos;
    private SessionAuthentication authentication;
    private ValueOperations<String,String> sessions;
    private String alice, previousKey;
    private RoomBatchBroadcaster broadcasts;
    private DanmakuSendQueue.Limits defaultLimits;

    @BeforeEach void setup() {
        previousKey = System.getProperty("tianmu.jwt.secret-base64");
        byte[] key = new byte[32]; new java.security.SecureRandom().nextBytes(key);
        System.setProperty("tianmu.jwt.secret-base64", Base64.getEncoder().encodeToString(key));
        producer = mock(RocketMQProducer.class); videos = mock(VideoLookup.class);
        when(producer.sendMessageAsync(anyString(), anyString())).thenReturn(CompletableFuture.completedFuture(null));
        defaultLimits = new DanmakuSendQueue.Limits(1024, 512, 2);
        broadcasts = new RoomBatchBroadcaster(100,1,16384);
        var redis = mock(StringRedisTemplate.class); sessions = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(sessions); authentication = new SessionAuthentication(redis);
        alice = JwtUtil.generate("1"); when(sessions.get("1")).thenReturn(alice);
    }
    @AfterEach void cleanup() {
        for (var channel : channels) channel.finishAndReleaseAll();
        broadcasts.close();
        if (previousKey == null) System.clearProperty("tianmu.jwt.secret-base64");
        else System.setProperty("tianmu.jwt.secret-base64", previousKey);
    }

    @Test void validSendUsesVerifiedIdentityAndRoomWithoutCredentialOrClientTimestampLeak() throws Exception {
        var sender = connect("99"); var viewer = connect("99"); var otherRoom = connect("100");
        var input = message().put("bulletId","7").put("createTime","2000-01-01T00:00:00Z");
        sender.writeInbound(new TextWebSocketFrame(input.toString()));
        var capture = ArgumentCaptor.forClass(String.class);
        verify(producer).sendMessageAsync(eq("tianmu-topic"), capture.capture());
        var queued = JSON.readTree(capture.getValue());
        assertEquals(1,queued.get("userId").asLong()); assertEquals(99,queued.get("videoId").asLong());
        assertNotEquals(7,queued.get("bulletId").asLong()); assertTrue(queued.has("createTime"));
        assertFalse(queued.has("authorization")); assertFalse(capture.getValue().contains(alice));
        var delivered = messages(viewer); assertEquals(1,delivered.size());
        assertEquals(5,delivered.get(0).get("type").asInt());
        assertEquals("1",delivered.get(0).path("data").get(0).path("userId").asText());
        assertFalse(delivered.toString().contains(alice)); assertTrue(messages(otherRoom).isEmpty());
        verify(videos).requirePublished(99L);
    }

    @Test void guestCanReceiveButCannotSendEvenIfVictimHasOnlineSession() throws Exception {
        var sender = connect("99"); var viewer = connect("99");
        sender.writeInbound(new TextWebSocketFrame("{\"userId\":\"1\",\"videoId\":\"99\",\"content\":\"hello\",\"playbackTime\":1}"));
        assertTrue(messages(sender).stream().anyMatch(n -> n.path("type").asInt()==3));
        assertTrue(messages(viewer).isEmpty()); verifyNoInteractions(producer, videos);
    }

    @Test void forgedUserAndCrossRoomMessagesNeverEnqueueOrBroadcast() throws Exception {
        var sender = connect("99"); var viewer = connect("99");
        for (var input : List.of(message().put("userId","2"),message().put("videoId","100")))
            sender.writeInbound(new TextWebSocketFrame(input.toString()));
        assertTrue(messages(sender).stream().allMatch(n -> n.path("type").asInt()==4));
        assertTrue(messages(viewer).isEmpty()); verifyNoInteractions(producer, videos);
    }

    @Test void logoutAndReplacedSessionInvalidateAnAlreadyOpenConnection() throws Exception {
        var sender = connect("99");
        sender.writeInbound(new TextWebSocketFrame(message().toString())); messages(sender);
        reset(producer, videos);
        when(sessions.get("1")).thenReturn(null);
        sender.writeInbound(new TextWebSocketFrame(message().toString()));
        when(sessions.get("1")).thenReturn("another-login-token");
        sender.writeInbound(new TextWebSocketFrame(message().toString()));
        var results = messages(sender); assertEquals(2,results.size());
        assertTrue(results.stream().allMatch(n -> n.path("type").asInt()==3));
        verifyNoInteractions(producer, videos);
    }

    @Test void expiredAndInvalidTokensCannotSend() throws Exception {
        var sender = connect("99");
        String expired = io.jsonwebtoken.Jwts.builder().setSubject("1").setExpiration(new Date(1))
                .signWith(JwtUtil.getSignInKey(),io.jsonwebtoken.SignatureAlgorithm.HS256).compact();
        for (String token : List.of(expired,"invalid")) sender.writeInbound(new TextWebSocketFrame(message().put("authorization",token).toString()));
        assertTrue(messages(sender).stream().allMatch(n -> n.path("type").asInt()==3));
        verifyNoInteractions(producer, videos);
    }

    @Test void malformedAndInvalidBusinessFieldsAreRejectedWithoutClosingValidConnection() throws Exception {
        var sender = connect("99");
        for (String input : List.of("{", "[]", message().put("content"," ").toString(),
                message().put("content","x".repeat(256)).toString(),message().put("playbackTime",-1).toString(),
                message().put("playbackTime","NaN").toString(),message().put("color","red<script>").toString(),
                message()+" {}", "{\"authorization\":\"a\",\"authorization\":\"b\"}"))
            sender.writeInbound(new TextWebSocketFrame(input));
        var results=messages(sender); assertEquals(9,results.size());
        assertTrue(results.stream().allMatch(n -> n.path("type").asInt()==4));
        assertTrue(sender.isActive()); verifyNoInteractions(producer, videos);
    }

    @Test void oversizedFramesAreRejectedBeforeAuthentication() throws Exception {
        var sender=connect("99");sender.writeInbound(new TextWebSocketFrame("x".repeat(16385)));
        assertEquals(4,messages(sender).get(0).path("type").asInt()); verifyNoInteractions(producer,videos);
    }

    @Test void nonexistentVideoAndQueueFailuresAreNotBroadcastAsSuccess() throws Exception {
        var sender=connect("99");var viewer=connect("99");
        doThrow(new BusinessException(ErrorCode.VIDEO_NOT_FOUND_ERROR)).when(videos).requirePublished(99L);
        sender.writeInbound(new TextWebSocketFrame(message().toString())); verifyNoInteractions(producer);
        reset(videos);
        when(producer.sendMessageAsync(anyString(), anyString()))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("test queue failure")));
        sender.writeInbound(new TextWebSocketFrame(message().toString()));
        assertEquals(2,messages(sender).size()); assertTrue(messages(viewer).isEmpty());
    }

    @Test void invalidRoomPathsAndTokenQueryStringsAreRejected() {
        for(String path:List.of("/ws/bulletScreen/0","/ws/bulletScreen/9999999999999999999",
                "/ws/bulletScreen/99?token=secret","/ws/bulletScreen/99/extra")) {
            var channel = new EmbeddedChannel(io.netty.channel.DefaultChannelId.newInstance(),
                    new WebSocketHandler(producer, authentication, videos, broadcasts, defaultLimits, Runnable::run));
            channels.add(channel);
            channel.pipeline().fireUserEventTriggered(new WebSocketServerProtocolHandler.HandshakeComplete(path,new DefaultHttpHeaders(),null));
            assertFalse(channel.isActive());
        }
        verifyNoInteractions(producer,videos);
    }

    @Test void asynchronousSendsAwaitBrokerAndKeepPerConnectionOrder() throws Exception {
        var limits = new DanmakuSendQueue.Limits(8, 4, 1);
        var sender = connect("99", limits);
        var viewer = connect("99", limits);
        var first = new CompletableFuture<Void>();
        var second = new CompletableFuture<Void>();
        when(producer.sendMessageAsync(eq("tianmu-topic"), anyString())).thenReturn(first, second);
        sender.writeInbound(new TextWebSocketFrame(message().put("content", "first").toString()));
        sender.writeInbound(new TextWebSocketFrame(message().put("content", "second").toString()));
        verify(producer, times(1)).sendMessageAsync(eq("tianmu-topic"), anyString());
        assertTrue(messages(viewer).isEmpty());

        first.complete(null);
        messages(sender);
        verify(producer, times(2)).sendMessageAsync(eq("tianmu-topic"), anyString());
        assertEquals("first", messages(viewer).get(0).path("data").get(0).path("text").asText());

        second.completeExceptionally(new IllegalStateException("broker timed out"));
        assertEquals(4, messages(sender).get(0).path("type").asInt());
        assertTrue(messages(viewer).isEmpty());
    }

    @Test void outOfOrderBrokerCallbacksBroadcastInOrderAndAdvancePastFailure() throws Exception {
        var limits = new DanmakuSendQueue.Limits(16, 16, 4);
        var sender = connect("99", limits);
        var viewer = connect("99", limits);
        var first = new CompletableFuture<Void>();
        var second = new CompletableFuture<Void>();
        var third = new CompletableFuture<Void>();
        var fourth = new CompletableFuture<Void>();
        var fifth = new CompletableFuture<Void>();
        when(producer.sendMessageAsync(eq("tianmu-topic"), anyString())).thenReturn(first, second, third, fourth, fifth);
        for (String text : List.of("first", "second", "third", "fourth", "fifth")) {
            sender.writeInbound(new TextWebSocketFrame(message().put("content", text).toString()));
        }
        verify(producer, times(4)).sendMessageAsync(eq("tianmu-topic"), anyString());

        fourth.complete(null);
        second.complete(null);
        third.completeExceptionally(new IllegalStateException("broker rejected"));
        assertTrue(messages(sender).isEmpty());
        assertTrue(messages(viewer).isEmpty());
        verify(producer, times(4)).sendMessageAsync(eq("tianmu-topic"), anyString());

        first.complete(null);
        assertEquals(1, messages(sender).stream().filter(frame -> frame.path("type").asInt() == 4).count());
        var delivered = messages(viewer).stream().map(frame -> frame.path("data").get(0).path("text").asText()).toList();
        assertEquals(List.of("first", "second", "fourth"), delivered);
        verify(producer, times(5)).sendMessageAsync(eq("tianmu-topic"), anyString());

        fifth.complete(null);
        messages(sender);
        assertEquals("fifth", messages(viewer).get(0).path("data").get(0).path("text").asText());
    }

    @Test void asynchronousPathBoundsRoomsAndRevalidatesQueuedFrames() throws Exception {
        var limits = new DanmakuSendQueue.Limits(4, 2, 1);
        var sender = connect("99", limits);
        var peer = connect("99", limits);
        var otherRoom = connect("100", limits);
        var first = new CompletableFuture<Void>();
        var other = new CompletableFuture<Void>();
        when(producer.sendMessageAsync(anyString(), anyString())).thenReturn(first, other);

        sender.writeInbound(new TextWebSocketFrame(message().toString()));
        sender.writeInbound(new TextWebSocketFrame(message().toString()));
        peer.writeInbound(new TextWebSocketFrame(message().toString()));
        assertEquals(4, messages(peer).get(0).path("type").asInt());
        otherRoom.writeInbound(new TextWebSocketFrame(message().put("videoId","100").toString()));
        verify(producer, times(2)).sendMessageAsync(anyString(), anyString());

        when(sessions.get("1")).thenReturn(null);
        first.complete(null);
        assertTrue(messages(sender).stream().anyMatch(frame -> frame.path("type").asInt() == 3));
        verify(producer, times(2)).sendMessageAsync(anyString(), anyString());
        other.complete(null);
        messages(otherRoom);
    }

    @Test void closeDropsQueuedFramesAndRetainsOnlyTheActualInFlightPermit() {
        var limits = new DanmakuSendQueue.Limits(2, 2, 1);
        var queue = new DanmakuSendQueue(limits);
        var first = new DanmakuSendQueue.Frame(99, "first", 0, 0);
        var second = new DanmakuSendQueue.Frame(99, "second", 0, 0);
        assertEquals(DanmakuSendQueue.Offer.START, queue.offer(first));
        var active = queue.take();
        assertSame(first, active.frame());
        assertEquals(DanmakuSendQueue.Offer.QUEUED, queue.offer(second));
        queue.close();
        assertTrue(limits.tryAcquire(99));
        assertFalse(limits.tryAcquire(99));
        queue.complete(active, () -> { });
        assertTrue(limits.tryAcquire(99));
    }

    @Test void sharedPreparationServesAnotherConnectionWhileBrokerConfirmationIsPending() throws Exception {
        var limits = new DanmakuSendQueue.Limits(3, 3, 1);
        var work = new ArrayDeque<Runnable>();
        var sender = connect("99", limits, work::addLast);
        var peer = connect("99", limits, work::addLast);
        var first = new CompletableFuture<Void>();
        when(producer.sendMessageAsync(anyString(), anyString()))
                .thenReturn(first, CompletableFuture.completedFuture(null), CompletableFuture.completedFuture(null));

        sender.writeInbound(new TextWebSocketFrame(message().put("content", "first").toString()));
        sender.writeInbound(new TextWebSocketFrame(message().put("content", "second").toString()));
        peer.writeInbound(new TextWebSocketFrame(message().put("content", "peer").toString()));
        assertEquals(2, work.size());
        verifyNoInteractions(producer);
        work.removeFirst().run();
        work.removeFirst().run();
        messages(peer);
        assertEquals("peer", messages(sender).get(0).path("data").get(0).path("text").asText());
        assertTrue(work.isEmpty());

        first.completeExceptionally(new IllegalStateException("Broker unavailable"));
        assertEquals(4, messages(sender).get(0).path("type").asInt());
        work.removeFirst().run();
        messages(sender);
        assertEquals("second", messages(peer).get(0).path("data").get(0).path("text").asText());
        assertTrue(limits.tryAcquire(99));
    }

    @Test void sharedPreparationSerializesValidationAndWaitsForEarlierBrokerOutcomes() throws Exception {
        var limits = new DanmakuSendQueue.Limits(4, 4, 2);
        var work = new ArrayDeque<Runnable>();
        var sender = connect("99", limits, work::addLast);
        var viewer = connect("99", limits, work::addLast);
        var first = new CompletableFuture<Void>();
        when(producer.sendMessageAsync(anyString(), anyString())).thenReturn(first, CompletableFuture.completedFuture(null));

        sender.writeInbound(new TextWebSocketFrame(message().put("content", "first").toString()));
        sender.writeInbound(new TextWebSocketFrame("malformed"));
        sender.writeInbound(new TextWebSocketFrame(message().put("content", "third").toString()));
        assertEquals(1, work.size());
        work.removeFirst().run();
        assertTrue(messages(sender).isEmpty());
        work.removeFirst().run();
        assertTrue(messages(sender).isEmpty());
        assertTrue(work.isEmpty());

        first.complete(null);
        assertEquals(List.of(5, 4), messages(sender).stream().map(frame -> frame.path("type").asInt()).toList());
        assertEquals("first", messages(viewer).get(0).path("data").get(0).path("text").asText());
        work.removeFirst().run();
        messages(sender);
        assertEquals("third", messages(viewer).get(0).path("data").get(0).path("text").asText());
    }

    private EmbeddedChannel connect(String room) throws Exception {
        return connect(room, defaultLimits);
    }

    private EmbeddedChannel connect(String room, DanmakuSendQueue.Limits limits) throws Exception {
        return connect(room, limits, Runnable::run);
    }

    private EmbeddedChannel connect(String room, DanmakuSendQueue.Limits limits, Executor preparation) throws Exception {
        var channel=new EmbeddedChannel(io.netty.channel.DefaultChannelId.newInstance(),
                new WebSocketHandler(producer, authentication, videos, broadcasts, limits, preparation));
        channels.add(channel);
        channel.pipeline().fireUserEventTriggered(new WebSocketServerProtocolHandler.HandshakeComplete("/ws/bulletScreen/"+room,new DefaultHttpHeaders(),null));
        for(var current:channels) messages(current);
        return channel;
    }
    private ObjectNode message() {
        return JSON.createObjectNode().put("authorization",alice).put("videoId","99").put("userId","1")
                .put("content","hello").put("playbackTime",1.25);
    }
    private List<JsonNode> messages(EmbeddedChannel channel) throws Exception {
        channel.runPendingTasks();List<JsonNode> output=new ArrayList<>();Object frame;
        while((frame=channel.readOutbound())!=null) {
            try { if(frame instanceof TextWebSocketFrame text) output.add(JSON.readTree(text.text())); }
            finally { ReferenceCountUtil.release(frame); }
        }
        return output;
    }
}
