package com.lore.zzal.pet.persona;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/** 원문 전체를 JSON으로 저장한다. 문자열 절단·AI 재해석은 하지 않는다. */
@Converter
public class AuthoredPersonaConverter implements AttributeConverter<AuthoredPersona, String> {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Override
    public String convertToDatabaseColumn(AuthoredPersona value) {
        if (value == null) return null;
        try { return JSON.writeValueAsString(value); }
        catch (JsonProcessingException e) { throw new IllegalArgumentException("캐릭터 설정을 저장할 수 없어요", e); }
    }

    @Override
    public AuthoredPersona convertToEntityAttribute(String value) {
        if (value == null) return null;
        try { return JSON.readValue(value, AuthoredPersona.class); }
        catch (JsonProcessingException e) { throw new IllegalArgumentException("캐릭터 설정을 읽을 수 없어요", e); }
    }
}
