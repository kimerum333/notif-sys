package com.example.notifsys.domain.notification.template;

import com.example.notifsys.domain.notification.NotificationType;
import jakarta.annotation.PostConstruct;
import tools.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;

@Component
public class JsonFileMessageTemplateRepository implements MessageTemplateRepository {

    private static final Logger log = LoggerFactory.getLogger(JsonFileMessageTemplateRepository.class);
    private static final String TEMPLATE_PATH_FORMAT = "templates/%s.json";

    private final ObjectMapper objectMapper;
    private final Map<NotificationType, MessageTemplate> cache = new EnumMap<>(NotificationType.class);

    public JsonFileMessageTemplateRepository(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    void loadTemplates() {
        for (NotificationType type : NotificationType.values()) {
            if (type == NotificationType.CUSTOM) {
                continue;
            }
            String path = TEMPLATE_PATH_FORMAT.formatted(type.name());
            ClassPathResource resource = new ClassPathResource(path);
            try (InputStream in = resource.getInputStream()) {
                MessageTemplate template = objectMapper.readValue(in, MessageTemplate.class);
                cache.put(type, template);
                log.debug("Loaded template for {} from {}", type, path);
            } catch (IOException e) {
                throw new IllegalStateException(
                        "Failed to load message template for type=" + type + " at path=" + path, e);
            }
        }
        log.info("Loaded {} message templates", cache.size());
    }

    @Override
    public Optional<MessageTemplate> findByType(NotificationType type) {
        return Optional.ofNullable(cache.get(type));
    }
}