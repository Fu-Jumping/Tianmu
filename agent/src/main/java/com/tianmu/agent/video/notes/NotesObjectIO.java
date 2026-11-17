package com.tianmu.agent.video.notes;

import com.tianmu.agent.runtime.control.CallControl;
import com.tianmu.agent.runtime.store.ObjectStore;
import java.security.MessageDigest;
import java.util.*;

/** Shared bounded immutable-write reconciliation over the existing ObjectStore. */
final class NotesObjectIO {
    private NotesObjectIO() {}
    static void put(ObjectStore objects,String key,byte[] body,CallControl control) {
        NotesLeaseRunner.check(control);
        if(body.length<1 || body.length>1048576)throw new IllegalArgumentException("Notes object limit");
        try{objects.put(key,body,control);}
        catch(RuntimeException uncertain){
            try{if(!Arrays.equals(objects.get(key,body.length,sha(body)),body))throw uncertain;}
            catch(RuntimeException unavailable){throw uncertain;}
        }
        NotesLeaseRunner.check(control);
    }
    static String sha(byte[] body) {
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));}
        catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
    }
}
