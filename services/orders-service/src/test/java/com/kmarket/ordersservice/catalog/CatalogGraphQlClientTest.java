package com.kmarket.ordersservice.catalog;

import com.kmarket.ordersservice.client.ServiceTokenProvider;
import com.kmarket.ordersservice.exception.ApiException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.http.HttpMethod.POST;

/**
 * Unit tests for {@link CatalogGraphQlClient}: the GraphQL {@code productsByIds} response
 * is mapped correctly (active vs inactive, prices, names), the orders {@code service_account}
 * token is attached (not a forwarded user JWT), and failures surface as 502.
 */
class CatalogGraphQlClientTest {

    private static final String BASE_URL = "http://catalog-service:8004/v1/catalog";

    private CatalogGraphQlClient newClient(MockRestServiceServer[] serverHolder) {
        RestClient.Builder builder = RestClient.builder();
        serverHolder[0] = MockRestServiceServer.bindTo(builder).build();
        return new CatalogGraphQlClient(builder, BASE_URL, new FixedTokenProvider("svc-token"));
    }

    @Test
    void fetchByIds_mapsProductsAndActiveFlag() {
        MockRestServiceServer[] holder = new MockRestServiceServer[1];
        CatalogGraphQlClient client = newClient(holder);

        holder[0].expect(requestTo(BASE_URL))
                .andExpect(method(POST))
                .andExpect(header("Authorization", "Bearer svc-token"))
                .andRespond(withSuccess("""
                        {"data":{"productsByIds":[
                          {"product_id":101,"name":"Album A","price_cents":1990,"status":"active"},
                          {"product_id":202,"name":"Archived B","price_cents":990,"status":"inactive"}
                        ]}}""", MediaType.APPLICATION_JSON));

        Map<Integer, CatalogProduct> result = client.fetchByIds(List.of(101, 202));

        holder[0].verify();
        assertThat(result).hasSize(2);
        assertThat(result.get(101)).isEqualTo(new CatalogProduct(101, "Album A", 1990, true));
        assertThat(result.get(202).active()).isFalse();
    }

    @Test
    void fetchByIds_unknownIdsAreAbsent() {
        MockRestServiceServer[] holder = new MockRestServiceServer[1];
        CatalogGraphQlClient client = newClient(holder);

        holder[0].expect(requestTo(BASE_URL))
                .andRespond(withSuccess("{\"data\":{\"productsByIds\":[]}}", MediaType.APPLICATION_JSON));

        Map<Integer, CatalogProduct> result = client.fetchByIds(List.of(999));

        assertThat(result).isEmpty();
    }

    @Test
    void fetchByIds_graphQlErrors_throwUpstream() {
        MockRestServiceServer[] holder = new MockRestServiceServer[1];
        CatalogGraphQlClient client = newClient(holder);

        holder[0].expect(requestTo(BASE_URL))
                .andRespond(withSuccess("""
                        {"errors":[{"message":"boom","extensions":{"code":"VALIDATION_ERROR"}}]}""",
                        MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.fetchByIds(List.of(1)))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getStatus()).isEqualTo(HttpStatus.BAD_GATEWAY));
    }

    @Test
    void fetchByIds_transportError_throwsUpstream() {
        MockRestServiceServer[] holder = new MockRestServiceServer[1];
        CatalogGraphQlClient client = newClient(holder);

        holder[0].expect(requestTo(BASE_URL)).andRespond(withServerError());

        assertThatThrownBy(() -> client.fetchByIds(List.of(1)))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getStatus()).isEqualTo(HttpStatus.BAD_GATEWAY));
    }

    @Test
    void fetchByIds_emptyInput_skipsCall() {
        MockRestServiceServer[] holder = new MockRestServiceServer[1];
        CatalogGraphQlClient client = newClient(holder);

        // No server expectation set: a call would fail. An empty id set must not hit the network.
        assertThat(client.fetchByIds(List.of())).isEmpty();
        holder[0].verify();
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
