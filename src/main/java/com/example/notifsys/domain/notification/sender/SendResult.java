package com.example.notifsys.domain.notification.sender;

public sealed interface SendResult {

    record Success() implements SendResult {}

    record Failure(FailureKind kind, String reason) implements SendResult {}
}