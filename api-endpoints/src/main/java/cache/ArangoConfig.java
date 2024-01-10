package cache;


import com.arangodb.Protocol;
import com.arangodb.config.ArangoConfigProperties;
import com.arangodb.config.HostDescription;
import jakarta.enterprise.context.Dependent;
import org.eclipse.microprofile.config.inject.ConfigProperties;

import java.util.List;
import java.util.Optional;

@Dependent
@ConfigProperties(prefix = "cache")
public class ArangoConfig implements ArangoConfigProperties {
    private Optional<List<HostDescription>> hosts;
    private Optional<Protocol> protocol;
    private Optional<String> password;

    @Override
    public Optional<List<HostDescription>> getHosts() {
        return hosts;
    }

    @Override
    public Optional<Protocol> getProtocol() {
        return protocol;
    }

    @Override
    public Optional<String> getPassword() {
        return password;
    }
}
