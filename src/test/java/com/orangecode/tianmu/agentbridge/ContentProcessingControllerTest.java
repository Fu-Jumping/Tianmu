package com.orangecode.tianmu.agentbridge;

import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

class ContentProcessingControllerTest {
    private static final String TOKEN="local-test-service-token-32-characters";
    @Test void serviceTokenIsRequiredBeforeAnyAuthorityOrMediaOperation(){
        var authority=mock(BusinessContentAuthority.class);
        var controller=new ContentProcessingController(authority,TOKEN.getBytes(StandardCharsets.UTF_8));
        assertEquals(401,assertThrows(BridgeException.class,()->controller.issue(null,new ContentProcessingController.Issue(UUID.randomUUID(),"7"))).status());
        assertEquals(401,assertThrows(BridgeException.class,()->controller.check("viewer-token",null)).status());
        assertEquals(401,assertThrows(BridgeException.class,()->controller.read("viewer-token",null)).status());
        verifyNoInteractions(authority);
    }
    @Test void longVideoIdsAreParsedWithoutJavascriptNumberCoercion(){
        var authority=mock(BusinessContentAuthority.class);var controller=new ContentProcessingController(authority,TOKEN.getBytes(StandardCharsets.UTF_8));
        UUID id=UUID.randomUUID();controller.issue(TOKEN,new ContentProcessingController.Issue(id,"800000026087195706"));
        verify(authority).issue(id,800000026087195706L);
        assertEquals(400,assertThrows(BridgeException.class,()->controller.issue(TOKEN,new ContentProcessingController.Issue(id,"9223372036854775808"))).status());
    }
    @Test void businessGrantMatchesSharedWireContract()throws Exception {
        var json=new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules()
                .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        var file=java.nio.file.Path.of(System.getProperty("basedir"),"contracts/video-content/grant-v1.json");
        var expected=json.readTree(java.nio.file.Files.readAllBytes(file));
        var grant=json.treeToValue(expected,BusinessContentAuthority.Grant.class);
        assertEquals(expected,json.readTree(json.writeValueAsBytes(grant)));
    }
}
