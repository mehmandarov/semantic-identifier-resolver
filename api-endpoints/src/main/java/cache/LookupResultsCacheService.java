package cache;

import com.arangodb.entity.ArangoDBVersion;

import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;


@Path("/cache")
public class LookupResultsCacheService {

    private final ArangoService arangoService;

    @Inject
    public LookupResultsCacheService(ArangoService arangoService) {
        this.arangoService = arangoService;
    }

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public ArangoDBVersion getVersion() {
        return arangoService.getVersion();
    }
}
