package io.quarkiverse.ssf.receiver.runtime.event;

public interface SsfEventHandler {
    void handle(SsfEventContext eventContext);
}
