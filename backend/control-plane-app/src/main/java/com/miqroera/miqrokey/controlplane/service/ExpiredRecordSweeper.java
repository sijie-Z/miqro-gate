package com.miqroera.miqrokey.controlplane.service;

import com.miqroera.miqrokey.domain.repository.UserSessionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;

/**
 * Periodic GC for records with a retention horizon (F06): finished export
 * artifacts past their download window, deletion requests past their
 * confirmation window and login sessions past their expiry are reclaimed on a
 * fixed-delay schedule. EXECUTED deletion requests and the audit chain are
 * permanent and never swept.
 */
@Service
public class ExpiredRecordSweeper {

    private static final Logger LOG = LoggerFactory.getLogger(ExpiredRecordSweeper.class);

    private final ExportTaskService exportTaskService;
    private final UsageDeletionService usageDeletionService;
    private final UserSessionRepository userSessionRepository;

    public ExpiredRecordSweeper(ExportTaskService exportTaskService, UsageDeletionService usageDeletionService,
            UserSessionRepository userSessionRepository) {
        this.exportTaskService = exportTaskService;
        this.usageDeletionService = usageDeletionService;
        this.userSessionRepository = userSessionRepository;
    }

    @Scheduled(fixedDelayString = "${miqrokey.cleanup.expired-sweep-ms:3600000}")
    public void sweep() {
        try {
            int exports = exportTaskService.sweepExpired();
            int deletions = usageDeletionService.sweepExpired();
            int sessions = userSessionRepository.deleteExpired(Instant.now());
            if (exports > 0 || deletions > 0 || sessions > 0) {
                LOG.info("Expired-record sweep reclaimed {} exports, {} deletion requests and {} sessions", exports,
                        deletions, sessions);
            }
        } catch (Exception e) {
            LOG.warn("Expired-record sweep failed", e);
        }
    }
}
