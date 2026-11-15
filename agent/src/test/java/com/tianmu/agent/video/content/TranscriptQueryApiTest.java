package com.tianmu.agent.video.content;

import com.tianmu.agent.access.IdentityPort;
import com.tianmu.agent.api.*;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.mockito.Mockito.*;

class TranscriptQueryApiTest {
    @Test void unauthenticatedReadCannotReachIdentityOrContentStores()throws Exception {
        var identity=mock(IdentityPort.class);var reader=mock(TranscriptRetrievalService.class);
        var policy=new TranscriptQueryProperties("asr-v1","unused",60000,128,12,8000,4194304,4,15);
        var mvc=MockMvcBuilders.standaloneSetup(new TranscriptQueryController(identity,reader,policy)).setControllerAdvice(new ApiExceptionHandler()).build();
        mvc.perform(get("/api/agent/videos/800000026087195706/transcript/identity")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/agent/videos/800000026087195706/transcript/query").contentType("application/json")
                .content("{\"sourceVersion\":\"v1\",\"mode\":\"CURRENT\",\"anchorMs\":30000}"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(identity,reader);
    }
}
