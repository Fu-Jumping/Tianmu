package com.orangecode.tianmu.agentbridge;

import io.minio.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import java.nio.file.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="CONTENT_MEDIA_FFMPEG_IT",matches="true")
class AudioOnlyExtractionIntegrationTest {
    @TempDir Path directory;
    @Test void realFfmpegCanExtractAudioFromMp4WithoutAnyVideoTrack()throws Exception {
        Path input=directory.resolve("audio-only.mp4");
        Process create=new ProcessBuilder("ffmpeg","-nostdin","-v","error","-f","lavfi","-i","sine=frequency=440:sample_rate=16000","-t","2","-c:a","aac","-f","mp4",input.toString())
                .redirectError(ProcessBuilder.Redirect.INHERIT).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        try{assertTrue(create.waitFor(15,TimeUnit.SECONDS));assertEquals(0,create.exitValue());}finally{if(create.isAlive())create.destroyForcibly();}
        byte[] bytes=Files.readAllBytes(input);
        var db=mock(JdbcTemplate.class);var minio=mock(MinioClient.class);var stat=mock(StatObjectResponse.class);
        when(db.queryForList(anyString(),any(Object[].class))).thenReturn(List.of(Map.of("file_url","http://storage/tianmu-upload/abcdefgh.mp4","duration",2.0)));
        when(stat.size()).thenReturn((long)bytes.length);when(stat.etag()).thenReturn("fixture-etag");when(minio.statObject(any(StatObjectArgs.class))).thenReturn(stat);
        when(minio.getObject(any(GetObjectArgs.class))).thenAnswer(call->{
            var stream=new ByteArrayInputStream(bytes);var response=mock(GetObjectResponse.class);
            when(response.read(any(byte[].class))).thenAnswer(read->stream.read(read.getArgument(0)));
            return response;
        });
        var service=new BusinessVideoContent(db,minio,"tianmu-upload");
        String version=service.publicSource(7).sourceVersion();
        var media=service.publicAudioSample(7,version,0,1000);
        assertTrue(media.frames().isEmpty());byte[] wav=Base64.getDecoder().decode(media.audioBase64());
        assertTrue(wav.length>44);assertEquals('R',wav[0]);assertEquals('I',wav[1]);
        assertEquals("VIDEO_DECODE_FAILED",assertThrows(BridgeException.class,()->service.publicSample(7,version,0,1000)).code());
    }
}
