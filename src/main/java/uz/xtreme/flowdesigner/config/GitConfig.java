package uz.xtreme.flowdesigner.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Configuration class that enables Git-related configuration properties and scheduling.
 */
@Configuration
@EnableConfigurationProperties(GitProperties.class)
@EnableScheduling
public class GitConfig {
}
