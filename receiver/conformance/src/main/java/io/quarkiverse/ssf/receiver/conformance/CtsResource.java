package io.quarkiverse.ssf.receiver.conformance;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import org.easyssf.core.SsfDeliveryMethod;
import org.easyssf.receiver.push.SsfPushResponse;
import org.easyssf.test.conformance.receiver.ConformanceRun;
import org.easyssf.test.conformance.receiver.ConformanceRunner;
import org.easyssf.test.conformance.receiver.ConformanceScenario;
import org.easyssf.test.conformance.receiver.ConformanceSuiteModules;

/**
 * Starts test runs and receives the SETs the suite pushes.
 */
@Path("/")
public class CtsResource {

    @Inject
    ConformanceRunner runner;

    @Inject
    ConformanceSuiteModules suiteModules;

    public record RunView(String id, String scenario, String issuer, SsfDeliveryMethod delivery,
            ConformanceRun.Status status, String streamId, Instant startedAt, List<ConformanceRun.LogEntry> log,
            List<ConformanceRun.ReceivedSet> receivedSets) {

        static RunView of(ConformanceRun run) {
            return new RunView(run.getId(), run.getScenario().alias(), run.getIssuer(), run.getDeliveryMethod(),
                    run.getStatus(), run.streamId(), run.getStartedAt(), run.getLog(), run.getReceivedSets());
        }
    }

    /** A run started for the module waiting in the suite. */
    public record ModuleRunView(ConformanceSuiteModules.Module module, RunView run) {
    }

    @GET
    @Produces(MediaType.TEXT_PLAIN)
    public String usage() {
        return """
                easyssf receiver under test for the OpenID conformance suite (Quarkus)

                POST /cts/runs/auto                  start the run the test module waiting in the suite expects
                     ?issuer=<issuer>                optional: a test instance of the suite, default cts.transmitter.issuer
                GET  /cts/suite/modules              the test modules running in the suite and the scenario each gets
                POST /cts/runs?scenario=<scenario>   start a run (stops the current one), scenarios: %s
                     &issuer=<issuer>                optional: the test instance of the suite, default cts.transmitter.issuer
                     &delivery=push|poll             optional: default cts.delivery.method
                GET  /cts/runs/current               what the current run did so far
                POST /cts/runs/current/stop          stop the current run and delete its stream
                POST /ssf/push                       push endpoint for the suite
                """
                .formatted(Arrays.stream(ConformanceScenario.values()).map(ConformanceScenario::alias).toList());
    }

    @POST
    @Path("/cts/runs")
    @Produces(MediaType.APPLICATION_JSON)
    public Response start(@QueryParam("scenario") @DefaultValue("caep-interop") String scenario,
            @QueryParam("issuer") String issuer, @QueryParam("delivery") String delivery) {
        ConformanceScenario conformanceScenario;
        SsfDeliveryMethod deliveryMethod;
        try {
            conformanceScenario = ConformanceScenario.fromAlias(scenario);
            deliveryMethod = (delivery != null) ? SsfDeliveryMethod.valueOf(delivery.toUpperCase(Locale.ROOT)) : null;
        } catch (IllegalArgumentException ex) {
            throw error(Response.Status.BAD_REQUEST, ex.getMessage());
        }
        return Response.accepted(RunView.of(runner.start(conformanceScenario, issuer, deliveryMethod))).build();
    }

    /**
     * Starts the run for the test module that waits for the receiver in the suite: the
     * scenario the module expects, against the module's test instance and with the
     * delivery method of its variant.
     */
    @POST
    @Path("/cts/runs/auto")
    @Produces(MediaType.APPLICATION_JSON)
    public Response startForWaitingModule(@QueryParam("issuer") String issuer) {
        ConformanceSuiteModules.TestInstance instance;
        List<ConformanceSuiteModules.Module> waiting;
        try {
            instance = suiteModules.testInstance(issuer);
            waiting = suiteModules.waiting(instance);
        } catch (IllegalArgumentException ex) {
            throw error(Response.Status.BAD_REQUEST, ex.getMessage());
        } catch (IllegalStateException ex) {
            throw error(Response.Status.BAD_GATEWAY, ex.getMessage());
        }
        if (waiting.isEmpty()) {
            throw error(Response.Status.NOT_FOUND, "No test module is waiting for the receiver in the"
                    + " conformance suite at " + instance.suite() + "; start one there first");
        }
        if (waiting.size() > 1) {
            throw error(Response.Status.CONFLICT, "Several test modules are waiting for the receiver: "
                    + waiting.stream().map(module -> module.name() + " (" + module.issuer() + ")").toList()
                    + "; pick one with ?issuer=<issuer>");
        }
        ConformanceSuiteModules.Module module = waiting.get(0);
        ConformanceRun run = runner.start(module.scenario(), module.issuer(), module.deliveryMethod());
        return Response.accepted(new ModuleRunView(module, RunView.of(run))).build();
    }

    @GET
    @Path("/cts/suite/modules")
    @Produces(MediaType.APPLICATION_JSON)
    public List<ConformanceSuiteModules.Module> suiteModules(@QueryParam("issuer") String issuer) {
        try {
            return suiteModules.running(suiteModules.testInstance(issuer));
        } catch (IllegalArgumentException ex) {
            throw error(Response.Status.BAD_REQUEST, ex.getMessage());
        } catch (IllegalStateException ex) {
            throw error(Response.Status.BAD_GATEWAY, ex.getMessage());
        }
    }

    @GET
    @Path("/cts/runs/current")
    @Produces(MediaType.APPLICATION_JSON)
    public RunView current() {
        return RunView.of(currentRun());
    }

    @POST
    @Path("/cts/runs/current/stop")
    @Produces(MediaType.APPLICATION_JSON)
    public RunView stop() {
        ConformanceRun run = currentRun();
        run.stop();
        return RunView.of(run);
    }

    /**
     * The push endpoint the suite delivers to. Each run expects its own
     * {@code Authorization} header, registered with the stream.
     */
    @POST
    @Path("/ssf/push")
    @Consumes(MediaType.WILDCARD)
    public Response push(@HeaderParam(HttpHeaders.AUTHORIZATION) String authorization, byte[] body) {
        ConformanceRun run = runner.current().filter(ConformanceRun::isActive).orElse(null);
        if (run == null) {
            return Response.status(Response.Status.SERVICE_UNAVAILABLE)
                    .type(MediaType.APPLICATION_JSON)
                    .header(HttpHeaders.CONTENT_LANGUAGE, SsfPushResponse.CONTENT_LANGUAGE)
                    .entity("{\"err\":\"invalid_request\",\"description\":\"No conformance run is active\"}")
                    .build();
        }
        SsfPushResponse response = run.push(authorization, (body != null) ? body : new byte[0]);
        Response.ResponseBuilder builder = Response.status(response.status());
        if (response.body() == null) {
            return builder.build();
        }
        return builder.type(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.CONTENT_LANGUAGE, SsfPushResponse.CONTENT_LANGUAGE)
                .entity(response.body())
                .build();
    }

    private ConformanceRun currentRun() {
        return runner.current().orElseThrow(() -> error(Response.Status.NOT_FOUND, "No run was started yet"));
    }

    private static WebApplicationException error(Response.Status status, String message) {
        return new WebApplicationException(message,
                Response.status(status).type(MediaType.TEXT_PLAIN).entity(message).build());
    }
}
