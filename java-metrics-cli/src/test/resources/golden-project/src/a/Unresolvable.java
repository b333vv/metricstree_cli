package a;

import missing.dependency.AbsentService;

/**
 * Deliberately unresolvable reference fixture.
 *
 * <p>{@code missing.dependency.AbsentService} does not exist on the analysis classpath, so every
 * visitor that tries to resolve it goes through the silent-failure path. This pins today's
 * behaviour (no diagnostic is emitted yet) and will make the diagnostics work of TASK-101..103 an
 * explicit, reviewable contract change.
 */
public class Unresolvable {

    private final AbsentService service;

    public Unresolvable(AbsentService service) {
        this.service = service;
    }

    public String call() {
        return service.describe();
    }
}
