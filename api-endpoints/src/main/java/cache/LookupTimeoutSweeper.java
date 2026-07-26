package cache;

import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Periodic sweep that marks stale lookups as timed-out. A lookup counts as
 * stale when it is still pending (never picked up) or in_progress (a worker
 * claimed it but never reported done/failed — e.g. it crashed) once the
 * timeout window after createdAt has passed. One mechanism covers both cases.
 * <p>
 * Configuration: {@code lookup.timeout-seconds} sets the window ({@code <= 0}
 * disables the sweep), {@code lookup.timeout-sweep-every} sets the cadence
 * (any Quarkus duration, or {@code off} to disable the scheduled trigger).
 */
@ApplicationScoped
public class LookupTimeoutSweeper {

    private final ArangoService arangoService;
    private final long timeoutSeconds;

    @Inject
    public LookupTimeoutSweeper(ArangoService arangoService,
                                @ConfigProperty(name = "lookup.timeout-seconds", defaultValue = "60")
                                long timeoutSeconds) {
        this.arangoService = arangoService;
        this.timeoutSeconds = timeoutSeconds;
    }

    @Scheduled(every = "{lookup.timeout-sweep-every}")
    void sweep() {
        if (timeoutSeconds <= 0) {
            return;
        }
        long marked = arangoService.markTimedOutRequests(timeoutSeconds);
        if (marked > 0) {
            System.out.println("Timeout sweep: marked " + marked + " lookup request(s) as timed-out (window: "
                    + timeoutSeconds + "s)");
        }
    }
}
