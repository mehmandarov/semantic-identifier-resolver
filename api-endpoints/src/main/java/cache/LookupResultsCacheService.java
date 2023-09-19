package cache;

import io.quarkus.redis.datasource.value.ValueCommands;
import jakarta.enterprise.context.ApplicationScoped;

import io.quarkus.redis.datasource.ReactiveRedisDataSource;
import io.quarkus.redis.datasource.RedisDataSource;
import io.quarkus.redis.datasource.keys.ReactiveKeyCommands;
import io.smallrye.mutiny.Uni;

import java.util.List;

@ApplicationScoped
public class LookupResultsCacheService {

    // This quickstart demonstrates both the imperative
    // and reactive Redis data sources
    // Regular applications will pick one of them.

    private final ReactiveKeyCommands<String> keyCommands;
    private final ValueCommands<String, String> cacheValueCommands;

    public LookupResultsCacheService(RedisDataSource ds, ReactiveRedisDataSource reactive) {
        cacheValueCommands = ds.value(String.class);
        keyCommands = reactive.key();

    }


    public String get(String key) {
        String value = cacheValueCommands.get(key);
        if (value == null) {
            return "";
        }
        return value;
    }

    public void set(String key, String value) {
        cacheValueCommands.set(key, value);
    }

    Uni<Void> del(String key) {
        return keyCommands.del(key)
                .replaceWithVoid();
    }

    public Uni<List<String>> keys() {
        return keyCommands.keys("*");
    }
}