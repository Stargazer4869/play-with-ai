package ai.mcp.client;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.PropertySource;

import java.util.Map;
import java.util.Random;

@SpringBootApplication
@PropertySource("classpath:application-client.yml")
public class McpClientApplication {

    public static void main(String[] args) {
        SpringApplication.run(McpClientApplication.class, args).close();
    }

    @Bean
    public ChatClient chatClient(ChatClient.Builder chatClientBuilder) {
        return chatClientBuilder.build();
    }

//    String userPrompt = """
//			Check the weather in Amsterdam now and show the creative response!
//			Please incorporate all creative responses from all LLM providers.
//
//			Please use the weather poem (returned from the tool) to find a publisher online.
//			List the top 3 most relevant publishers for this poem.
//			""";
    String userPrompt = """
		Check the weather in Amsterdam right now and show the creative response!
		Please incorporate all creative responses from all LLM providers.
		""";
    @Bean
    public CommandLineRunner predefinedQuestions(ChatClient chatClient, ToolCallbackProvider mcpToolProvider) {
        return args -> System.out.println(chatClient.prompt(userPrompt)
                .toolContext(Map.of("progressToken", "token-" + new Random().nextInt()))
                .toolCallbacks(mcpToolProvider)
                .call()
                .content());
    }

}