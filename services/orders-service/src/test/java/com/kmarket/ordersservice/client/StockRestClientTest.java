package com.kmarket.ordersservice.client;

import com.kmarket.ordersservice.entity.OrderItem;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpMethod.POST;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withResourceNotFound;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

/**
 * Unit tests for {@link StockRestClient}: mapping of the {@code POST /stock/reserve}
 * response to a {@link StockReservationOutcome}, and that the service token is attached.
 */
class StockRestClientTest {

    private static final String BASE_URL = "http://stock-service:8002/v1";

    private StockRestClient newClient(MockRestServiceServer[] holder) {
        RestClient.Builder builder = RestClient.builder();
        holder[0] = MockRestServiceServer.bindTo(builder).build();
        return new StockRestClient(builder, BASE_URL, new FixedTokenProvider("svc-token"));
    }

    @Test
    void reserve_created_isReserved() {
        MockRestServiceServer[] holder = new MockRestServiceServer[1];
        StockRestClient client = newClient(holder);

        holder[0].expect(requestTo(BASE_URL + "/stock/reserve"))
                .andExpect(method(POST))
                .andExpect(header("Authorization", "Bearer svc-token"))
                .andRespond(withStatus(org.springframework.http.HttpStatus.CREATED)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"order_id\":7,\"reservations\":[]}"));

        assertThat(client.reserve(7, List.of(line(101, 2))))
                .isEqualTo(StockReservationOutcome.RESERVED);
        holder[0].verify();
    }

    @Test
    void reserve_unprocessable_isInsufficient() {
        MockRestServiceServer[] holder = new MockRestServiceServer[1];
        StockRestClient client = newClient(holder);

        holder[0].expect(requestTo(BASE_URL + "/stock/reserve"))
                .andRespond(withStatus(org.springframework.http.HttpStatus.UNPROCESSABLE_CONTENT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"code\":\"INSUFFICIENT_STOCK\",\"message\":\"nope\",\"details\":[]}"));

        assertThat(client.reserve(7, List.of(line(101, 99))))
                .isEqualTo(StockReservationOutcome.INSUFFICIENT);
    }

    @Test
    void reserve_otherStatus_isUncertain() {
        MockRestServiceServer[] holder = new MockRestServiceServer[1];
        StockRestClient client = newClient(holder);

        holder[0].expect(requestTo(BASE_URL + "/stock/reserve"))
                .andRespond(withResourceNotFound());

        assertThat(client.reserve(7, List.of(line(101, 1))))
                .isEqualTo(StockReservationOutcome.UNCERTAIN);
    }

    @Test
    void reserve_transportError_isUncertain() {
        MockRestServiceServer[] holder = new MockRestServiceServer[1];
        StockRestClient client = newClient(holder);

        holder[0].expect(requestTo(BASE_URL + "/stock/reserve"))
                .andRespond(request -> {
                    throw new java.io.IOException("connection reset / timeout");
                });

        assertThat(client.reserve(7, List.of(line(101, 1))))
                .isEqualTo(StockReservationOutcome.UNCERTAIN);
    }

    @Test
    void reserve_serverError500_isUncertain_notInsufficient() {
        // Regression: the real-world bug was Stock returning HTTP 500 (inventory.outbox missing).
        // A technical error must be UNCERTAIN (order stays pending), never INSUFFICIENT (rejected).
        MockRestServiceServer[] holder = new MockRestServiceServer[1];
        StockRestClient client = newClient(holder);

        holder[0].expect(requestTo(BASE_URL + "/stock/reserve"))
                .andRespond(withStatus(org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"code\":\"INTERNAL_ERROR\",\"message\":\"relation \\\"inventory.outbox\\\" does not exist\"}"));

        StockReservationOutcome outcome = client.reserve(7, List.of(line(101, 1)));
        assertThat(outcome).isEqualTo(StockReservationOutcome.UNCERTAIN);
        assertThat(outcome).isNotEqualTo(StockReservationOutcome.INSUFFICIENT);
    }

    @Test
    void reserve_serviceUnavailable503_isUncertain() {
        MockRestServiceServer[] holder = new MockRestServiceServer[1];
        StockRestClient client = newClient(holder);

        holder[0].expect(requestTo(BASE_URL + "/stock/reserve"))
                .andRespond(withStatus(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE));

        assertThat(client.reserve(7, List.of(line(101, 1))))
                .isEqualTo(StockReservationOutcome.UNCERTAIN);
    }

    @Test
    void reserve_unauthorized401_isUncertain() {
        // An expired/invalid service token is a technical problem, not insufficient stock.
        MockRestServiceServer[] holder = new MockRestServiceServer[1];
        StockRestClient client = newClient(holder);

        holder[0].expect(requestTo(BASE_URL + "/stock/reserve"))
                .andRespond(withStatus(org.springframework.http.HttpStatus.UNAUTHORIZED)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"code\":\"UNAUTHORIZED\",\"message\":\"Jeton absent\"}"));

        assertThat(client.reserve(7, List.of(line(101, 1))))
                .isEqualTo(StockReservationOutcome.UNCERTAIN);
    }

    @Test
    void reserve_forbidden403_isUncertain() {
        MockRestServiceServer[] holder = new MockRestServiceServer[1];
        StockRestClient client = newClient(holder);

        holder[0].expect(requestTo(BASE_URL + "/stock/reserve"))
                .andRespond(withStatus(org.springframework.http.HttpStatus.FORBIDDEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"code\":\"FORBIDDEN\",\"message\":\"Rôle insuffisant\"}"));

        assertThat(client.reserve(7, List.of(line(101, 1))))
                .isEqualTo(StockReservationOutcome.UNCERTAIN);
    }

    @Test
    void reserve_badRequest400_isUncertain() {
        // A 400 means our request was malformed — a bug to fix, but stock was NOT reserved and
        // this is NOT "insufficient". Keep it UNCERTAIN so the order is not wrongly rejected.
        MockRestServiceServer[] holder = new MockRestServiceServer[1];
        StockRestClient client = newClient(holder);

        holder[0].expect(requestTo(BASE_URL + "/stock/reserve"))
                .andRespond(withStatus(org.springframework.http.HttpStatus.BAD_REQUEST)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"code\":\"BAD_REQUEST\",\"message\":\"Requête invalide\"}"));

        assertThat(client.reserve(7, List.of(line(101, 1))))
                .isEqualTo(StockReservationOutcome.UNCERTAIN);
    }

    @Test
    void reserve_tokenAcquisitionFails_isUncertain_withoutCallingStock() {
        // auth-service down / API key rejected: no reserve is sent, outcome is UNCERTAIN.
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        StockRestClient client = new StockRestClient(builder, BASE_URL, new ThrowingTokenProvider());

        assertThat(client.reserve(7, List.of(line(101, 1))))
                .isEqualTo(StockReservationOutcome.UNCERTAIN);
        // No HTTP expectation was registered: proves Stock was never called.
        server.verify();
    }

    private static OrderItem line(int productId, int quantity) {
        OrderItem item = new OrderItem();
        item.setProductId(productId);
        item.setQuantity(quantity);
        item.setUnitPriceCents(1000);
        return item;
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

    /** A {@link ServiceTokenProvider} that fails to mint a token (auth down / key rejected). */
    private static final class ThrowingTokenProvider extends ServiceTokenProvider {
        ThrowingTokenProvider() {
            super(RestClient.builder(), "http://unused", "unused");
        }

        @Override
        public String getToken() {
            throw com.kmarket.ordersservice.exception.ApiException.upstream("auth-service unreachable");
        }
    }
}
