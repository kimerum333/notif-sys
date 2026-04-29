package com.example.notifsys.domain.notification.sender;

import com.example.notifsys.domain.notification.Notification;
import com.example.notifsys.domain.notification.NotificationChannel;

public interface NotificationSender {

    NotificationChannel channel();

    SendResult send(Notification notification);
}