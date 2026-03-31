package org.dean.codex.runtime.springai.appserver;

import org.dean.codex.core.appserver.CodexAppServer;
import org.dean.codex.runtime.springai.appserver.transport.jsonrpc.StdioJsonRpcAppServerLauncher;
import org.springframework.boot.Banner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;

@SpringBootApplication(scanBasePackages = "org.dean.codex")
public class CodexAppServerStdioApplication {

    public static void main(String[] args) {
        System.setProperty("org.springframework.boot.logging.LoggingSystem", "none");

        SpringApplication application = new SpringApplication(CodexAppServerStdioApplication.class);
        application.setBannerMode(Banner.Mode.OFF);
        application.setLogStartupInfo(false);
        application.setWebApplicationType(WebApplicationType.NONE);

        ConfigurableApplicationContext context = application.run(args);
        try {
            CodexAppServer appServer = context.getBean(CodexAppServer.class);
            new StdioJsonRpcAppServerLauncher(appServer).run(System.in, System.out);
        }
        catch (Exception exception) {
            throw new IllegalStateException("Failed to run stdio app-server host.", exception);
        }
        finally {
            context.close();
        }
    }
}
