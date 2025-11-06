package com.chat.application

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.autoconfigure.domain.EntityScan
import org.springframework.boot.runApplication
import org.springframework.data.jpa.repository.config.EnableJpaAuditing
import org.springframework.data.jpa.repository.config.EnableJpaRepositories

@SpringBootApplication(
    scanBasePackages = [
        "com.chat.application",
        "com.chat.domain", //도메인 모델과 인터페이스
        "com.chat.persistence",//repository 와 서비스 클래스 구현체
        "com.chat.api", //rest api
        "com.chat.websocket"//ws 통신에 대한 관련 컴포넌트
    ]
)
@EnableJpaAuditing // JPA에 대한 감사 기능 @CreatedDate
@EnableJpaRepositories(basePackages = ["com.chat.persistence.repository"])
@EntityScan(basePackages = ["com.chat.domain.model"])
class ChatApplication

fun main(args: Array<String>) {
    runApplication<ChatApplication>(*args)
}