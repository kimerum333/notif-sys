package com.example.notifsys.domain.notification;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class NotificationPoller {

    private static final Logger log = LoggerFactory.getLogger(NotificationPoller.class);

    private final NotificationStuckRecoveryService recoveryService;
    private final NotificationPickupService pickupService;
    private final NotificationDispatcher dispatcher;
    private final WorkerProperties properties;

    public NotificationPoller(NotificationStuckRecoveryService recoveryService,
                              NotificationPickupService pickupService,
                              NotificationDispatcher dispatcher,
                              WorkerProperties properties) {
        this.recoveryService = recoveryService;
        this.pickupService = pickupService;
        this.dispatcher = dispatcher;
        this.properties = properties;
    }

    @Scheduled(
            fixedDelayString = "${notification.worker.poll-interval-ms}",
            initialDelayString = "${notification.worker.poll-interval-ms}"
    )
    public void poll() {
        try {
            recoveryService.recover(properties.batchSize());
        } catch (Exception e) {
            log.error("stuck recovery pass failed", e);
        }

        List<Notification> picked;
        try {
            picked = pickupService.pickAndMarkProcessing(properties.batchSize());
        } catch (Exception e) {
            log.error("pickup pass failed", e);
            return;
        }

        for (Notification n : picked) {
            try {
                dispatcher.dispatch(n);
            } catch (Exception e) {
                log.error("dispatch failed for notification id={}", n.getId(), e);
            }
        }
    }
}
