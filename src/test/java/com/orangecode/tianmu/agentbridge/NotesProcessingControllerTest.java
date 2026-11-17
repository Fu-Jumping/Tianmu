package com.orangecode.tianmu.agentbridge;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class NotesProcessingControllerTest {
    @TempDir Path directory;
    final String token="local-test-platform-token-32-characters";
    final BusinessContentIdentityReader reader=mock(BusinessContentIdentityReader.class);
    final BusinessContentAuthority authority=mock(BusinessContentAuthority.class);
    final BusinessContentAuthority.Asset asset=new BusinessContentAuthority.Asset("site",7,"v1","public",1);
    NotesProcessingController controller() throws Exception {
        var file=directory.resolve("token");Files.writeString(file,token);
        return new NotesProcessingController(reader,authority,new ContentProcessingProperties(file.toString(),900,5),new BridgeProperties("site",Set.of("0"),"Asia/Shanghai",2));
    }
    @Test void unauthorizedServiceCannotInspectAccountOrIssueGrant() throws Exception {
        var target=controller();assertThrows(BridgeException.class,()->target.check("wrong",new NotesProcessingController.Context("site","123",asset,30000)));
        verifyNoInteractions(reader,authority);
    }
    @Test void revokedOwnerOrChangedVideoFailsBeforeGrant() throws Exception {
        var target=controller();var context=new NotesProcessingController.Context("site","123",asset,30000);
        when(reader.activeViewer("site","123",Set.of("0"))).thenThrow(new BridgeException("FORBIDDEN",403));
        assertThrows(BridgeException.class,()->target.issue(token,new NotesProcessingController.Issue(context,UUID.randomUUID())));verifyNoInteractions(authority);
        var principal=new BusinessAuthentication.Principal("site","123");doReturn(principal).when(reader).activeViewer("site","123",Set.of("0"));
        when(reader.readableIdentity(principal,7)).thenReturn(new BusinessContentIdentityReader.Identity(new BusinessContentAuthority.Asset("site",7,"v2","public",1),30000));
        assertThrows(BridgeException.class,()->target.issue(token,new NotesProcessingController.Issue(context,UUID.randomUUID())));verifyNoInteractions(authority);
    }
    @Test void ownerAndVersionAreCheckedBothBeforeAndAfterGrant() throws Exception {
        var target=controller();var principal=new BusinessAuthentication.Principal("site","123");var context=new NotesProcessingController.Context("site","123",asset,30000);var id=UUID.randomUUID();
        when(reader.activeViewer("site","123",Set.of("0"))).thenReturn(principal);when(reader.readableIdentity(principal,7)).thenReturn(new BusinessContentIdentityReader.Identity(asset,30000));
        when(authority.issueNotes(id,7,"123")).thenReturn(new BusinessContentAuthority.Grant(id,asset,Instant.now().plusSeconds(60)));
        assertEquals(id,target.issue(token,new NotesProcessingController.Issue(context,id)).getBody().id());verify(reader,times(2)).activeViewer("site","123",Set.of("0"));
    }
}
