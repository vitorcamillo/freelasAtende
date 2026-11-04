package com.freelasatende.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Configuração principal da aplicação.
 */
@Configuration
@EnableConfigurationProperties({
    TelegramConfig.class,
    FreelasConfig.class,
    UpworkConfig.class,
    FreelancerConfig.class,
    FreelancerBrConfig.class
})
public class AppConfig {
}
