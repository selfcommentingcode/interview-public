package com.weavelab.interview.seed;

import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "app.seed", havingValue = "true")
@Order(0) // run before StartupCheck (@Order(1)) so `--app.seed=true` seeds a fresh DB
          // instead of StartupCheck's empty-DB System.exit(1) firing first
public class SeedCommand implements CommandLineRunner {

    private final DataSeeder seeder;

    public SeedCommand(DataSeeder seeder) {
        this.seeder = seeder;
    }

    @Override
    public void run(String... args) throws Exception {
        int contacts = 10000;
        int files = 20;

        for (int i = 0; i < args.length; i++) {
            if (args[i].startsWith("--contacts=")) {
                contacts = Integer.parseInt(args[i].substring("--contacts=".length()));
            } else if (args[i].startsWith("--files=")) {
                files = Integer.parseInt(args[i].substring("--files=".length()));
            }
        }

        if (!seeder.isEmpty()) {
            System.out.println("Database already seeded. Use 'make reset' to reset.");
            System.exit(0);
        }

        System.out.println("Seeding database...");
        seeder.seed(contacts, files);
        System.out.println("Done seeding.");
        System.exit(0);
    }
}
