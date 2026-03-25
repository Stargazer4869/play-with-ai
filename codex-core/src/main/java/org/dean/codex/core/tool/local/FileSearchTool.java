package org.dean.codex.core.tool.local;

import org.dean.codex.protocol.tool.FileSearchResult;

public interface FileSearchTool {

    FileSearchResult search(String query, String scope);
}
