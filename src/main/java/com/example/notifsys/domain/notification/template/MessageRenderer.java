package com.example.notifsys.domain.notification.template;

import com.example.notifsys.domain.notification.Notification;
import com.example.notifsys.domain.notification.NotificationType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Notification 엔티티 → 발송용 메시지 텍스트로 렌더링.
 *
 * - 일반 타입: {@link MessageTemplateRepository}에서 타입별 템플릿 조회 후
 *   {@code reference_data}의 값을 placeholder({@code {key}})에 치환.
 * - CUSTOM 타입: 템플릿 조회 없이 {@code reference_data.message}(필수) /
 *   {@code reference_data.title}(선택)을 그대로 사용.
 *
 * 렌더링 실패는 호출자가 PERMANENT 발송 실패로 매핑하는 것을 권장 (재시도 무의미).
 */
@Service
public class MessageRenderer {

    private static final Logger log = LoggerFactory.getLogger(MessageRenderer.class);
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{(\\w+)}");

    private final MessageTemplateRepository templateRepository;

    public MessageRenderer(MessageTemplateRepository templateRepository) {
        this.templateRepository = templateRepository;
    }

    public RenderedMessage render(Notification notification) {
        Map<String, Object> data = notification.getReferenceData();
        if (notification.getType() == NotificationType.CUSTOM) {
            return renderCustom(notification, data);
        }
        MessageTemplate template = templateRepository.findByType(notification.getType())
                .orElseThrow(() -> new IllegalStateException(
                        "No template registered for type=" + notification.getType()));
        return new RenderedMessage(
                substitute(template.title(), data),
                substitute(template.body(), data)
        );
    }

    private RenderedMessage renderCustom(Notification notification, Map<String, Object> data) {
        if (data == null || !data.containsKey("message")) {
            throw new IllegalStateException(
                    "CUSTOM notification id=" + notification.getId()
                            + " requires reference_data.message");
        }
        String body = String.valueOf(data.get("message"));
        Object titleObj = data.get("title");
        String title = titleObj == null ? "" : String.valueOf(titleObj);
        return new RenderedMessage(title, body);
    }

    private String substitute(String template, Map<String, Object> data) {
        if (template == null || template.isEmpty()) {
            return "";
        }
        if (data == null || data.isEmpty()) {
            return template;
        }
        Matcher matcher = PLACEHOLDER.matcher(template);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            String key = matcher.group(1);
            Object value = data.get(key);
            String replacement;
            if (value == null) {
                log.warn("Missing placeholder '{}' in template, leaving literal", key);
                replacement = matcher.group(0);
            } else {
                replacement = String.valueOf(value);
            }
            matcher.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    public record RenderedMessage(String title, String body) {
    }
}