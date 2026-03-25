package org.dean.experimental.ai.tools;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.tool.function.FunctionToolCallback;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.function.Function;

/**
 * Chat examples using the high-level ChatClient API.
 */
@RestController
class ChatController {

    private static final Logger logger = LoggerFactory.getLogger(ChatController.class);

    private final ChatClient chatClient;
    private final Tools tools;

    ChatController(ChatClient.Builder chatClientBuilder, Tools tools) {
        this.chatClient = chatClientBuilder.clone()
                .defaultAdvisors(new SimpleLoggerAdvisor())
                .build();
        this.tools = tools;
    }

    // METHODS
    @PostMapping("/chat/method/raw")
    String chatMethodRaw(@RequestBody String userPrompt) {
        return chatClient.prompt()
                .user(userPrompt)
                .tools(tools)
                .call()
                .content();
    }

    @GetMapping("/chat/method/no-args")
    String chatMethodNoArgs() {
        return chatClient.prompt()
                .user("Welcome the user to the library")
                .tools(tools)
                .call()
                .content();
    }

    @GetMapping("/chat/method/no-args-stream")
    Flux<String> chatMethodNoArgsStream() {
        return chatClient.prompt()
                .user("Welcome the user to the library")
                .tools(tools)
                .stream()
                .content();
    }

    @GetMapping("/chat/method/void")
    String chatMethodVoid(String user) {
        var userPromptTemplate = "Welcome {user} to the library";
        return chatClient.prompt()
                .user(userSpec -> userSpec
                        .text(userPromptTemplate)
                        .param("user", user)
                )
                .tools(tools)
                .call()
                .content();
    }

    @GetMapping("/chat/method/single")
    String chatMethodSingle(String authorName) {
        var userPromptTemplate = "What books written by {author} are available in the library?";
        return chatClient.prompt()
                .user(userSpec -> userSpec
                        .text(userPromptTemplate)
                        .param("author", authorName)
                )
                .tools(tools)
                .call()
                .content();
    }

    @GetMapping("/chat/method/list")
    String chatMethodList(String bookTitle1, String bookTitle2) {
        var userPromptTemplate = "What authors wrote the books {bookTitle1} and {bookTitle2} available in the library?";
        return chatClient.prompt()
                .user(userSpec -> userSpec
                        .text(userPromptTemplate)
                        .param("bookTitle1", bookTitle1)
                        .param("bookTitle2", bookTitle2)
                )
                .tools(tools)
                .call()
                .content();
    }
}