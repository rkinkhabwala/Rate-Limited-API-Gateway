package dev.practice.gateway.web;

/** Names shared by the filters. */
public final class GatewayHeaders {

    public static final String API_KEY = "X-API-Key";
    public static final String ADMIN_TOKEN = "X-Admin-Token";

    /** Added for the upstream service so it knows who is calling without re-validating the key. */
    public static final String CLIENT_ID = "X-Client-Id";
    public static final String CLIENT_PLAN = "X-Client-Plan";

    public static final String RATE_LIMIT_LIMIT = "X-RateLimit-Limit";
    public static final String RATE_LIMIT_REMAINING = "X-RateLimit-Remaining";
    public static final String QUOTA_LIMIT = "X-Quota-Limit";
    public static final String QUOTA_REMAINING = "X-Quota-Remaining";

    /** Exchange attribute holding the authenticated {@code ApiClient}. */
    public static final String CLIENT_ATTR = "apigw.client";

    private GatewayHeaders() {
    }
}
