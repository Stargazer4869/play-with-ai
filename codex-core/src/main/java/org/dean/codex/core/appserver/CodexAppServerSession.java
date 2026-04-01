package org.dean.codex.core.appserver;

import org.dean.codex.protocol.appserver.AppServerNotification;
import org.dean.codex.protocol.appserver.InitializedNotification;
import org.dean.codex.protocol.appserver.InitializeParams;
import org.dean.codex.protocol.appserver.InitializeResponse;
import org.dean.codex.protocol.appserver.SkillsListParams;
import org.dean.codex.protocol.appserver.SkillsListResponse;
import org.dean.codex.protocol.appserver.ThreadArchiveParams;
import org.dean.codex.protocol.appserver.ThreadArchiveResponse;
import org.dean.codex.protocol.appserver.ThreadCompactStartParams;
import org.dean.codex.protocol.appserver.ThreadCompactStartResponse;
import org.dean.codex.protocol.appserver.ThreadForkParams;
import org.dean.codex.protocol.appserver.ThreadForkResponse;
import org.dean.codex.protocol.appserver.ThreadListParams;
import org.dean.codex.protocol.appserver.ThreadListResponse;
import org.dean.codex.protocol.appserver.ThreadLoadedListParams;
import org.dean.codex.protocol.appserver.ThreadLoadedListResponse;
import org.dean.codex.protocol.appserver.ThreadReadParams;
import org.dean.codex.protocol.appserver.ThreadReadResponse;
import org.dean.codex.protocol.appserver.ThreadRollbackParams;
import org.dean.codex.protocol.appserver.ThreadRollbackResponse;
import org.dean.codex.protocol.appserver.ThreadResumeParams;
import org.dean.codex.protocol.appserver.ThreadResumeResponse;
import org.dean.codex.protocol.appserver.ThreadStartParams;
import org.dean.codex.protocol.appserver.ThreadStartResponse;
import org.dean.codex.protocol.appserver.ThreadUnarchiveParams;
import org.dean.codex.protocol.appserver.ThreadUnarchiveResponse;
import org.dean.codex.protocol.appserver.TurnInterruptParams;
import org.dean.codex.protocol.appserver.TurnInterruptResponse;
import org.dean.codex.protocol.appserver.TurnResumeParams;
import org.dean.codex.protocol.appserver.TurnResumeResponse;
import org.dean.codex.protocol.appserver.TurnStartParams;
import org.dean.codex.protocol.appserver.TurnStartResponse;
import org.dean.codex.protocol.appserver.TurnSteerParams;
import org.dean.codex.protocol.appserver.TurnSteerResponse;

import java.util.function.Consumer;

public interface CodexAppServerSession extends AutoCloseable {

    InitializeResponse initialize(InitializeParams params);

    void initialized(InitializedNotification notification);

    ThreadStartResponse threadStart(ThreadStartParams params);

    ThreadResumeResponse threadResume(ThreadResumeParams params);

    default ThreadListResponse threadList() {
        return threadList(null);
    }

    ThreadListResponse threadList(ThreadListParams params);

    ThreadLoadedListResponse threadLoadedList(ThreadLoadedListParams params);

    ThreadReadResponse threadRead(ThreadReadParams params);

    ThreadForkResponse threadFork(ThreadForkParams params);

    ThreadArchiveResponse threadArchive(ThreadArchiveParams params);

    ThreadUnarchiveResponse threadUnarchive(ThreadUnarchiveParams params);

    ThreadRollbackResponse threadRollback(ThreadRollbackParams params);

    ThreadCompactStartResponse threadCompactStart(ThreadCompactStartParams params);

    TurnStartResponse turnStart(TurnStartParams params);

    TurnResumeResponse turnResume(TurnResumeParams params);

    TurnInterruptResponse turnInterrupt(TurnInterruptParams params);

    TurnSteerResponse turnSteer(TurnSteerParams params);

    SkillsListResponse skillsList(SkillsListParams params);

    AutoCloseable subscribe(Consumer<AppServerNotification> listener);
}
