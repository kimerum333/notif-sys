package com.example.notifsys.domain.notification.sender;

import com.example.notifsys.domain.notification.NotificationChannel;
import org.springframework.stereotype.Component;

@Component
public class MockEmailSender extends EventIdPatternMockSender {

    @Override
    public NotificationChannel channel() {
        return NotificationChannel.EMAIL;
    }
}
