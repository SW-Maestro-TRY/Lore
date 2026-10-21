package com.lore.zzal.chat.line;

import com.lore.zzal.chat.prompt.SystemPromptCache;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.test.context.support.TestPropertySourceUtils;
import org.springframework.test.util.ReflectionTestUtils;
import java.time.Duration;
import java.util.concurrent.TimeoutException;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.mock;

class LineChainAttemptsTest {
    private LineChain chain(FakeChatLineClient client, int limit){return new LineChain(new LlmLineGenerator(client,"gpt-5.6-luna",Duration.ofSeconds(4),new SystemPromptCache()),null,limit);}
    @Test void failureWithOneAttemptNeverMakesSecondProviderCall(){
        var client=new FakeChatLineClient().fail(new TimeoutException()).line("unused","hello");
        var result=chain(client,1).generate(LlmLineGeneratorTest.reply(),1L);
        assertThat(client.systems).hasSize(1);assertThat(result.attempts()).isEqualTo(1);assertThat(result.failReason()).isEqualTo("timeout");assertThat(result.costUsd()).isPositive();
    }
    @Test void twoAttemptsAndLegacyConstructorKeepRetrySuccessAndCost(){
        for(boolean legacy:new boolean[]{false,true}){
            var client=new FakeChatLineClient().fail(new TimeoutException()).line("응.","hello");
            var gen=new LlmLineGenerator(client,"gpt-5.6-luna",Duration.ofSeconds(4),new SystemPromptCache());
            var result=(legacy?new LineChain(gen,null):new LineChain(gen,null,2)).generate(LlmLineGeneratorTest.reply(),1L);
            assertThat(client.systems).hasSize(2);assertThat(result.attempts()).isEqualTo(2);assertThat(result.outcome()).isEqualTo(LineOutcome.RETRIED_OK);
        }
    }
    @Test void persistentFailureStopsAtExactlyConfiguredLimit(){
        for(int cap:new int[]{1,2}){
            var client=new FakeChatLineClient().fail(new TimeoutException()).fail(new TimeoutException()).line("unused","hello");
            var result=chain(client,cap).generate(LlmLineGeneratorTest.reply(),1L);
            assertThat(client.systems).hasSize(cap);assertThat(result.attempts()).isEqualTo(cap);assertThat(result.outcome()).isEqualTo(LineOutcome.FAILED_CLOSED);
        }
    }
    @Test void successfulFirstAttemptDoesNotSpendRemainingAllowance(){
        for(int cap:new int[]{1,2}){
            var client=new FakeChatLineClient().line("응.","hello").line("unused","hello");
            var result=chain(client,cap).generate(LlmLineGeneratorTest.reply(),1L);
            assertThat(client.systems).hasSize(1);assertThat(result.attempts()).isEqualTo(1);assertThat(result.outcome()).isEqualTo(LineOutcome.OK);
        }
    }
    @Test void stageGenerateOnceStaysOneEvenWhenNormalLimitIsTwo(){
        for(int cap:new int[]{1,2}){
            var client=new FakeChatLineClient().fail(new TimeoutException()).line("unused","hello");
            var result=chain(client,cap).generateOnce(LlmLineGeneratorTest.reply(),1L);
            assertThat(client.systems).hasSize(1);assertThat(result.attempts()).isEqualTo(1);
        }
    }
    @Test void fiveOrdinaryGenerationsHaveExplicitFiveOrTenProviderCeiling(){
        var old=LlmLineGeneratorTest.reply();
        var plan=new com.lore.zzal.chat.session.TurnPlan(com.lore.zzal.chat.session.TurnType.CONTINUE,2,false,null,false,com.lore.zzal.chat.ChatSlot.CONTINUOUS);
        var ordinary=new com.lore.zzal.chat.prompt.ChatContext(old.petId(),old.sheet(),old.state(),old.kind(),plan,old.answering(),old.history(),old.motions());
        assertThat(ordinary.ordinaryChat()).isTrue();
        for(int cap:new int[]{1,2}){
            var client=new FakeChatLineClient().whenEmpty("malformed JSON");var chain=chain(client,cap);
            for(int turn=0;turn<5;turn++){var result=chain.generate(ordinary,1L);assertThat(result.attempts()).isEqualTo(cap);assertThat(result.outcome()).isEqualTo(LineOutcome.FAILED_REPLY);}
            assertThat(client.systems).hasSize(5*cap);
        }
    }
    @Test void invalidLimitsRejectBeforeAnyProviderCall(){
        var client=new FakeChatLineClient().line("unused","hello");
        for(int cap:new int[]{-1,0,3})assertThatThrownBy(()->chain(client,cap)).hasMessageContaining("max-attempts must be 1 or 2");
        assertThat(client.systems).isEmpty();
    }
    @Test void legacyBabyHonorsPerRequestLimitWithoutChangingThreeFailureNeutralThreshold(){
        var client=new FakeChatLineClient().fail(new TimeoutException()).fail(new TimeoutException()).fail(new TimeoutException());var chain=chain(client,1);
        var first=chain.firstBaby(LlmLineGeneratorTest.cont(com.lore.zzal.chat.session.TurnType.FIRST_MEET,1),1L,0);
        var second=chain.firstBaby(LlmLineGeneratorTest.cont(com.lore.zzal.chat.session.TurnType.FIRST_MEET,1),1L,1);
        var third=chain.firstBaby(LlmLineGeneratorTest.cont(com.lore.zzal.chat.session.TurnType.FIRST_MEET,1),1L,2);
        assertThat(first.outcome()).isEqualTo(LineOutcome.RETRY_WAIT);assertThat(second.outcome()).isEqualTo(LineOutcome.RETRY_WAIT);assertThat(third.outcome()).isEqualTo(LineOutcome.BABY_NEUTRAL);assertThat(client.systems).hasSize(3);assertThat(first.attempts()).isEqualTo(1);assertThat(second.attempts()).isEqualTo(1);assertThat(third.attempts()).isEqualTo(1);
    }
    @Test void configurationBindsOneOrTwoAndUnsetKeepsProductionDefaultTwo(){
        for(Integer cap:new Integer[]{null,1,2})try(var ctx=new AnnotationConfigApplicationContext()){
            TestPropertySourceUtils.addInlinedPropertiesToEnvironment(ctx,"app.zzal.chat.llm=true","app.zzal.openai.api-key=SYNTHETIC_NOT_A_KEY","app.lore.env=local","app.zzal.chat.max-attempts=${ZZAL_CHAT_MAX_ATTEMPTS:2}");
            if(cap!=null)TestPropertySourceUtils.addInlinedPropertiesToEnvironment(ctx,"ZZAL_CHAT_MAX_ATTEMPTS="+cap);
            ctx.registerBean(SystemPromptCache.class,SystemPromptCache::new);ctx.registerBean(ChatLineEvents.class,()->mock(ChatLineEvents.class));ctx.register(ChatLineConfig.class);ctx.refresh();
            assertThat(ReflectionTestUtils.getField(ctx.getBean(LineChain.class),"maxAttempts")).isEqualTo(cap==null?2:cap);
        }
    }
    @Test void invalidConfigurationRejectsEvenWhenLlmIsOff(){
        var config=new ChatLineConfig();ReflectionTestUtils.setField(config,"maxAttempts",3);
        assertThatThrownBy(()->config.lineChain(false,"gpt-5.6-luna",4000,"none","",new SystemPromptCache(),null,"local")).hasMessageContaining("max-attempts");
    }
}
