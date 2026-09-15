package com.miqroera.miqrokey.controlplane.client;

import java.util.List;

/**
 * Public price index client (issue #585). One implementation per source format;
 * the sync service treats a fetch failure as fatal for the whole run
 * (success-only pipeline, mirroring the model probe).
 */
public interface PriceSourceClient {

    /**
     * Fetches the current model quotes; failures raise
     * {@link PriceSourceException}.
     */
    List<SourceModelPrice> fetch();
}
