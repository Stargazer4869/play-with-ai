package org.dean.codex.core.tool.local;

import org.dean.codex.protocol.tool.FilePatchResult;

public interface FilePatchTool {

    FilePatchResult applyPatch(String path, String oldText, String newText, boolean replaceAll);
}
