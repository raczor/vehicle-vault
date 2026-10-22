package lat.raczor.vehiclevault.backfill;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** Active only with --spring.profiles.active=backfill; migrates then exits (see compose profile "backfill"). */
@Component
@Profile("backfill")
class BackfillRunner implements ApplicationRunner {

    private final BackfillService service;

    BackfillRunner(BackfillService service) {
        this.service = service;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        long migrated = service.run();
        System.out.println("backfill complete: migrated=" + migrated + " remaining=" + service.remaining());
    }
}
