package org.dean.codex.runtime.springai.config;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertTrue;

class CodexRuntimeDefaultsTest {

    @Test
    void chatPromptAndCompletionLoggingAreDisabledByDefaultWithEnvOverrides() throws Exception {
        String yaml = new String(new ClassPathResource("codex-runtime-defaults.yml").getInputStream().readAllBytes(),
                StandardCharsets.UTF_8);

        assertTrue(yaml.contains("log-prompt: ${CODEX_CHAT_LOG_PROMPT:false}"));
        assertTrue(yaml.contains("log-completion: ${CODEX_CHAT_LOG_COMPLETION:false}"));
    }
}
