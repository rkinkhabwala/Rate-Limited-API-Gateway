package dev.practice.gateway.admin;

import dev.practice.gateway.admin.AdminDtos.ClientResponse;
import dev.practice.gateway.admin.AdminDtos.CreateClientRequest;
import dev.practice.gateway.admin.AdminDtos.IssuedKeyResponse;
import dev.practice.gateway.admin.AdminDtos.QuotaStatus;
import dev.practice.gateway.admin.AdminDtos.UsageReport;
import dev.practice.gateway.client.ApiClient;
import dev.practice.gateway.client.ApiClientStore;
import dev.practice.gateway.config.ApiGatewayProperties;
import dev.practice.gateway.metering.UsageMeter;
import dev.practice.gateway.ratelimit.QuotaPeriod;
import dev.practice.gateway.ratelimit.RateLimiter;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.time.Clock;
import java.time.YearMonth;

/** Client (API consumer) management and usage reports. Protected by {@link AdminAuthFilter}. */
@RestController
@RequestMapping("/admin/clients")
public class AdminController {

    private final ApiClientStore clients;
    private final RateLimiter rateLimiter;
    private final UsageMeter usageMeter;
    private final ApiGatewayProperties props;
    private final Clock clock;

    public AdminController(ApiClientStore clients, RateLimiter rateLimiter, UsageMeter usageMeter,
                           ApiGatewayProperties props, Clock clock) {
        this.clients = clients;
        this.rateLimiter = rateLimiter;
        this.usageMeter = usageMeter;
        this.props = props;
        this.clock = clock;
    }

    @PostMapping
    public Mono<ResponseEntity<IssuedKeyResponse>> create(@Valid @RequestBody CreateClientRequest request) {
        if (props.plan(request.plan()).isEmpty()) {
            return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Unknown plan '" + request.plan() + "'. Configured: " + props.plans().keySet()));
        }
        return clients.create(request.name(), request.plan())
                .map(issued -> ResponseEntity
                        .created(URI.create("/admin/clients/" + issued.client().id()))
                        .body(IssuedKeyResponse.from(issued)));
    }

    @GetMapping("/{id}")
    public Mono<ClientResponse> get(@PathVariable String id) {
        return load(id).map(ClientResponse::from);
    }

    @PostMapping("/{id}/rotate-key")
    public Mono<IssuedKeyResponse> rotateKey(@PathVariable String id) {
        return clients.rotateKey(id)
                .map(IssuedKeyResponse::from)
                .switchIfEmpty(Mono.error(notFound(id)));
    }

    @DeleteMapping("/{id}")
    public Mono<ResponseEntity<Void>> revoke(@PathVariable String id) {
        return clients.revoke(id).flatMap(existed -> existed
                ? Mono.just(ResponseEntity.noContent().<Void>build())
                : Mono.<ResponseEntity<Void>>error(notFound(id)));
    }

    @GetMapping("/{id}/usage")
    public Mono<UsageReport> usage(@PathVariable String id, @RequestParam(defaultValue = "7") int days) {
        if (days < 1 || days > 90) {
            return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST, "days must be 1-90"));
        }
        return load(id).flatMap(client -> {
            long limit = props.plan(client.plan()).map(ApiGatewayProperties.Plan::monthlyQuota).orElse(0L);
            YearMonth month = QuotaPeriod.current(clock);
            Mono<QuotaStatus> quota = rateLimiter.quotaUsed(client.id()).map(used -> new QuotaStatus(
                    QuotaPeriod.id(month), limit, used, Math.max(0, limit - used), QuotaPeriod.end(month)));
            return quota.zipWith(usageMeter.lastDays(client.id(), days).collectList(),
                    (q, daily) -> new UsageReport(client.id(), client.plan(), q, daily));
        });
    }

    private Mono<ApiClient> load(String id) {
        return clients.findById(id).switchIfEmpty(Mono.error(notFound(id)));
    }

    private static ResponseStatusException notFound(String id) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Client " + id + " not found");
    }
}
