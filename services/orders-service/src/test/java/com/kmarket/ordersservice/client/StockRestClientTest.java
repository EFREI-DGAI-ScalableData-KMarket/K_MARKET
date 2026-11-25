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
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

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
}
