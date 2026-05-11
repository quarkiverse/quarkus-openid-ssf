package io.quarkiverse.ssf.receiver.deployment;

import io.quarkiverse.ssf.receiver.runtime.devui.SsfDevJsonRpcService;
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
