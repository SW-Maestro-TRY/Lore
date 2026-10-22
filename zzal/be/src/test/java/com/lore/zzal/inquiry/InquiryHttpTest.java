package com.lore.zzal.inquiry;

import com.lore.common.auth.jwt.*;
import com.lore.common.config.*;
import com.lore.common.exception.*;
import org.junit.jupiter.api.*;
import org.springframework.context.annotation.*;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import tools.jackson.databind.json.JsonMapper;
import java.time.Instant;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class InquiryHttpTest {
    @Configuration @EnableWebMvc
    @Import({WebSecurityConfig.class, WebMvcConfig.class, LoginUserArgumentResolver.class,
            GlobalExceptionHandler.class, InquiryController.class})
    static class Config {
        @Bean InquiryService service() { return mock(InquiryService.class); }
        @Bean JwtAuthenticationFilter jwtFilter() { return new JwtAuthenticationFilter(mock(JwtProvider.class)); }
    }
    AnnotationConfigWebApplicationContext context; MockMvc mvc; InquiryService service;
    final String path = "/api/zzal/v1/me/inquiries";
    final JsonMapper json = JsonMapper.builder().build();
    @BeforeAll void start() {
        context = new AnnotationConfigWebApplicationContext(); context.setServletContext(new MockServletContext());
        context.register(Config.class); context.refresh(); service = context.getBean(InquiryService.class);
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }
    @AfterAll void close() { if(context != null) context.close(); }
    @BeforeEach void resetService() {
        reset(service); when(service.submit(any(), any(), any())).thenReturn(new InquiryController.Receipt(12, Instant.parse("2026-10-21T09:00:00Z")));
    }
    Map<String,Object> payload(String body) {
        return new LinkedHashMap<>(Map.of("requestId", UUID.randomUUID().toString(), "body", body, "locale", "ko"));
    }
    ResultActions send(Map<String,Object> body) throws Exception {
        return mvc.perform(post(path).with(authentication(new UsernamePasswordAuthenticationToken(71L, null, List.of())))
                .contentType("application/json").content(json.writeValueAsBytes(body)));
    }
    @Test void realSecurityRequiresLoginAndDoesNotExposeAnInquiryList() throws Exception {
        mvc.perform(post(path).contentType("application/json").content(json.writeValueAsBytes(payload("문의"))))
                .andExpect(status().isUnauthorized());
        mvc.perform(get(path).with(authentication(new UsernamePasswordAuthenticationToken(71L, null, List.of()))))
                .andExpect(status().isMethodNotAllowed());
        verifyNoInteractions(service);
    }
    @Test void httpValidationTrimsBeforeLengthChecksAndResponseContainsOnlyReceipt() throws Exception {
        var result = send(payload(" \n" + "가".repeat(4000) + "\n ")).andExpect(status().isOk()).andReturn();
        var data = json.readTree(result.getResponse().getContentAsString()).path("data");
        assertThat(data.properties()).hasSize(2); assertThat(data.path("id").asInt()).isEqualTo(12);
        verify(service).submit(eq(71L), argThat(request -> request.body().length()==4000 && request.petId()==null), any());
    }
    @Test void blankTooLongMissingUuidBadLocaleAndNonpositivePetAreRejectedBeforeStorage() throws Exception {
        for (String body : List.of(" \n\t ", "가".repeat(4001))) send(payload(body)).andExpect(status().isBadRequest());
        var missing=payload("문의");missing.remove("requestId");send(missing).andExpect(status().isBadRequest());
        var invalid=payload("문의");invalid.put("requestId","not-a-uuid");send(invalid).andExpect(status().isBadRequest());
        for(String locale : List.of("KO","xx","")){var body=payload("문의");body.put("locale",locale);send(body).andExpect(status().isBadRequest());}
        var missingLocale=payload("문의");missingLocale.remove("locale");send(missingLocale).andExpect(status().isBadRequest());
        var badPet=payload("문의");badPet.put("petId",0);send(badPet).andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }
    @Test void ownedPetAndIdempotencyFailuresUseTheExistingErrorEnvelope() throws Exception {
        when(service.submit(any(),any(),any())).thenThrow(new BusinessException(ErrorCode.ZZAL_INQUIRY_CONFLICT));
        var result=send(payload("문의")).andExpect(status().isConflict()).andReturn();
        assertThat(json.readTree(result.getResponse().getContentAsString()).path("error").path("code").asText())
                .isEqualTo("ZZAL_INQUIRY_CONFLICT");
        doThrow(new BusinessException(ErrorCode.ZZAL_PET_NOT_FOUND)).when(service).submit(any(),any(),any());
        var request=payload("문의");request.put("petId",9);send(request).andExpect(status().isNotFound());
    }
}
