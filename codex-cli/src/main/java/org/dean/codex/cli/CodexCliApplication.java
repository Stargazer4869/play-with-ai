package org.dean.codex.cli;

import org.dean.codex.runtime.springai.appserver.CodexAppServerStdioApplication;
import org.dean.codex.cli.launch.CliApplicationBootstrap;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = "org.dean.codex")
public class CodexCliApplication {
    public static void main(String[] args) {
        if (shouldStartAppServer(args)) {
            CodexAppServerStdioApplication.main(args);
            return;
        }
        CliApplicationBootstrap.launch(args);
    }

    private static boolean shouldStartAppServer(String[] args) {
        if (args == null || args.length == 0) {
            return false;
        }
        for (String arg : args) {
            if ("--codex.cli.enabled=false".equals(arg)) {
                return true;
            }
        }
        return false;
    }
}
