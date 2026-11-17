package com.tianmu.agent.video.notes;

import com.tianmu.agent.video.content.ContentAssetKey;
import com.tianmu.agent.video.content.ContentWork;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static com.tianmu.agent.video.notes.NotesJob.*;

class NotesJobTest {
    private final Policy policy=new Policy("zh","notes-v1","audio-visual-v1","generate-v1",60000,5000,100000);
    private Create request(String user,long epoch,String version) {
        return new Create("site",user,new ContentAssetKey("site",123,version,"public",epoch),125000,policy,"request-1",Instant.parse("2030-01-01T00:00:00Z"));
    }
    @Test void rootIdentityIsolatesUsersRevocationAndSourceVersion() {
        assertNotEquals(MysqlNotesJobStore.rootKey(request("a",1,"v1")),MysqlNotesJobStore.rootKey(request("b",1,"v1")));
        assertNotEquals(MysqlNotesJobStore.rootKey(request("a",1,"v1")),MysqlNotesJobStore.rootKey(request("a",2,"v1")));
        assertNotEquals(MysqlNotesJobStore.rootKey(request("a",1,"v1")),MysqlNotesJobStore.rootKey(request("a",1,"v2")));
    }
    @Test void tailAndOverlapPreserveFullTimelineWithoutExceedingSource() {
        var plan=sourcePlan(UUID.randomUUID(),125000,policy,"a".repeat(64));
        assertEquals(6,plan.size());
        var audio=plan.stream().filter(u->u.stage()==ContentWork.Stage.TRANSCRIBE).toList();
        assertEquals(0,audio.get(0).startMs()); assertEquals(65000,audio.get(0).dependencyEndMs());
        assertEquals(120000,audio.get(2).startMs()); assertEquals(125000,audio.get(2).endMs());
        assertEquals(115000,audio.get(2).dependencyStartMs()); assertEquals(125000,audio.get(2).dependencyEndMs());
        for(int i=1;i<audio.size();i++)assertEquals(audio.get(i-1).endMs(),audio.get(i).startMs());
    }
    @Test void childWorkIdentityIncludesRootAndRawDependencies() {
        var root=UUID.randomUUID(); var unit=sourcePlan(root,125000,policy,"a".repeat(64)).get(0);
        assertEquals(unit.segmentId(root),unit.segmentId(root));
        assertNotEquals(unit.segmentId(root),unit.segmentId(UUID.randomUUID()));
        var changed=new PlanUnit(unit.unitKey(),unit.stage(),unit.startMs(),unit.endMs(),unit.dependencyStartMs(),unit.dependencyEndMs(),"b".repeat(64));
        assertNotEquals(unit.segmentId(root),changed.segmentId(root));
        assertTrue(unit.segmentId(root).matches("[A-Za-z0-9_.:-]{1,128}"));
    }
    @Test void malformedPolicyAndCrossTenantAdmissionFailClosed() {
        assertThrows(IllegalArgumentException.class,()->new Policy("zh","s","a","g",120000,5000,1));
        assertThrows(IllegalArgumentException.class,()->new Create("other","a",request("a",1,"v1").asset(),1,policy,"key",Instant.now()));
        assertThrows(IllegalArgumentException.class,()->sourcePlan(UUID.randomUUID(),0,policy,"a".repeat(64)));
    }
    @Test void hashingIsLengthDelimited() {assertNotEquals(hash("a","bc"),hash("ab","c"));}
    @Test void mysqlPaddedIdentityAliasesAreRejectedBeforeReading() {
        var store=new MysqlNotesJobStore(org.mockito.Mockito.mock(javax.sql.DataSource.class),new com.fasterxml.jackson.databind.ObjectMapper());
        assertThrows(IllegalArgumentException.class,()->store.get(UUID.randomUUID(),"site ","a"));
        assertThrows(IllegalArgumentException.class,()->store.get(UUID.randomUUID(),"site","a "));
        assertThrows(IllegalArgumentException.class,()->new Create("site","a ",request("a",1,"v1").asset(),1,policy,"k",Instant.now()));
        assertThrows(IllegalArgumentException.class,()->new Policy("zh\n","s","a","g",60000,5000,1));
    }
}
