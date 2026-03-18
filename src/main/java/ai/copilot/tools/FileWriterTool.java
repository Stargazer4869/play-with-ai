package ai.copilot.tools;

public interface FileWriterTool {

    FileWriteResult writeFile(String path, String content);

    record FileWriteResult(boolean success,
                           String path,
                           boolean created,
                           int charactersWritten,
                           String error) {
    }
}
