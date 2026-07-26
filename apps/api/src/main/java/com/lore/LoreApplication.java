package com.lore;

/**
 * API 서버 진입점 (뼈대).
 *
 * 도메인 폴더(common / story / comic / trailer)의 소스는 루트 build.gradle 의 sourceSets 로
 * 하나로 묶여 컴파일되고, 이 클래스가 그걸 서버 하나로 띄운다.
 * 이 클래스 외에 여기에 비즈니스 로직을 추가하지 않는다 — 로직은 각 도메인의 be/ 안에.
 *
 * spring-boot 의존성이 아직 없어서 애노테이션은 주석으로만 남겨둠.
 *
 * <pre>
 * &#64;SpringBootApplication(scanBasePackages = "com.lore")
 * public class LoreApplication {
 *     public static void main(String[] args) {
 *         SpringApplication.run(LoreApplication.class, args);
 *     }
 * }
 * </pre>
 */
public class LoreApplication {

    public static void main(String[] args) {
        // Spring Boot 세팅 후 SpringApplication.run(...) 으로 교체.
    }
}
