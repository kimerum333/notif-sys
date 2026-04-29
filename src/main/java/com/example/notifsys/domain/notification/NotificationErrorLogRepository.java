package com.example.notifsys.domain.notification;

import org.springframework.data.jpa.repository.JpaRepository;

public interface NotificationErrorLogRepository extends JpaRepository<NotificationErrorLog, Long> {
}