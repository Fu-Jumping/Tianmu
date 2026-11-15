package com.tianmu.agent.runtime.spi;

import com.tianmu.agent.runtime.RunState;

/** Revalidates source authority before inference; implementations own the backing ledger. */
public interface ContextAuthority {
    void verify(RunState run);
    /** A guarded source may require buffering provider text until transactional delivery. */
    default boolean permitsStreaming(RunState run){return true;}
}
