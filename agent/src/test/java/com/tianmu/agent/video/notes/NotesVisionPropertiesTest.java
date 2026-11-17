package com.tianmu.agent.video.notes;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NotesVisionPropertiesTest {
    @Test void blankConfigurationRetainsExistingAdapter(){assertFalse(new NotesVisionProperties("","","").configured());}
    @Test void partialOrInsecureOverrideCannotStart(){
        assertThrows(IllegalArgumentException.class,()->new NotesVisionProperties("qwen3-vl-plus","",""));
        assertThrows(IllegalArgumentException.class,()->new NotesVisionProperties("qwen3-vl-plus","http://example.org","/private/key"));
        assertThrows(IllegalArgumentException.class,()->new NotesVisionProperties("qwen3-vl-plus","https://user:secret@example.org","/private/key"));
    }
    @Test void completeOverrideIsExplicitAndDoesNotLogPrivateConfiguration(){
        var value=new NotesVisionProperties("qwen3-vl-plus","https://dashscope.aliyuncs.com/compatible-mode/v1","/private/key");
        assertTrue(value.configured());assertFalse(value.toString().contains("/private/key"));
    }
}
