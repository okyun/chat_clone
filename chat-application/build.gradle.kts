plugins {
    kotlin("jvm")
    kotlin("plugin.spring")
    kotlin("plugin.jpa")
    id("org.springframework.boot") version "3.3.4"
    id("io.spring.dependency-management")
}

dependencyManagement {
    imports {
        mavenBom("org.springframework.boot:spring-boot-dependencies:3.3.4")
    }
}

dependencies {
    // 모든 하위 모듈 포함 (조립자 역할)
    implementation(project(":chat-api"))
    implementation(project(":chat-domain"))
    implementation(project(":chat-persistence"))
    implementation(project(":chat-websocket"))

    // 메인 애플리케이션 실행에 필요한 의존성만
    implementation("org.springframework.boot:spring-boot-starter")
    implementation("org.springframework.boot:spring-boot-starter-actuator")

    // JPA 어노테이션 사용을 위해 필요 (@EnableJpaRepositories, @EnableJpaAuditing, @EntityScan)
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")

    // 데이터베이스 (런타임에만 필요)
    runtimeOnly("org.postgresql:postgresql")
    runtimeOnly("com.h2database:h2")
}
