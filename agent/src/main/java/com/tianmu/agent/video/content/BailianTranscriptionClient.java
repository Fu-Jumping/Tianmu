package com.tianmu.agent.video.content;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tianmu.agent.core.AgentException;
import com.tianmu.agent.harness.trace.TraceAttributes;
import com.tianmu.agent.resource.BoundedHttpBody;
import com.tianmu.agent.runtime.control.CallControl;
import com.tianmu.agent.runtime.control.ModelBudget;
import com.tianmu.agent.video.TranscriptionPort;
import io.opentelemetry.api.trace.Span;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.ExecutionException;

/** Explicitly constructed for the content worker; it is not a global transcription bean. */
public final class BailianTranscriptionClient implements TranscriptionPort {
    public static final String MODEL = "qwen-audio-3.0-asr-flash";
    @Override public String modelName(){return MODEL;}
    private static final String PATH = "/api/v1/services/aigc/multimodal-generation/generation";
    private final URI endpoint;
    private final String apiKey;
    private final HttpClient http;
    private final ObjectMapper json;
    private final Duration timeout;

    public static BailianTranscriptionClient create(String region, String apiKey, ObjectMapper json, Duration timeout) {
        String host = switch (region) {
            case "beijing" -> "dashscope.aliyuncs.com";
            case "singapore" -> "dashscope-intl.aliyuncs.com";
            default -> throw new IllegalArgumentException("Unsupported Bailian region");
        };
        return new BailianTranscriptionClient(URI.create("https://" + host + PATH), apiKey, json, timeout,
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER).build());
    }

    BailianTranscriptionClient(URI endpoint, String apiKey, ObjectMapper json, Duration timeout, HttpClient http) {
        this.endpoint=Objects.requireNonNull(endpoint); this.apiKey=Objects.requireNonNull(apiKey);
        if (apiKey.isBlank() || apiKey.indexOf('\n')>=0 || apiKey.indexOf('\r')>=0 || timeout==null || timeout.isNegative() || timeout.isZero())
            throw new IllegalArgumentException("Invalid ASR configuration");
        this.timeout=timeout; this.http=Objects.requireNonNull(http);
        this.json=Objects.requireNonNull(json).copy().enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    }

    @Override public String transcribe(String audio, CallControl control) {
        throw new AgentException("VIDEO_TRANSCRIPTION_FAILED", "Timed audio range is required");
    }

    @Override public TimedTranscript transcribeTimed(String audio, long startMs, long endMs, CallControl control) {
        if (startMs<0 || endMs<=startMs || endMs-startMs>120_000 || audio==null || audio.length()>5_333_336)
            throw new AgentException("CONTENT_AUDIO_UNAVAILABLE","Audio clip is invalid");
        byte[] decoded;
        try { decoded=Base64.getDecoder().decode(audio); }
        catch(IllegalArgumentException e) { throw new AgentException("CONTENT_AUDIO_UNAVAILABLE","Audio clip is invalid"); }
        if(decoded.length==0 || decoded.length>4_000_000)
            throw new AgentException("CONTENT_AUDIO_UNAVAILABLE","Audio clip is invalid");
        WaveClip.requireNormalized(decoded,endMs-startMs);
        try {
            var audioContent=Map.of("type","input_audio","input_audio",Map.of("data","data:audio/wav;base64,"+audio));
            var message=Map.of("role","user","content",List.of(audioContent));
            byte[] payload=json.writeValueAsBytes(Map.of("model",MODEL,"input",Map.of("messages",List.of(message)),
                    "parameters",Map.of("format","wav","sample_rate",16000)));
            JsonNode response=ModelBudget.execute(control,MODEL,true,()->send(payload,control));
            try { return BailianTranscriptParser.parse(response,startMs,endMs); }
            catch (RuntimeException e) { throw new AgentException("INVALID_TRANSCRIPT_TIMING","Audio timestamps cannot be aligned to the playback clip"); }
        } catch(AgentException e) { throw e; }
        catch(Exception e) { throw new AgentException("VIDEO_TRANSCRIPTION_FAILED","Audio transcription unavailable"); }
    }

    private JsonNode send(byte[] payload, CallControl control) {
        control.beforeDispatch();
        long millis=Math.min(timeout.toMillis(),Duration.between(Instant.now(),control.deadline()).toMillis());
        if(millis<=0)throw new AgentException("VIDEO_MODEL_TIMEOUT","Audio transcription deadline exceeded");
        try {
            var request=HttpRequest.newBuilder(endpoint).timeout(Duration.ofMillis(millis)).header("Authorization","Bearer "+apiKey)
                    .header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofByteArray(payload)).build();
            TraceAttributes.apply(Span.current(),Map.of("model.dispatched",true,"model.version_state","REQUESTED_ONLY"));
            var future=http.sendAsync(request,ignored->new BoundedHttpBody(131072));
            try(var registration=control.cancellation().onCancel(()->future.cancel(true))) {
                HttpResponse<byte[]> response;
                try { response=future.get(millis,TimeUnit.MILLISECONDS); }
                catch(TimeoutException e) { future.cancel(true); throw new AgentException("VIDEO_MODEL_TIMEOUT","Audio transcription timed out"); }
                catch(InterruptedException e) { future.cancel(true); Thread.currentThread().interrupt(); throw new AgentException("CANCELLED","Audio transcription interrupted"); }
                catch(ExecutionException e) {
                    if(e.getCause() instanceof HttpTimeoutException)
                        throw new AgentException("VIDEO_MODEL_TIMEOUT","Audio transcription timed out");
                    throw e;
                }
                control.checkpoint();
                TraceAttributes.apply(Span.current(),Map.of("model.http_status",response.statusCode()));
                if(response.statusCode()!=200) {
                    int status=response.statusCode();
                    if(status==429 || status>=500)throw new AgentException("VIDEO_MODEL_UNAVAILABLE","Audio transcription service unavailable");
                    throw new AgentException("VIDEO_TRANSCRIPTION_REJECTED","Audio transcription service rejected the request");
                }
                return json.readTree(response.body());
            }
        } catch(AgentException e) { throw e; }
        catch(Exception e) { control.checkpoint(); throw new AgentException("VIDEO_MODEL_UNAVAILABLE","Audio transcription service unavailable"); }
    }
}
