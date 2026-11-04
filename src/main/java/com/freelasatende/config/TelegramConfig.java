package com.freelasatende.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Configurações do Telegram.
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "telegram")
public class TelegramConfig {

    private String botToken;
    private String chatId;
    private boolean simulate;
}
