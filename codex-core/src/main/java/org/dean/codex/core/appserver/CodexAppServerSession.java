package org.dean.codex.core.appserver;

import org.dean.codex.protocol.appserver.AppServerNotification;
import org.dean.codex.protocol.appserver.InitializedNotification;
import org.dean.codex.protocol.appserver.InitializeParams;
import org.dean.codex.protocol.appserver.InitializeResponse;
import org.dean.codex.protocol.appserver.SkillsListParams;
import org.dean.codex.protocol.appserver.SkillsListResponse;
import org.dean.codex.protocol.appserver.ThreadCompactStartParams;
import org.dean.codex.protocol.appserver.ThreadCompactStartResponse;
import org.dean.codex.protocol.appserver.ThreadListResponse;
import org.dean.codex.protocol.appserver.ThreadReadParams;
import org.dean.codex.protocol.appserver.ThreadReadResponse;
import org.dean.codex.protocol.appserver.ThreadResumeParams;
import org.dean.codex.protocol.appserver.ThreadResumeResponse;
import org.dean.codex.protocol.appserver.ThreadStartParams;
import org.dean.codex.protocol.appserver.ThreadStartResponse;
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

    ThreadListResponse threadList();

    ThreadReadResponse threadRead(ThreadReadParams params);

    ThreadCompactStartResponse threadCompactStart(ThreadCompactStartParams params);

    TurnStartResponse turnStart(TurnStartParams params);

    TurnResumeResponse turnResume(TurnResumeParams params);

    TurnInterruptResponse turnInterrupt(TurnInterruptParams params);

    TurnSteerResponse turnSteer(TurnSteerParams params);

    SkillsListResponse skillsList(SkillsListParams params);

    AutoCloseable subscribe(Consumer<AppServerNotification> listener);
}
