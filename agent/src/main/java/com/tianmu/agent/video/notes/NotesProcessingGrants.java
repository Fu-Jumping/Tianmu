package com.tianmu.agent.video.notes;

import com.tianmu.agent.runtime.control.CallControl;
import com.tianmu.agent.video.content.*;
import java.util.*;

/** Delegates persisted task owner revalidation to the business primary database. */
public final class NotesProcessingGrants implements NotesCoordinator.ProcessingGrants {
    private final HttpContentProcessingAuthority authority;
    public NotesProcessingGrants(HttpContentProcessingAuthority authority){this.authority=Objects.requireNonNull(authority);}
    public void requireRoot(NotesJob.Job root,CallControl control){var request=root.request();authority.requireNotes(request.tenantId(),request.userId(),request.asset(),request.durationMs(),control);}
    public ContentWork.Grant issue(NotesJob.Job root,NotesJob.PlanUnit unit,UUID requestId,CallControl control){
        var request=root.request();return authority.issueNotes(request.tenantId(),request.userId(),request.asset(),request.durationMs(),requestId,control);
    }
}
