package org.dean.codex.core.tool;

import java.util.Map;

public interface ToolExecutor {

    Object execute(String toolName, Map<String, Object> arguments);
}
