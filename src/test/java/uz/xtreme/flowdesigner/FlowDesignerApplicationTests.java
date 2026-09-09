package uz.xtreme.flowdesigner;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * The login provider is part of the context: an instance with none configured
 * refuses to start, so the test supplies one the way a deployment does.
 */
@SpringBootTest(properties = {
        "app.oauth2.provider=gitlab",
        "app.oauth2.gitlab.client-id=test-client-id",
        "app.oauth2.gitlab.client-secret=test-client-secret"
})
class FlowDesignerApplicationTests {

    @Test
    void contextLoads() {
    }
}
