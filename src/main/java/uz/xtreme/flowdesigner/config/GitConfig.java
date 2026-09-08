package uz.xtreme.flowdesigner.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Configuration class that enables the application's configuration properties and scheduling.
 */
@Configuration
@EnableConfigurationProperties({GitProperties.class, AuthProperties.class})
@EnableScheduling
public class GitConfig {
}
