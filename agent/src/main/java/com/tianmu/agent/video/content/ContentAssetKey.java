package com.tianmu.agent.video.content;

/** Trusted media identity supplied by the business authority, never a client authorization grant. */
public record ContentAssetKey(String tenantId, long videoId, String sourceVersion,
                              String authorizationDomain, long revocationEpoch) {
    public ContentAssetKey {
        require(tenantId, 128); require(sourceVersion, 128); require(authorizationDomain, 128);
        if (videoId <= 0 || revocationEpoch < 0) throw new IllegalArgumentException("Invalid media identity");
    }
    private static void require(String value, int max) {
        if (value == null || value.isBlank() || value.length() > max)
            throw new IllegalArgumentException("Invalid media identity");
    }
}
