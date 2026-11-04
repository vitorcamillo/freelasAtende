package com.freelasatende;

import com.freelasatende.config.EnvFileLoader;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import java.nio.file.Path;

@SpringBootApplication
public class FreelasAtendeApplication {

    public static void main(String[] args) {
        EnvFileLoader.loadIfPresent(Path.of(".env"));
        SpringApplication.run(FreelasAtendeApplication.class, args);
    }
}
