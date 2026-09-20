package com.lore;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * API 서버 진입점.
 *
 * 도메인 폴더(common / webtoon / zzal / trailer)의 소스는 루트 build.gradle 의 sourceSets 로
 * 하나로 묶여 컴파일되고, 이 클래스가 그걸 서버 하나로 띄운다.
 * 이 클래스 외에 여기에 비즈니스 로직을 추가하지 않는다 — 로직은 각 도메인의 be/ 안에.
 *
 * scanBasePackages 를 명시한 이유:
 * 기본값도 이 클래스가 속한 com.lore 하위를 스캔하므로 생략해도 동작하지만,
 * 소스가 물리적으로 다른 폴더에 흩어져 있는 구조라 의도를 코드에 남겨둔다.
 */
@SpringBootApplication(scanBasePackages = "com.lore")
public class LoreApplication {

    public static void main(String[] args) {
        SpringApplication.run(LoreApplication.class, args);
    }
}
