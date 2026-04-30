package com.example.notifsys.domain.notification.sender;

import com.example.notifsys.domain.notification.NotificationChannel;
import com.example.notifsys.domain.notification.template.MessageRenderer;
import org.springframework.stereotype.Component;

@Component
public class MockEmailSender extends EventIdPatternMockSender {

    public MockEmailSender(MessageRenderer messageRenderer) {
        super(messageRenderer);
    }

    @Override
    public NotificationChannel channel() {
        return NotificationChannel.EMAIL;
    }
}