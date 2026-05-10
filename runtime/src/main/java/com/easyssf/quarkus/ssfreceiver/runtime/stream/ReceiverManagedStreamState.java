/*
 * Copyright 2026 Thomas Darimont and the easyssf.com contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.easyssf.quarkus.ssfreceiver.runtime.stream;

import java.util.Optional;

import jakarta.enterprise.context.ApplicationScoped;

/**
 * Holds the {@code stream_id} the receiver-managed registrar discovered (or created)
 * on startup. {@link SsfStreamClient} consults this before falling back to the static
 * {@code ssf.receiver.stream-id} property, so receiver-managed apps don't have to know
 * their stream id ahead of time.
 *
 * <p>
 * Always present in the bean container regardless of {@code stream-management} mode;
 * it stays empty when the transmitter owns the stream.
 */
@ApplicationScoped
public class ReceiverManagedStreamState {

    private volatile String streamId;

    public Optional<String> streamId() {
        return Optional.ofNullable(streamId);
    }

    public void setStreamId(String streamId) {
        this.streamId = streamId;
    }

    public void clear() {
        this.streamId = null;
    }
}
