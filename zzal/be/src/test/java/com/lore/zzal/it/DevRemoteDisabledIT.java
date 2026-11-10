package com.lore.zzal.it;

import com.lore.zzal.dev.DevService;
import com.lore.zzal.dev.DevStateController;
import com.lore.zzal.dev.DevBakeService;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;
import static org.assertj.core.api.Assertions.assertThat;

@ZzalIntegrationTest
@TestPropertySource(properties="app.zzal.dev-tools=false")
class DevRemoteDisabledIT extends ZzalItSupport {
    @Test void disabledConfigurationHasNoRemoteBeansOrRoutes()throws Exception{
        assertThat(context.getBeansOfType(DevService.class)).isEmpty();
        assertThat(context.getBeansOfType(DevStateController.class)).isEmpty();
        assertThat(context.getBeansOfType(DevBakeService.class)).isEmpty();
        Long user=newUserId();
        assertThat(postAs(user,"/api/zzal/v1/dev/pets/1/events/tutorial-restart",null).getResponse().getStatus()).isEqualTo(404);
    }
}
