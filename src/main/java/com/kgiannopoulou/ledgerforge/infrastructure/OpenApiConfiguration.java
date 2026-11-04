package com.kgiannopoulou.ledgerforge.infrastructure;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.headers.Header;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.IntegerSchema;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The OpenAPI document. Cross-cutting behaviour (authentication, idempotency, errors, pagination,
 * rate limits) is added here in one place, from rules, instead of repeating annotations on every
 * controller method. Controllers stay about the business, and a new endpoint gets the right
 * documentation automatically. {@code OpenApiDocumentationTest} checks the result.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "springdoc.api-docs.enabled", havingValue = "true")
class OpenApiConfiguration {

    private static final String BEARER = "bearerAuth";
    private static final String PROBLEM = "Problem";
    private static final String PROBLEM_JSON = "application/problem+json";
    private static final String TOKEN_PATH = "/api/v1/auth/token";
    private static final String PAYMENTS_PATH = "/api/v1/payments";

    private static final String DESCRIPTION = """
            A payment and double-entry ledger platform.

            ## Try it

            1. Open **POST /api/v1/auth/token** (enabled for local use only) and send
               `{"subject": "ops", "roles": ["ADMIN"]}`.
            2. Copy `accessToken`, press **Authorize** and paste it.
            3. Create a customer and a wallet, then mint a token for that customer with
               `{"subject": "<id>", "roles": ["CUSTOMER"], "customerId": "<id>"}`.

            ## Authentication

            Every endpoint except the token endpoint needs `Authorization: Bearer <JWT>`. Roles are
            `CUSTOMER`, `SUPPORT`, `AUDITOR` and `ADMIN`, and they are not hierarchical: an admin cannot
            spend a customer's money. A customer asking for another customer's resource gets `403`,
            whether or not it exists.

            ## Idempotency

            Send an `Idempotency-Key` on money-moving requests. Retrying with the same key and body
            returns the original response with `Idempotent-Replayed: true`, and no money moves twice.
            The same key with a different body is a `409 IDEMPOTENCY_KEY_REUSED`. Keys last 24 hours,
            and a failed request releases its key.

            ## Errors

            Every error is an RFC 9457 problem document. Branch on `type`, never on `detail`, and quote
            `traceId` to support: it finds the matching log lines.

            ```json
            {
              "type": "INSUFFICIENT_FUNDS",
              "title": "Insufficient funds",
              "status": 422,
              "detail": "Account 3f0c... has insufficient available funds",
              "traceId": "9d1c2f0e5b8a4c7d9e6f1a2b3c4d5e6f"
            }
            ```

            ## Pagination

            Lists that can grow take `page` (from 0) and `size`, and return `items`, `page`, `size`,
            `totalItems` and `totalPages`. Results are newest first.

            ## Rate limits

            `POST /api/v1/payments` allows 100 requests per minute per account, over a sliding window.
            Past that it returns `429 RATE_LIMIT_EXCEEDED` with a `Retry-After` header in seconds.
            Payments are also screened by a risk engine, so `201` can mean `UNDER_REVIEW`, and a refusal
            is a deliberately unexplained `422 PAYMENT_BLOCKED`.
            """;

    private static final Set<String> CREATES_A_RESOURCE = Set.of(PAYMENTS_PATH, "/api/v1/transfers", "/api/v1/accounts/{accountId}/deposits");

    /** Display names for the modules, by path prefix; the first match wins. */
    private static final Map<String, String> TAGS = Map.ofEntries(
            Map.entry("/api/v1/auth", "Authentication"),
            Map.entry("/api/v1/customers", "Customers"),
            Map.entry("/api/v1/accounts", "Accounts"),
            Map.entry("/api/v1/transfers", "Transfers"),
            Map.entry("/api/v1/payments", "Payments"),
            Map.entry("/api/v1/webhook", "Webhooks"),
            Map.entry("/api/v1/risk", "Risk"),
            Map.entry("/api/v1/ledger", "Ledger"),
            Map.entry("/api/v1/audit-events", "Audit"),
            Map.entry("/api/v1/admin", "Operations"));

    @Bean
    OpenAPI ledgerForgeOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("LedgerForge API")
                        .version("v1")
                        .description(DESCRIPTION)
                        .license(new License().name("MIT")))
                .components(new Components()
                        .addSecuritySchemes(BEARER, new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")
                                .description("Short-lived JWT. Locally: POST /api/v1/auth/token.")));
    }

    @Bean
    OpenApiCustomizer crossCuttingDocumentation() {
        return openApi -> {
            // Here, not in the OpenAPI bean: springdoc rebuilds the schemas from the controllers.
            openApi.getComponents().addSchemas(PROBLEM, problemSchema());
            openApi.getPaths().forEach((path, item) ->
                    item.readOperationsMap().forEach((method, operation) -> document(path, method, operation)));
        };
    }

    private static void document(String path, PathItem.HttpMethod method, Operation operation) {
        TAGS.entrySet().stream().filter(tag -> path.startsWith(tag.getKey())).findFirst()
                .ifPresent(tag -> operation.setTags(List.of(tag.getValue())));

        if (operation.getParameters() != null) {
            operation.getParameters().forEach(OpenApiConfiguration::describe);
        }

        ApiResponses responses = operation.getResponses();
        responses.remove("500");
        // These return ResponseEntity, so the real status is not visible to springdoc.
        if (method == PathItem.HttpMethod.POST && CREATES_A_RESOURCE.contains(path) && responses.containsKey("200")) {
            responses.addApiResponse("201", responses.remove("200").description("Created"));
        }
        boolean isPublic = path.equals(TOKEN_PATH);
        if (!isPublic) {
            operation.addSecurityItem(new SecurityRequirement().addList(BEARER));
            responses.addApiResponse("401", problem("Missing, invalid or expired token"));
            responses.addApiResponse("403", problem("Role not allowed, or the resource is not yours"));
        }
        if (method == PathItem.HttpMethod.POST || method == PathItem.HttpMethod.PUT) {
            responses.addApiResponse("400", problem("Validation failed or malformed request"));
            if (!isPublic) {
                responses.addApiResponse("422", problem("A business rule was violated"));
                responses.addApiResponse("409", problem("Conflicts with the current state"));
            }
        }
        if (path.contains("{")) {
            responses.addApiResponse("404", problem("Not found (staff; customers get 403)"));
        }
        if (path.equals(PAYMENTS_PATH) && method == PathItem.HttpMethod.POST) {
            responses.addApiResponse("429", problem("Rate limit exceeded")
                    .addHeaderObject("Retry-After", new Header()
                            .description("Seconds to wait before retrying")
                            .schema(new IntegerSchema())));
        }
    }

    private static void describe(Parameter parameter) {
        switch (parameter.getName().toLowerCase(Locale.ROOT)) {
            case "idempotency-key" -> parameter.setDescription(
                    "Makes retries safe: the same key and body return the original response. 1-255 characters.");
            case "page" -> parameter.setDescription("Page number, starting from 0.");
            case "size" -> parameter.setDescription("Items per page.");
            default -> { }
        }
    }

    private static ApiResponse problem(String description) {
        return new ApiResponse().description(description).content(new Content().addMediaType(PROBLEM_JSON,
                new MediaType().schema(new Schema<>().$ref("#/components/schemas/" + PROBLEM))));
    }

    private static Schema<?> problemSchema() {
        return new ObjectSchema()
                .description("RFC 9457 problem document, the shape of every error")
                .addProperty("type", new StringSchema().example("INSUFFICIENT_FUNDS")
                        .description("Stable and machine-readable: branch on this"))
                .addProperty("title", new StringSchema().example("Insufficient funds"))
                .addProperty("status", new IntegerSchema().example(422))
                .addProperty("detail", new StringSchema().description("What went wrong in this request"))
                .addProperty("traceId", new StringSchema().description("Same as the X-Trace-Id header and the logs"));
    }
}
