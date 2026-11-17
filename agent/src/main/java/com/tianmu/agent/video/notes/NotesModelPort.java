package com.tianmu.agent.video.notes;

import com.fasterxml.jackson.databind.JsonNode;
import com.tianmu.agent.runtime.control.CallControl;
import java.util.Map;

/** Each operation must be admitted by a separate durable dispatch receipt before calling this port. */
public interface NotesModelPort {
    enum Operation { DRAFT, CHECK, REPAIR, RECHECK, ARBITRATE }
    String version();
    JsonNode request(Operation operation,Map<String,Object> data,int maxOutputTokens,CallControl control);
}
