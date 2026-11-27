package com.kmarket.ordersservice.client;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpMethod.GET;
import static org.springframework.http.HttpMethod.POST;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withResourceNotFound;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Unit tests for {@link PaymentRestClient}: mapping of the {@code POST /payments} and
 * {@code GET /payments/{order_id}} responses to a {@link PaymentOutcome}, that the service
 * token and the stable {@code Idempotency-Key} are attached, and that the request body
 * matches the Payment contract ({@code order_id}, {@code amount_cents}, {@code currency}).
 */
class PaymentRestClientTest {

    private static final String BASE_URL = "http://payment-service:8003/v1";
    private static final int ORDER_ID = 7;
    private static final String KEY = PaymentClient.paymentKey(ORDER_ID);

    private PaymentRestClient newClient(MockRestServiceServer[] holder) {
        RestClient.Builder builder = RestClient.builder();
        holder[0] = MockRestServiceServer.bindTo(builder).build();
        return new PaymentRestClient(builder, BASE_URL, new FixedTokenProvider("svc-token"));
    }

    @Test
    void capture_created_isSucceeded_andSendsContractBody() {
        MockRestServiceServer[] holder = new MockRestServiceServer[1];
        PaymentRestClient client = newClient(holder);

        holder[0].expect(requestTo(BASE_URL + "/payments"))
                .andExpect(method(POST))
                .andExpect(header("Authorization", "Bearer svc-token"))
                .andExpect(header("Idempotency-Key", KEY))
                .andExpect(jsonPath("$.order_id").value(ORDER_ID))
                .andExpect(jsonPath("$.amount_cents").value(4990))
                .andExpect(jsonPath("$.currency").value("EUR"))
                .andRespond(withStatus(HttpStatus.CREATED)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"id\":1,\"order_id\":7,\"amount_cents\":4990,\"status\":\"succeeded\"}"));

        assertThat(client.capture(ORDER_ID, 4990, KEY)).isEqualTo(PaymentOutcome.SUCCEEDED);
        holder[0].verify();
    }

    @Test
    void capture_unprocessable_isDeclined() {
        MockRestServiceServer[] holder = new MockRestServiceServer[1];
        PaymentRestClient client = newClient(holder);

        holder[0].expect(requestTo(BASE_URL + "/payments"))
                .andRespond(withStatus(HttpStatus.UNPROCESSABLE_CONTENT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"code\":\"PAYMENT_FAILED\",\"reason\":\"amount_exceeds_limit\"}"));

        assertThat(client.capture(ORDER_ID, 25000, KEY)).isEqualTo(PaymentOutcome.DECLINED);
    }

    @Test
    void capture_conflict_recoversViaLookup_succeeded() {
        MockRestServiceServer[] holder = new MockRestServiceServer[1];
        PaymentRestClient client = newClient(holder);

        // 409 on POST (key already used) -> the client reads GET /payments/{id} to recover.
        holder[0].expect(once(), requestTo(BASE_URL + "/payments"))
                .andExpect(method(POST))
                .andRespond(withStatus(HttpStatus.CONFLICT));
        holder[0].expect(once(), requestTo(BASE_URL + "/payments/" + ORDER_ID))
                .andExpect(method(GET))
                .andRespond(withSuccess(
                        "{\"id\":1,\"order_id\":7,\"status\":\"succeeded\"}", MediaType.APPLICATION_JSON));

        assertThat(client.capture(ORDER_ID, 4990, KEY)).isEqualTo(PaymentOutcome.SUCCEEDED);
        holder[0].verify();
    }

    @Test
    void capture_otherStatus_isUncertain() {
        MockRestServiceServer[] holder = new MockRestServiceServer[1];
        PaymentRestClient client = newClient(holder);

        holder[0].expect(requestTo(BASE_URL + "/payments"))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));

        assertThat(client.capture(ORDER_ID, 4990, KEY)).isEqualTo(PaymentOutcome.UNCERTAIN);
    }

    @Test
    void capture_transportError_isUncertain() {
        MockRestServiceServer[] holder = new MockRestServiceServer[1];
        PaymentRestClient client = newClient(holder);

        holder[0].expect(requestTo(BASE_URL + "/payments"))
                .andRespond(request -> {
                    throw new java.io.IOException("connection reset / timeout");
                });

        assertThat(client.capture(ORDER_ID, 4990, KEY)).isEqualTo(PaymentOutcome.UNCERTAIN);
    }

    @Test
    void lookup_succeeded_isSucceeded() {
        MockRestServiceServer[] holder = new MockRestServiceServer[1];
        PaymentRestClient client = newClient(holder);

        holder[0].expect(requestTo(BASE_URL + "/payments/" + ORDER_ID))
                .andExpect(method(GET))
                .andExpect(header("Authorization", "Bearer svc-token"))
                .andRespond(withSuccess(
                        "{\"id\":1,\"order_id\":7,\"status\":\"succeeded\"}", MediaType.APPLICATION_JSON));

        assertThat(client.lookup(ORDER_ID)).isEqualTo(PaymentOutcome.SUCCEEDED);
        holder[0].verify();
    }

    @Test
    void lookup_failed_isDeclined() {
        MockRestServiceServer[] holder = new MockRestServiceServer[1];
        PaymentRestClient client = newClient(holder);

        holder[0].expect(requestTo(BASE_URL + "/payments/" + ORDER_ID))
                .andRespond(withSuccess(
                        "{\"id\":1,\"order_id\":7,\"status\":\"failed\"}", MediaType.APPLICATION_JSON));

        assertThat(client.lookup(ORDER_ID)).isEqualTo(PaymentOutcome.DECLINED);
    }

    @Test
    void lookup_notFound_isUncertain() {
        MockRestServiceServer[] holder = new MockRestServiceServer[1];
        PaymentRestClient client = newClient(holder);

        holder[0].expect(requestTo(BASE_URL + "/payments/" + ORDER_ID))
                .andRespond(withResourceNotFound());

        assertThat(client.lookup(ORDER_ID)).isEqualTo(PaymentOutcome.UNCERTAIN);
    }

    @Test
    void refund_ok_isTrue() {
        MockRestServiceServer[] holder = new MockRestServiceServer[1];
        PaymentRestClient client = newClient(holder);

        holder[0].expect(requestTo(BASE_URL + "/payments/" + ORDER_ID + "/refund"))
                .andExpect(method(POST))
                .andExpect(header("Authorization", "Bearer svc-token"))
                .andRespond(withSuccess(
                        "{\"id\":1,\"order_id\":7,\"status\":\"refunded\"}", MediaType.APPLICATION_JSON));

        assertThat(client.refund(ORDER_ID)).isTrue();
        holder[0].verify();
    }

    @Test
    void refund_conflict_isTreatedAsAlreadyCompensated() {
        MockRestServiceServer[] holder = new MockRestServiceServer[1];
        PaymentRestClient client = newClient(holder);

        holder[0].expect(requestTo(BASE_URL + "/payments/" + ORDER_ID + "/refund"))
                .andRespond(withStatus(HttpStatus.CONFLICT));

        assertThat(client.refund(ORDER_ID)).isTrue();
    }

    @Test
    void refund_transportError_isFalse() {
        MockRestServiceServer[] holder = new MockRestServiceServer[1];
        PaymentRestClient client = newClient(holder);

        holder[0].expect(requestTo(BASE_URL + "/payments/" + ORDER_ID + "/refund"))
                .andRespond(request -> {
                    throw new java.io.IOException("timeout");
                });

        assertThat(client.refund(ORDER_ID)).isFalse();
    }

    /** Minimal {@link ServiceTokenProvider} returning a fixed token (no HTTP). */
    private static final class FixedTokenProvider extends ServiceTokenProvider {
        private final String token;

        FixedTokenProvider(String token) {
            super(RestClient.builder(), "http://unused", "unused");
            this.token = token;
        }

        @Override
        public String getToken() {
            return token;
        }
    }
}
