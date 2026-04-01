package org.dean.codex.protocol.appserver;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record ThreadListParams(String cursor,
                               Integer limit,
                               ThreadSortKey sortKey,
                               List<String> modelProviders,
                               List<ThreadSourceKind> sourceKinds,
                               Boolean archived,
                               String cwd,
                               String searchTerm) {

    public ThreadListParams {
        modelProviders = modelProviders == null ? null : List.copyOf(modelProviders);
        sourceKinds = sourceKinds == null ? null : List.copyOf(sourceKinds);
    }
}
