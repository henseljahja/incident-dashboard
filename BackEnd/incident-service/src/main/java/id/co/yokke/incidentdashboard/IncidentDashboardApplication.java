package id.co.yokke.incidentdashboard;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

// @ConfigurationPropertiesScan picks up SecurityProps (incident.jwt.*). Without it the
// @ConfigurationProperties bean is never registered and JwtService cannot be constructed.
@SpringBootApplication
@ConfigurationPropertiesScan
public class IncidentDashboardApplication {

	public static void main(String[] args) {
		SpringApplication.run(IncidentDashboardApplication.class, args);
	}

}
