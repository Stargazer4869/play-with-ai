package org.dean.codex.protocol.appserver;

public record InitializeResponse(String userAgent,
                                 String codexHome,
                                 String platformFamily,
                                 String platformOs) {
}
