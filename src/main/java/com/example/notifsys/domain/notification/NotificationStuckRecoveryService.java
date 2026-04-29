package com.example.notifsys.domain.notification;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

@Service
public class NotificationStuckRecoveryService {

    private final NotificationRepository repo;
    private final NotificationErrorLogRepository errorLogRepo;
    private final WorkerProperties properties;

    public NotificationStuckRecoveryService(NotificationRepository repo,
                                            NotificationErrorLogRepository errorLogRepo,
                                            WorkerProperties properties) {
        this.repo = repo;
        this.errorLogRepo = errorLogRepo;
        this.properties = properties;
    }

    @Transactional
    public int recover(int batchSize) {
        Instant threshold = Instant.now().minusSeconds(properties.stuckThresholdSeconds());
        List<Notification> stuck = repo.findStuckProcessingForUpdate(threshold, batchSize);
        for (Notification n : stuck) {
            int nextStuckCount = n.getStuckCount() + 1;
            if (nextStuckCount >= properties.maxStuckCount()) {
                String reason = "stuck threshold exceeded (count=" + nextStuckCount + ")";
                n.markStuckDeadLetter(reason);
                errorLogRepo.save(NotificationErrorLog.of(n, reason));
            } else {
                n.recoverFromStuck();
            }
        }
        return stuck.size();
    }
}
