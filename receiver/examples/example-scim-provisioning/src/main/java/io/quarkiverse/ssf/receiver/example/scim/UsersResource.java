package io.quarkiverse.ssf.receiver.example.scim;

import java.util.Collection;

import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/**
 * Shows the mirrored directory.
 */
@Path("/users")
@Produces(MediaType.APPLICATION_JSON)
public class UsersResource {

    @Inject
    UserDirectory directory;

    @GET
    public Collection<User> users() {
        return directory.all();
    }

    @GET
    @Path("/{id}")
    public User user(@PathParam("id") String id) {
        User user = directory.find(id);
        if (user == null) {
            throw new NotFoundException("No user " + id + " in the directory");
        }
        return user;
    }
}
