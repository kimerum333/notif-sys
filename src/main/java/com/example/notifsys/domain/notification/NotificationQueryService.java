package com.example.notifsys.domain.notification;

import com.example.notifsys.api.common.NotFoundException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class NotificationQueryService {

    private final NotificationRepository repo;

    public NotificationQueryService(NotificationRepository repo) {
        this.repo = repo;
    }

    @Transactional(readOnly = true)
    public Notification findById(Long id) {
        return repo.findById(id)
                .orElseThrow(() -> new NotFoundException("Notification not found: id=" + id));
    }

    /**
     * @param read null = 전체, true = 읽음만, false = 안 읽음만
     */
    @Transactional(readOnly = true)
    public Page<Notification> findByRecipient(Long recipientId, Boolean read, Pageable pageable) {
        if (read == null) {
            return repo.findByRecipientId(recipientId, pageable);
        }
        return read
                ? repo.findByRecipientIdAndReadAtIsNotNull(recipientId, pageable)
                : repo.findByRecipientIdAndReadAtIsNull(recipientId, pageable);
    }
}