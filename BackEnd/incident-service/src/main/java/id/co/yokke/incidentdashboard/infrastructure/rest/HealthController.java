package id.co.yokke.incidentdashboard.infrastructure.rest;

import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Unauthenticated liveness endpoint. Phase 3's hand-rolled AuthFilter must
 * include this path in its NO_AUTH allow-list.
 */
@RestController
public class HealthController {

	@GetMapping("/health")
	public Map<String, String> health() {
		return Map.of("status", "UP");
	}

}
