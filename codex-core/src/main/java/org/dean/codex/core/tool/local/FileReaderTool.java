package org.dean.codex.core.tool.local;

import org.dean.codex.protocol.tool.FileReadResult;

public interface FileReaderTool {

    FileReadResult readFile(String path);
}
