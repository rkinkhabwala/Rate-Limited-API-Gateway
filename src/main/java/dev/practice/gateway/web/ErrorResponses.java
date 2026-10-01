package dev.practice.gateway.web;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;

/** Writes RFC 9457 Problem Details from filters, where there's no controller advice to do it for us. */
public final class ErrorResponses {

    private ErrorResponses() {
    }

    public static Mono<Void> write(ServerWebExchange exchange, HttpStatus status, String code, String detail) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(status);
        response.getHeaders().setContentType(MediaType.APPLICATION_PROBLEM_JSON);
        String json = "{\"type\":\"about:blank\",\"title\":\"" + escape(code) + "\",\"status\":" + status.value()
                + ",\"code\":\"" + escape(code) + "\",\"detail\":\"" + escape(detail) + "\"}";
        return response.writeWith(Mono.just(response.bufferFactory().wrap(json.getBytes(StandardCharsets.UTF_8))));
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
