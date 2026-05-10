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
package com.easyssf.quarkus.ssfreceiver.runtime.metadata;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.net.URI;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Pure unit tests for {@link SsfConfigurationResolver#deriveMetadataUri(URI)}
 * — the SSF §7.2 path-splice rule. No Quarkus boot.
 */
class SsfConfigurationResolverTest {

    @Test
    @DisplayName("Issuer with no path → /.well-known/ssf-configuration")
    void issuerWithoutPath() {
        URI issuer = URI.create("https://tr.example.com");
        assertThat(SsfConfigurationResolver.deriveMetadataUri(issuer),
                equalTo(URI.create("https://tr.example.com/.well-known/ssf-configuration")));
    }

    @Test
    @DisplayName("Issuer with explicit empty path → same as no path")
    void issuerWithRootPath() {
        URI issuer = URI.create("https://tr.example.com/");
        assertThat(SsfConfigurationResolver.deriveMetadataUri(issuer),
                equalTo(URI.create("https://tr.example.com/.well-known/ssf-configuration")));
    }

    @Test
    @DisplayName("Issuer with single-segment path: spec example verbatim")
    void issuerWithSingleSegmentPath() {
        // From SSF spec §7.2 figure 17.
        URI issuer = URI.create("https://tr.example.com/issuer1");
        assertThat(SsfConfigurationResolver.deriveMetadataUri(issuer),
                equalTo(URI.create("https://tr.example.com/.well-known/ssf-configuration/issuer1")));
    }

    @Test
    @DisplayName("Issuer with multi-segment path (Keycloak-shape)")
    void issuerWithKeycloakShapedPath() {
        URI issuer = URI.create("https://kc.example.com/realms/ssf-poc");
        assertThat(SsfConfigurationResolver.deriveMetadataUri(issuer),
                equalTo(URI.create("https://kc.example.com/.well-known/ssf-configuration/realms/ssf-poc")));
    }

    @Test
    @DisplayName("Trailing slash on issuer path is stripped before splicing")
    void issuerWithTrailingSlashOnPath() {
        URI issuer = URI.create("https://tr.example.com/issuer1/");
        assertThat(SsfConfigurationResolver.deriveMetadataUri(issuer),
                equalTo(URI.create("https://tr.example.com/.well-known/ssf-configuration/issuer1")));
    }

    @Test
    @DisplayName("Non-default port is preserved in the authority component")
    void issuerWithPortIsPreserved() {
        URI issuer = URI.create("https://tr.example.com:8443/realms/r1");
        assertThat(SsfConfigurationResolver.deriveMetadataUri(issuer),
                equalTo(URI.create("https://tr.example.com:8443/.well-known/ssf-configuration/realms/r1")));
    }

    @Test
    @DisplayName("Non-https scheme is honored (http for local stubs)")
    void issuerWithHttpScheme() {
        URI issuer = URI.create("http://localhost:9000");
        assertThat(SsfConfigurationResolver.deriveMetadataUri(issuer),
                equalTo(URI.create("http://localhost:9000/.well-known/ssf-configuration")));
    }

    @Test
    @DisplayName("null issuer throws SsfMetadataException")
    void nullIssuerThrows() {
        assertThrows(SsfMetadataException.class,
                () -> SsfConfigurationResolver.deriveMetadataUri(null));
    }

    @Test
    @DisplayName("Issuer without scheme/authority throws SsfMetadataException")
    void relativeIssuerThrows() {
        URI relative = URI.create("/just-a-path");
        assertThrows(SsfMetadataException.class,
                () -> SsfConfigurationResolver.deriveMetadataUri(relative));
    }
}
