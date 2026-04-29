package com.example.notifsys.domain.notification.sender;

import com.example.notifsys.domain.notification.NotificationChannel;
import org.springframework.stereotype.Component;

@Component
public class MockInAppSender extends EventIdPatternMockSender {

    @Override
    public NotificationChannel channel() {
        return NotificationChannel.IN_APP;
    }
}
