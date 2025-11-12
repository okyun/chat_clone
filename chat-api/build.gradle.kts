plugins {
    kotlin("jvm")
    kotlin("plugin.spring")
    id("io.spring.dependency-management")
}

dependencyManagement {
    imports {
        mavenBom("org.springframework.boot:spring-boot-dependencies:3.3.4")
    }
}

dependencies {
    // 도메인 모듈 의존성 (DTO와 서비스 인터페이스 사용)
    implementation(project(":chat-domain"))

    // REST API 기능
    implementation("org.springframework.boot:spring-boot-starter-web")

    // 입력 데이터 검증
    implementation("org.springframework.boot:spring-boot-starter-validation")

    // 페이징 지원
    implementation("org.springframework.data:spring-data-commons")

    implementation("org.springframework.boot:spring-boot-starter-actuator")
}