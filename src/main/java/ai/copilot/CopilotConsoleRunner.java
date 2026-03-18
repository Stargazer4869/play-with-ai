package ai.copilot;

import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.util.Scanner;

@Component
public class CopilotConsoleRunner implements CommandLineRunner {
    private final CopilotAgentRegistry copilotAgentRegistry;
    private CopilotAgentHandler activeAgent;

    @Autowired
    public CopilotConsoleRunner(CopilotAgentRegistry copilotAgentRegistry) {
        this.copilotAgentRegistry = copilotAgentRegistry;
        this.activeAgent = copilotAgentRegistry.defaultAgent();
    }

    @Override
    public void run(String @NonNull ... args) {
        try (Scanner scanner = new Scanner(System.in)) {
            System.out.printf("Copilot Agent Console. Current mode: %s%n", activeAgent.mode());
            System.out.println("Use ':agents' to list modes or ':agent <mode>' to switch.");
            while (true) {
                System.out.print("> ");
                if (!scanner.hasNextLine()) {
                    System.out.println("\nInput closed. Shutting down.");
                    return;
                }

                String input = scanner.nextLine().trim();
                if (input.equalsIgnoreCase("exit") || input.equalsIgnoreCase("quit")) {
                    System.out.println("Bye.");
                    return;
                }
                if (input.isEmpty()) {
                    continue;
                }
                if (handleConsoleCommand(input)) {
                    continue;
                }

                String response = activeAgent.handleUserInput(input);
                System.out.println(response);
            }
        }
    }

    private boolean handleConsoleCommand(String input) {
        if (input.equalsIgnoreCase(":agents")) {
            System.out.println("Available modes: " + String.join(", ", copilotAgentRegistry.availableModes()));
            return true;
        }
        if (input.startsWith(":agent")) {
            String requestedMode = input.substring(6).trim();
            if (requestedMode.isEmpty()) {
                System.out.println("Usage: :agent <mode>");
                return true;
            }
            activeAgent = copilotAgentRegistry.resolve(requestedMode);
            System.out.println("Switched to agent mode: " + activeAgent.mode());
            return true;
        }
        return false;
    }
}
