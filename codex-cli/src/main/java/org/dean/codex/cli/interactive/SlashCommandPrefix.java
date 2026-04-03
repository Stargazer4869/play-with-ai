package org.dean.codex.cli.interactive;

public enum SlashCommandPrefix {
    SLASH('/');

    private final char prefixChar;

    SlashCommandPrefix(char prefixChar) {
        this.prefixChar = prefixChar;
    }

    public char prefixChar() {
        return prefixChar;
    }
}
