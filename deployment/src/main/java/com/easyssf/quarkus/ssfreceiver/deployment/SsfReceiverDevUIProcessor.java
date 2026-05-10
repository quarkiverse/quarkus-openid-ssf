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
package com.easyssf.quarkus.ssfreceiver.deployment;

import com.easyssf.quarkus.ssfreceiver.runtime.devui.SsfDevJsonRpcService;

import io.quarkus.arc.deployment.AdditionalBeanBuildItem;
import io.quarkus.deployment.IsDevelopment;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.devui.spi.JsonRPCProvidersBuildItem;
import io.quarkus.devui.spi.page.CardPageBuildItem;
import io.quarkus.devui.spi.page.Page;

class SsfReceiverDevUIProcessor {

    @BuildStep(onlyIf = IsDevelopment.class)
    AdditionalBeanBuildItem registerDevBean() {
        return AdditionalBeanBuildItem.unremovableOf(SsfDevJsonRpcService.class);
    }

    @BuildStep(onlyIf = IsDevelopment.class)
    CardPageBuildItem createCard() {
        CardPageBuildItem card = new CardPageBuildItem();

        card.addPage(Page.webComponentPageBuilder()
                .title("Stream Management")
                .icon("font-awesome-solid:right-left")
                .componentLink("qwc-ssf-stream-management.js"));

        card.addPage(Page.webComponentPageBuilder()
                .title("Transmitter Metadata")
                .icon("font-awesome-solid:circle-info")
                .componentLink("qwc-ssf-transmitter-metadata.js"));

        return card;
    }

    @BuildStep(onlyIf = IsDevelopment.class)
    JsonRPCProvidersBuildItem registerJsonRpcService() {
        return new JsonRPCProvidersBuildItem(SsfDevJsonRpcService.class);
    }
}
