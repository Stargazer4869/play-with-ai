package org.dean.codex.runtime.springai.config;

import org.dean.codex.core.approval.CommandApprovalService;
import org.dean.codex.core.approval.CommandApprovalStore;
import org.dean.codex.core.conversation.ConversationStore;
import org.dean.codex.core.tool.local.CommandApprovalPolicy;
import org.dean.codex.core.tool.local.ShellCommandTool;
import org.dean.codex.runtime.springai.approval.DefaultCommandApprovalService;
import org.dean.codex.runtime.springai.approval.FileSystemCommandApprovalStore;
import org.dean.codex.runtime.springai.conversation.FileSystemConversationStore;
import org.dean.codex.tools.local.PatternCommandApprovalPolicy;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;
import java.time.Duration;

@Configuration
@EnableConfigurationProperties(CodexProperties.class)
public class CodexRuntimeSpringAiConfig {

    @Bean("codexWorkspaceRoot")
    public Path codexWorkspaceRoot(CodexProperties properties) {
        String configuredRoot = properties.getWorkspaceRoot();
        String resolvedRoot = configuredRoot == null || configuredRoot.isBlank()
                ? System.getProperty("user.dir")
                : configuredRoot;
        return Path.of(resolvedRoot).toAbsolutePath().normalize();
    }

    @Bean("codexStorageRoot")
    public Path codexStorageRoot(CodexProperties properties) {
        String configuredRoot = properties.getStorageRoot();
        String resolvedRoot = configuredRoot == null || configuredRoot.isBlank()
                ? Path.of(System.getProperty("user.home"), ".codex-java").toString()
                : configuredRoot;
        return Path.of(resolvedRoot).toAbsolutePath().normalize();
    }

    @Bean
    public ConversationStore conversationStore(@org.springframework.beans.factory.annotation.Qualifier("codexStorageRoot") Path storageRoot) {
        return new FileSystemConversationStore(storageRoot);
    }

    @Bean
    public CommandApprovalStore commandApprovalStore(@org.springframework.beans.factory.annotation.Qualifier("codexStorageRoot") Path storageRoot) {
        return new FileSystemCommandApprovalStore(storageRoot);
    }

    @Bean
    public CommandApprovalService commandApprovalService(CommandApprovalStore commandApprovalStore,
                                                         ConversationStore conversationStore,
                                                         ShellCommandTool shellCommandTool) {
        return new DefaultCommandApprovalService(commandApprovalStore, conversationStore, shellCommandTool);
    }

    @Bean
    public CommandApprovalPolicy commandApprovalPolicy(CodexProperties properties) {
        return new PatternCommandApprovalPolicy(
                PatternCommandApprovalPolicy.Mode.from(properties.getShell().getApprovalMode()));
    }

    @Bean("codexCommandTimeout")
    public Duration codexCommandTimeout(CodexProperties properties) {
        return Duration.ofSeconds(Math.max(1, properties.getShell().getTimeoutSeconds()));
    }
}
