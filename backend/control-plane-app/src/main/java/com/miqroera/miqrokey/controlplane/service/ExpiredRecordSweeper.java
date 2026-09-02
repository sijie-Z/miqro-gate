package com.miqroera.miqrokey.controlplane.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Periodic GC for records with a retention horizon (F06): finished export
 * artifacts past their download window and deletion requests past their
 * confirmation window are reclaimed on a fixed-delay schedule. EXECUTED
 * deletion requests and the audit chain are permanent and never swept.
 */
@Service
public class ExpiredRecordSweeper {

    private static final Logger LOG = LoggerFactory.getLogger(ExpiredRecordSweeper.class);

    private final ExportTaskService exportTaskService;
    private final UsageDeletionService usageDeletionService;

    public ExpiredRecordSweeper(ExportTaskService exportTaskService, UsageDeletionService usageDeletionService) {
        this.exportTaskService = exportTaskService;
        this.usageDeletionService = usageDeletionService;
    }

    @Scheduled(fixedDelayString = "${miqrokey.cleanup.expired-sweep-ms:3600000}")
    public void sweep() {
        try {
            int exports = exportTaskService.sweepExpired();
            int deletions = usageDeletionService.sweepExpired();
            if (exports > 0 || deletions > 0) {
                LOG.info("Expired-record sweep reclaimed {} exports and {} deletion requests", exports, deletions);
            }
        } catch (Exception e) {
            LOG.warn("Expired-record sweep failed", e);
        }
    }
}
