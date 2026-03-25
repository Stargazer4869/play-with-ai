package org.dean.codex.cli;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = "org.dean.codex")
public class CodexCliApplication {
    public static void main(String[] args) {
        SpringApplication application = new SpringApplication(CodexCliApplication.class);
        application.setWebApplicationType(WebApplicationType.NONE);
        application.run(args).close();
    }
}
