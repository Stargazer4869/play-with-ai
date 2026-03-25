package org.dean.codex.protocol.tool;

import java.util.List;

public record FileSearchResult(boolean success,
                               String query,
                               String scope,
                               List<SearchMatch> matches,
                               int totalMatches,
                               boolean truncated,
                               String error) {
}
