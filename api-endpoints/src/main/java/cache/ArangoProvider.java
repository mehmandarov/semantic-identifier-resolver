package cache;

import com.arangodb.ArangoDB;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.inject.Produces;
import org.eclipse.microprofile.config.inject.ConfigProperties;


@Dependent
public class ArangoProvider {

    @Produces
    public ArangoDB arangoDB(@ConfigProperties final ArangoConfig config) {
        return new ArangoDB.Builder()
                .loadProperties(config)
                .build();
    }

}
