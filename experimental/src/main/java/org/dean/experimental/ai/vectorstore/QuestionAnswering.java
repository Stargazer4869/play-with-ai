package org.dean.experimental.ai.vectorstore;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.document.DefaultContentFormatter;
import org.springframework.ai.model.transformer.KeywordMetadataEnricher;
import org.springframework.ai.model.transformer.SummaryMetadataEnricher;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.repository.configuration.EnableRedisRepositories;

import java.util.List;

@SpringBootApplication
@EnableRedisRepositories
//@EnableAspectJAutoProxy(proxyTargetClass = true)
public class QuestionAnswering {

    public static void main(String[] args) {
        SpringApplication.run(QuestionAnswering.class, args);
    }


    @Bean
    DefaultContentFormatter defaultContentFormatter() {
        return DefaultContentFormatter.builder()
                .withExcludedEmbedMetadataKeys("NewEmbedKey")
                .withExcludedInferenceMetadataKeys("NewInferenceKey")
                .build();
    }

    @Bean
    KeywordMetadataEnricher keywordMetadataEnricher(ChatModel chatModel) {
        return new KeywordMetadataEnricher(chatModel, 3);
    }

    @Bean
    SummaryMetadataEnricher summaryMetadataEnricher(ChatModel chatModel) {
        return new SummaryMetadataEnricher(chatModel, List.of(
                SummaryMetadataEnricher.SummaryType.PREVIOUS,
                SummaryMetadataEnricher.SummaryType.CURRENT,
                SummaryMetadataEnricher.SummaryType.NEXT));
    }
}