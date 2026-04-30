package com.example.notifsys.domain.notification.template;

import com.example.notifsys.domain.notification.NotificationType;

import java.util.Optional;

/**
 * 알림 타입별 메시지 템플릿 조회 포트.
 *
 * 현재 구현체는 classpath JSON 파일을 startup에 로드한다 ({@code src/main/resources/templates/}).
 * 운영 전환 시 DB 테이블 / 외부 CMS 등으로 구현체만 교체.
 *
 * CUSTOM 타입은 템플릿이 아닌 reference_data 자체에서 메시지를 추출하므로
 * 이 리포지토리의 책임 밖이다 ({@code MessageRenderer}가 분기 처리).
 */
public interface MessageTemplateRepository {

    Optional<MessageTemplate> findByType(NotificationType type);
}