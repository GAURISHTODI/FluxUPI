package com.fluxupi;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * One walk through the whole REST surface against a real Postgres: register a
 * user, take the seeded lender's line, spend, generate a schedule, repay, and
 * read the statement — checking the ledger view the statement returns agrees
 * with the credit line's own balance at the end.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ApiSmokeIT extends AbstractIntegrationTest {

    @Autowired
    private TestRestTemplate rest;

    private HttpEntity<String> json(String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return new HttpEntity<>(body, headers);
    }

    @Test
    @DisplayName("register -> apply -> activate -> spend -> schedule -> repay -> statement")
    void fullHappyPath() {
        // 1. Register a user with enough income for the seeded PRUDENT lender.
        String vpa = "smoke." + UUID.randomUUID() + "@fluxbank";
        ResponseEntity<JsonNode> user = rest.postForEntity("/users",
                json("""
                        {"fullName":"Smoke Test","vpa":"%s","declaredMonthlyIncome":60000}
                        """.formatted(vpa)), JsonNode.class);
        assertThat(user.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String userId = user.getBody().get("id").asText();

        // 2. Apply for a credit line — underwriting runs synchronously.
        ResponseEntity<JsonNode> line = rest.postForEntity("/credit-lines",
                json("""
                        {"userId":"%s","lenderCode":"PRUDENT"}
                        """.formatted(userId)), JsonNode.class);
        assertThat(line.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(line.getBody().get("status").asText()).isEqualTo("APPROVED");
        String lineId = line.getBody().get("id").asText();

        // 3. Activate it.
        ResponseEntity<JsonNode> activated = rest.postForEntity("/credit-lines/" + lineId + "/activate",
                json("{}"), JsonNode.class);
        assertThat(activated.getBody().get("status").asText()).isEqualTo("ACTIVE");

        // 4. Spend, and prove the retry is absorbed.
        String spendBody = """
                {"creditLineId":"%s","amount":18000,"payeeVpa":"store@fluxbank","description":"tv",
                 "idempotencyKey":"smoke-spend-1"}
                """.formatted(lineId);
        ResponseEntity<JsonNode> spend1 = rest.postForEntity("/transactions", json(spendBody), JsonNode.class);
        ResponseEntity<JsonNode> spend2 = rest.postForEntity("/transactions", json(spendBody), JsonNode.class);
        assertThat(spend1.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(spend2.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(spend1.getBody().get("id")).isEqualTo(spend2.getBody().get("id"));
        assertThat(spend2.getBody().get("replayed").asBoolean()).isTrue();

        // 5. Generate the EMI schedule for the drawn principal.
        ResponseEntity<JsonNode> schedule = rest.postForEntity(
                "/credit-lines/" + lineId + "/repayment-schedule", json("{}"), JsonNode.class);
        assertThat(schedule.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(schedule.getBody().get("principal").decimalValue()).isEqualByComparingTo("18000.00");
        assertThat(schedule.getBody().get("installments")).hasSize(12);

        // 6. Repay the first instalment.
        ResponseEntity<JsonNode> repay = rest.postForEntity("/repayments",
                json("""
                        {"creditLineId":"%s","idempotencyKey":"smoke-repay-1"}
                        """.formatted(lineId)), JsonNode.class);
        assertThat(repay.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        // 7. The statement must show the two balance views agreeing.
        ResponseEntity<JsonNode> statement = rest.getForEntity(
                "/credit-lines/" + lineId + "/statement", JsonNode.class);
        JsonNode s = statement.getBody();
        assertThat(s.get("balancesAgree").asBoolean())
                .as("ledger receivable must match the credit line's outstanding principal")
                .isTrue();
        assertThat(s.get("transactions")).hasSizeGreaterThanOrEqualTo(2);
        assertThat(s.get("ledgerEntries").size()).isGreaterThanOrEqualTo(4);
    }

    @Test
    @DisplayName("reusing an idempotency key with a different amount is a 409")
    void idempotencyConflictSurfacesAs409() {
        String vpa = "smoke." + UUID.randomUUID() + "@fluxbank";
        String userId = rest.postForEntity("/users",
                json("""
                        {"fullName":"Conflict","vpa":"%s","declaredMonthlyIncome":60000}
                        """.formatted(vpa)), JsonNode.class).getBody().get("id").asText();
        String lineId = rest.postForEntity("/credit-lines",
                json("""
                        {"userId":"%s","lenderCode":"PRUDENT"}
                        """.formatted(userId)), JsonNode.class).getBody().get("id").asText();
        rest.postForEntity("/credit-lines/" + lineId + "/activate", json("{}"), JsonNode.class);

        String key = "dup-" + UUID.randomUUID();
        rest.postForEntity("/transactions", json("""
                {"creditLineId":"%s","amount":1000,"payeeVpa":"a@fluxbank","idempotencyKey":"%s"}
                """.formatted(lineId, key)), JsonNode.class);
        ResponseEntity<JsonNode> conflict = rest.postForEntity("/transactions", json("""
                {"creditLineId":"%s","amount":2000,"payeeVpa":"a@fluxbank","idempotencyKey":"%s"}
                """.formatted(lineId, key)), JsonNode.class);

        assertThat(conflict.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(conflict.getBody().get("errorCode").asText()).isEqualTo("IDEMPOTENCY_KEY_CONFLICT");
    }

    @Test
    @DisplayName("insufficient limit is a 422 with a machine-readable code")
    void insufficientLimitIsA422() {
        String vpa = "smoke." + UUID.randomUUID() + "@fluxbank";
        String userId = rest.postForEntity("/users",
                json("""
                        {"fullName":"Broke","vpa":"%s","declaredMonthlyIncome":11000}
                        """.formatted(vpa)), JsonNode.class).getBody().get("id").asText();
        // STARTER: min income 10,000, so this approves at a small limit.
        JsonNode line = rest.postForEntity("/credit-lines",
                json("""
                        {"userId":"%s","lenderCode":"STARTER"}
                        """.formatted(userId)), JsonNode.class).getBody();
        String lineId = line.get("id").asText();
        rest.postForEntity("/credit-lines/" + lineId + "/activate", json("{}"), JsonNode.class);

        ResponseEntity<JsonNode> tooBig = rest.postForEntity("/transactions", json("""
                {"creditLineId":"%s","amount":99999999,"payeeVpa":"a@fluxbank","idempotencyKey":"%s"}
                """.formatted(lineId, UUID.randomUUID())), JsonNode.class);

        assertThat(tooBig.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(tooBig.getBody().get("errorCode").asText()).isEqualTo("INSUFFICIENT_CREDIT_LIMIT");
    }

    @Test
    @DisplayName("lenders endpoint lists the seeded mock lenders")
    void lendersAreSeeded() {
        ResponseEntity<JsonNode> lenders = rest.getForEntity("/lenders", JsonNode.class);
        assertThat(lenders.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(lenders.getBody()).anySatisfy(l ->
                assertThat(l.get("code").asText()).isEqualTo("PRUDENT"));
    }
}
