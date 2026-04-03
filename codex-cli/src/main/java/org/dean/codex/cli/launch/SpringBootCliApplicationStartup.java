package org.dean.codex.cli.launch;

import org.dean.codex.cli.CodexCliApplication;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.ConfigurableApplicationContext;

import java.util.Objects;

final class SpringBootCliApplicationStartup implements CliApplicationStartup {
    private final Class<?> applicationClass;

    SpringBootCliApplicationStartup(Class<?> applicationClass) {
        this.applicationClass = Objects.requireNonNull(applicationClass, "applicationClass");
    }

    @Override
    public void start(CliLaunchRequest request) {
        SpringApplication application = new SpringApplication(applicationClass);
        application.setWebApplicationType(WebApplicationType.NONE);
        try (ConfigurableApplicationContext ignored = application.run(request.toArray())) {
            // The CLI entrypoint only needs the application lifecycle side effects.
        }
    }

    static SpringBootCliApplicationStartup codexCliApplication() {
        return new SpringBootCliApplicationStartup(CodexCliApplication.class);
    }
}
