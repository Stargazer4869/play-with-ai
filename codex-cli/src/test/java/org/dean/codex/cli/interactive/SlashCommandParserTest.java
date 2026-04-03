package org.dean.codex.cli.interactive;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertIterableEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class SlashCommandParserTest {

    @Test
    void defaultRegistryPreservesCommandOrderAndMetadata() {
        SlashCommandRegistry registry = SlashCommandRegistry.defaultRegistry();

        assertIterableEquals(
                List.of("help", "new", "threads", "resume", "fork", "archive", "unarchive", "rollback", "subagents", "agent", "skills", "history", "compact", "approvals", "approve", "reject", "interrupt", "steer"),
                registry.commands().stream().map(SlashCommandSpec::name).toList());

        SlashCommandSpec resume = registry.find("resume").orElseThrow();
        assertEquals("/resume <thread-id-prefix>", resume.syntax());
        assertEquals("Switch to a thread", resume.description());
        assertTrue(resume.supportsInlineArgs());
        assertFalse(resume.availableDuringTask());
        assertTrue(registry.find("/use").isPresent());

        SlashCommandSpec threads = registry.find("threads").orElseThrow();
        assertEquals("/threads [all|loaded|archived]", threads.syntax());
        assertEquals("/threads", threads.slashForms().get(0));
        assertTrue(registry.find("/threads").isPresent());
    }

    @Test
    void parserRecognizesSlashCommands() {
        SlashCommandParser parser = new SlashCommandParser(SlashCommandRegistry.defaultRegistry());

        SlashCommandParseResult slash = parser.parse("/resume thread-123 extra flags");
        assertTrue(slash.isCommand());
        assertEquals("resume", slash.invocation().orElseThrow().command().name());
        assertEquals(SlashCommandPrefix.SLASH, slash.invocation().orElseThrow().prefix());
        assertEquals("thread-123 extra flags", slash.invocation().orElseThrow().arguments());
    }

    @Test
    void parserKeepsNonCommandsAndUnknownCommandsDistinct() {
        SlashCommandParser parser = new SlashCommandParser(SlashCommandRegistry.defaultRegistry());

        SlashCommandParseResult nonCommand = parser.parse("plain text that should stay as chat");
        assertTrue(nonCommand.isNonCommand());

        SlashCommandParseResult colonPrefixedText = parser.parse(":resume thread-123 extra flags");
        assertTrue(colonPrefixedText.isNonCommand());

        SlashCommandParseResult unknown = parser.parse("/does-not-exist one two");
        assertTrue(unknown.isUnknownCommand());
        assertEquals("does-not-exist", unknown.commandToken().orElseThrow());
        assertEquals("one two", unknown.arguments());
    }

    @Test
    void registrySupportsGenericAliases() {
        SlashCommandRegistry registry = SlashCommandRegistry.builder()
                .add(new SlashCommandSpec("review", "/review [ARGS...]", "Start a review", List.of("inspect"), true, false))
                .build();
        SlashCommandParser parser = new SlashCommandParser(registry);

        SlashCommandParseResult slashAlias = parser.parse("/inspect current diff");
        assertTrue(slashAlias.isCommand());
        assertEquals("review", slashAlias.invocation().orElseThrow().command().name());
        assertEquals("current diff", slashAlias.invocation().orElseThrow().arguments());
    }

    @Test
    void blankInputIsReportedAsEmpty() {
        SlashCommandParser parser = new SlashCommandParser(SlashCommandRegistry.defaultRegistry());

        assertTrue(parser.parse(null).isEmpty());
        assertTrue(parser.parse("   ").isEmpty());
    }
}
