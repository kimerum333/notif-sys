-- ============================================================
-- notification
-- ============================================================
-- type   : COURSE_REGISTRATION | PAYMENT_CONFIRMED | LECTURE_REMINDER | CANCELLATION | CUSTOM
-- channel: EMAIL | IN_APP
-- status : PENDING | PROCESSING | SENT | FAILED | DEAD_LETTER
-- ============================================================
CREATE TABLE notification (
    id             BIGSERIAL    PRIMARY KEY,
    recipient_id   BIGINT       NOT NULL,
    type           VARCHAR(30)  NOT NULL,
    channel        VARCHAR(10)  NOT NULL,
    event_id       VARCHAR(100) NOT NULL,
    reference_data JSONB,
    status         VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
    fail_count     INT          NOT NULL DEFAULT 0,
    stuck_count    INT          NOT NULL DEFAULT 0,
    retry_after    TIMESTAMPTZ,
    failure_reason TEXT,
    scheduled_at   TIMESTAMPTZ,
    read_at        TIMESTAMPTZ,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT uq_idempotency UNIQUE (recipient_id, event_id, type, channel)
);

CREATE INDEX idx_notification_recipient ON notification(recipient_id);
CREATE INDEX idx_notification_status    ON notification(status);

-- ============================================================
-- notification_error_log
-- ============================================================
CREATE TABLE notification_error_log (
    id              BIGSERIAL   PRIMARY KEY,
    notification_id BIGINT      NOT NULL REFERENCES notification(id),
    error_message   TEXT        NOT NULL,
    occurred_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);