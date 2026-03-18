package ai.copilot;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class CopilotConsoleApplication {
    public static void main(String[] args) {
        SpringApplication application = new SpringApplication(CopilotConsoleApplication.class);
        application.setWebApplicationType(WebApplicationType.NONE);
        application.run(args).close();
    }
}
