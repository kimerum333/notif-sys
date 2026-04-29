package com.example.notifsys.domain.notification;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

@Service
public class NotificationPickupService {

    private final NotificationRepository repo;

    public NotificationPickupService(NotificationRepository repo) {
        this.repo = repo;
    }

    @Transactional
    public List<Notification> pickAndMarkProcessing(int batchSize) {
        List<Notification> targets = repo.findDispatchTargetsForUpdate(Instant.now(), batchSize);
        for (Notification n : targets) {
            n.markProcessing();
        }
        return targets;
    }
}
