package com.example.notifsys.domain.notification;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface NotificationRepository extends JpaRepository<Notification, Long> {

    Page<Notification> findByRecipientId(Long recipientId, Pageable pageable);

    Page<Notification> findByRecipientIdAndReadAtIsNotNull(Long recipientId, Pageable pageable);

    Page<Notification> findByRecipientIdAndReadAtIsNull(Long recipientId, Pageable pageable);

    @Query(value = """
            SELECT * FROM notification
            WHERE status IN ('PENDING', 'FAILED')
              AND (retry_after IS NULL OR retry_after <= :now)
              AND (scheduled_at IS NULL OR scheduled_at <= :now)
            ORDER BY created_at
            LIMIT :batchSize
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<Notification> findDispatchTargetsForUpdate(@Param("now") Instant now,
                                                    @Param("batchSize") int batchSize);

    @Query(value = """
            SELECT * FROM notification
            WHERE status = 'PROCESSING'
              AND updated_at <= :threshold
            ORDER BY updated_at
            LIMIT :batchSize
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<Notification> findStuckProcessingForUpdate(@Param("threshold") Instant threshold,
                                                    @Param("batchSize") int batchSize);
}
