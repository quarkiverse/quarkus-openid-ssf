package io.quarkiverse.ssf.receiver.runtime.auth;

import java.util.Optional;

import jakarta.enterprise.context.ApplicationScoped;

import io.quarkus.arc.DefaultBean;

@ApplicationScoped
@DefaultBean
public class NoopTransmitterTokenProvider implements TransmitterTokenProvider {

    @Override
    public Optional<String> accessToken() {
        return Optional.empty();
    }
}
