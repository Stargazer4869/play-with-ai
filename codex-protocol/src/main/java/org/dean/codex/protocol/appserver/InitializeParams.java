package org.dean.codex.protocol.appserver;

public record InitializeParams(AppServerClientInfo clientInfo,
                               AppServerCapabilities capabilities) {
}
