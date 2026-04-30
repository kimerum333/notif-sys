package com.example.notifsys.domain.notification.sender;

import com.example.notifsys.domain.notification.NotificationChannel;
import com.example.notifsys.domain.notification.template.MessageRenderer;
import org.springframework.stereotype.Component;

@Component
public class MockInAppSender extends EventIdPatternMockSender {

    public MockInAppSender(MessageRenderer messageRenderer) {
        super(messageRenderer);
    }

    @Override
    public NotificationChannel channel() {
        return NotificationChannel.IN_APP;
    }
}