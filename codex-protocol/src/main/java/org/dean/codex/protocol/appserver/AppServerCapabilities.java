package org.dean.codex.protocol.appserver;

import java.util.List;

public record AppServerCapabilities(boolean experimentalApi,
                                    List<String> optOutNotificationMethods) {

    public AppServerCapabilities {
        optOutNotificationMethods = optOutNotificationMethods == null ? List.of() : List.copyOf(optOutNotificationMethods);
    }
}
