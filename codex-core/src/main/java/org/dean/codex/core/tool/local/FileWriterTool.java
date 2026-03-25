package org.dean.codex.core.tool.local;

import org.dean.codex.protocol.tool.FileWriteResult;

public interface FileWriterTool {

    FileWriteResult writeFile(String path, String content);
}
