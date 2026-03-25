package org.dean.codex.core.conversation;

import org.dean.codex.protocol.conversation.ConversationMessage;
import org.dean.codex.protocol.conversation.ConversationTurn;
import org.dean.codex.protocol.conversation.ThreadId;
import org.dean.codex.protocol.conversation.ThreadSummary;
import org.dean.codex.protocol.conversation.TurnId;
import org.dean.codex.protocol.conversation.TurnStatus;
import org.dean.codex.protocol.event.TurnEvent;

import java.time.Instant;
import java.util.List;
import java.util.stream.Stream;

public interface ConversationStore {

    ThreadId createThread(String title);

    boolean exists(ThreadId threadId);

    TurnId startTurn(ThreadId threadId, String userInput, Instant startedAt);

    void appendTurnEvents(ThreadId threadId, TurnId turnId, List<TurnEvent> events);

    void updateTurnStatus(ThreadId threadId, TurnId turnId, TurnStatus status, Instant updatedAt);

    void completeTurn(ThreadId threadId, TurnId turnId, TurnStatus status, String finalAnswer, Instant completedAt);

    ConversationTurn turn(ThreadId threadId, TurnId turnId);

    List<ConversationTurn> turns(ThreadId threadId);

    List<ThreadSummary> listThreads();

    default List<ConversationMessage> messages(ThreadId threadId) {
        return turns(threadId).stream()
                .flatMap(turn -> {
                    ConversationMessage userMessage = new ConversationMessage(
                            turn.turnId(),
                            org.dean.codex.protocol.conversation.MessageRole.USER,
                            turn.userInput(),
                            turn.startedAt());
                    if (turn.finalAnswer() == null || turn.finalAnswer().isBlank()) {
                        return Stream.of(userMessage);
                    }
                    ConversationMessage assistantMessage = new ConversationMessage(
                            turn.turnId(),
                            org.dean.codex.protocol.conversation.MessageRole.ASSISTANT,
                            turn.finalAnswer(),
                            turn.completedAt() == null ? turn.startedAt() : turn.completedAt());
                    return Stream.of(userMessage, assistantMessage);
                })
                .toList();
    }
}
