package io.quarkiverse.ssf.receiver.example.receivermanaged;

import java.util.List;
import java.util.Optional;

import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

@Path("/events")
public class SsfEventsResource {

    @Inject
    CapturingSsfEventHandler handler;

    @GET
    @Path("/recent-events")
    @Produces(MediaType.APPLICATION_JSON)
    public List<CapturedEvent> recentEvents() {
        return handler.recentEvents();
    }

    @GET
    @Path("/latest")
    @Produces(MediaType.APPLICATION_JSON)
    public Optional<CapturedEvent> latest() {
        return handler.latestEvent();
    }
}
