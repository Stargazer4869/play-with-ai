package ai.copilot.tools;

public interface FileReaderTool {

    FileReadResult readFile(String path);

    record FileReadResult(boolean success,
                          String path,
                          String content,
                          boolean truncated,
                          int totalCharacters,
                          String error) {
    }
}
