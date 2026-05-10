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
package com.easyssf.quarkus.ssfreceiver.example;

import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

import com.easyssf.quarkus.ssfreceiver.runtime.metadata.SsfConfigurationResolver;
import com.easyssf.quarkus.ssfreceiver.runtime.metadata.SsfTransmitterMetadata;

/**
 * Endpoints exposing transmitter-side information that isn't bound to a single
 * stream — currently just the parsed {@code ssf-configuration} metadata document.
 */
@Path("/transmitter")
public class SsfTransmitterResource {

    @Inject
    SsfConfigurationResolver metadataResolver;

    @GET
    @Path("/metadata")
    @Produces(MediaType.APPLICATION_JSON)
    public SsfTransmitterMetadata metadata() {
        return metadataResolver.get();
    }
}
