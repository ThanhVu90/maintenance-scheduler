package dev.thanhvu.maintenance;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;

@Configuration
@EnableMethodSecurity
public class SecurityConfig {
  @Bean
  UserDetailsService users(
      @Value("${app.planner-password}") String planner,
      @Value("${app.technician-password}") String technician,
      @Value("${app.viewer-password}") String viewer) {
    var encoder = new BCryptPasswordEncoder();
    return new InMemoryUserDetailsManager(
        User.withUsername("planner")
            .password("{bcrypt}" + encoder.encode(planner))
            .roles("PLANNER")
            .build(),
        User.withUsername("technician")
            .password("{bcrypt}" + encoder.encode(technician))
            .roles("TECHNICIAN")
            .build(),
        User.withUsername("viewer")
            .password("{bcrypt}" + encoder.encode(viewer))
            .roles("VIEWER")
            .build());
  }

  @Bean
  SecurityFilterChain security(HttpSecurity http) throws Exception {
    return http.authorizeHttpRequests(
            a ->
                a.requestMatchers("/", "/index.html", "/app.js", "/style.css", "/api/csrf")
                    .permitAll()
                    .anyRequest()
                    .authenticated())
        .formLogin(
            f ->
                f.loginProcessingUrl("/api/login")
                    .successHandler((q, s, a) -> s.setStatus(204))
                    .failureHandler((q, s, e) -> s.sendError(401))
                    .permitAll())
        .logout(l -> l.logoutUrl("/api/logout").logoutSuccessHandler((q, s, a) -> s.setStatus(204)))
        .exceptionHandling(
            e -> e.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
        .headers(
            h ->
                h.contentSecurityPolicy(
                    c ->
                        c.policyDirectives(
                            "default-src 'self'; script-src 'self'; style-src 'self'; object-src 'none'; frame-ancestors 'none'; base-uri 'none'")))
        .build();
  }
}
