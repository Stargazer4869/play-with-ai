package org.dean.codex.cli.appserver;

import org.dean.codex.cli.appserver.transport.jsonrpc.StdioProcessCodexAppServer;
import org.dean.codex.core.appserver.CodexAppServer;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

@Configuration
@EnableConfigurationProperties(CodexCliAppServerProperties.class)
public class CodexCliAppServerConfig {

    @Bean
    @Primary
    public CodexAppServer codexAppServer(CodexCliAppServerProperties properties) {
        return new StdioProcessCodexAppServer(properties);
    }
}
