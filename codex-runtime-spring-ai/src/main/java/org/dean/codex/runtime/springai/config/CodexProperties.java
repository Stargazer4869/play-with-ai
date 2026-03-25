package org.dean.codex.runtime.springai.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "codex")
public class CodexProperties {

    private String workspaceRoot = "";
    private String storageRoot = "";
    private final Agent agent = new Agent();
    private final Shell shell = new Shell();

    public String getWorkspaceRoot() {
        return workspaceRoot;
    }

    public void setWorkspaceRoot(String workspaceRoot) {
        this.workspaceRoot = workspaceRoot;
    }

    public String getStorageRoot() {
        return storageRoot;
    }

    public void setStorageRoot(String storageRoot) {
        this.storageRoot = storageRoot;
    }

    public Agent getAgent() {
        return agent;
    }

    public Shell getShell() {
        return shell;
    }

    public static class Agent {
        private int maxSteps = 10;
        private int maxActionsPerTurn = 3;
        private int historyWindow = 8;

        public int getMaxSteps() {
            return maxSteps;
        }

        public void setMaxSteps(int maxSteps) {
            this.maxSteps = maxSteps;
        }

        public int getMaxActionsPerTurn() {
            return maxActionsPerTurn;
        }

        public void setMaxActionsPerTurn(int maxActionsPerTurn) {
            this.maxActionsPerTurn = maxActionsPerTurn;
        }

        public int getHistoryWindow() {
            return historyWindow;
        }

        public void setHistoryWindow(int historyWindow) {
            this.historyWindow = historyWindow;
        }
    }

    public static class Shell {
        private String approvalMode = "review-sensitive";
        private int timeoutSeconds = 60;

        public String getApprovalMode() {
            return approvalMode;
        }

        public void setApprovalMode(String approvalMode) {
            this.approvalMode = approvalMode;
        }

        public int getTimeoutSeconds() {
            return timeoutSeconds;
        }

        public void setTimeoutSeconds(int timeoutSeconds) {
            this.timeoutSeconds = timeoutSeconds;
        }
    }
}
