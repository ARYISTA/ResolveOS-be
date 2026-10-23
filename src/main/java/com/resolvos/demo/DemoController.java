package com.resolvos.demo;

import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/demo")
@Profile("demo")
public class DemoController {

    private final DemoSeeder demoSeeder;

    public DemoController(DemoSeeder demoSeeder) {
        this.demoSeeder = demoSeeder;
    }

    @PostMapping("/seed")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'TENANT_ADMIN')")
    public Map<String, String> seed() {
        demoSeeder.seed();
        return Map.of("message", "Demo data seeded successfully");
    }

    @PostMapping("/reset")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'TENANT_ADMIN')")
    public Map<String, String> reset() {
        demoSeeder.reset();
        return Map.of("message", "Demo data reset successfully");
    }
}
