package io.quarkiverse.ssf.receiver.example.scim;

import java.util.List;
import java.util.Map;

import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.ServiceUnavailableException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import io.quarkiverse.ssf.receiver.example.scim.DemoScimProvider.TransmittedSet;

/**
 * A tiny stand-in for the {@code /Users} endpoint of a SCIM service provider: every
 * request transmits the SCIM Event about the change to the receiver of this application.
 * The response is the SET that was transmitted, as the compact JWT and its decoded claims.
 */
@Path("/demo/scim")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class DemoScimProviderResource {

    @Inject
    DemoScimProvider provider;

    @POST
    @Path("/Users")
    public Response create(Map<String, Object> user) {
        return Response.status(Response.Status.CREATED).entity(transmit(() -> provider.create(user))).build();
    }

    @PATCH
    @Path("/Users/{id}")
    public TransmittedSet patch(@PathParam("id") String id, @QueryParam("mode") String mode,
            Map<String, Object> patchOp) {
        return transmit(() -> provider.patch(id, patchOp, "notice".equals(mode)));
    }

    @PUT
    @Path("/Users/{id}")
    public TransmittedSet put(@PathParam("id") String id, Map<String, Object> user) {
        return transmit(() -> provider.put(id, user));
    }

    @DELETE
    @Path("/Users/{id}")
    public TransmittedSet delete(@PathParam("id") String id) {
        return transmit(() -> provider.delete(id));
    }

    @POST
    @Path("/Users/{id}/activate")
    public TransmittedSet activate(@PathParam("id") String id) {
        return transmit(() -> provider.setActive(id, true));
    }

    @POST
    @Path("/Users/{id}/deactivate")
    public TransmittedSet deactivate(@PathParam("id") String id) {
        return transmit(() -> provider.setActive(id, false));
    }

    /** The SETs the demo service provider transmitted, oldest first. */
    @GET
    @Path("/events")
    public List<TransmittedSet> events() {
        return provider.events();
    }

    private TransmittedSet transmit(java.util.function.Supplier<TransmittedSet> operation) {
        try {
            return operation.get();
        } catch (IllegalStateException e) {
            throw new ServiceUnavailableException(Response.status(Response.Status.SERVICE_UNAVAILABLE)
                    .entity(Map.of("error", e.getMessage())).build());
        }
    }
}
