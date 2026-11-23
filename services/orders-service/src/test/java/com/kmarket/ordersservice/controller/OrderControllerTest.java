package com.kmarket.ordersservice.controller;

import com.kmarket.ordersservice.config.SecurityConfig;
import com.kmarket.ordersservice.dto.OrderDetailResponse;
import com.kmarket.ordersservice.dto.OrderListResponse;
import com.kmarket.ordersservice.dto.OrderResponse;
import com.kmarket.ordersservice.exception.ApiException;
import com.kmarket.ordersservice.exception.GlobalExceptionHandler;
import com.kmarket.ordersservice.service.OrderService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithAnonymousUser;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Web-layer tests for {@link OrderController#listOrders}.
 *
 * <p>Uses the {@code @WebMvcTest} slice so only the MVC stack is loaded; the real
 * {@link SecurityConfig} is imported so the Bearer-JWT rules of the contract are
 * exercised, while the {@link OrderService} collaborator is mocked.</p>
 */
@WebMvcTest(OrderController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class})
@TestPropertySource(properties = "app.jwt.secret=kmarket-local-development-jwt-secret-32chars")
class OrderControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private OrderService orderService;

    @Test
    @WithAnonymousUser
    void listOrders_withoutAuthentication_returnsUnauthorized() throws Exception {
        mockMvc.perform(get("/orders"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));

        verifyNoInteractions(orderService);
    }

    @Test
    @WithMockUser(username = "42", roles = "analyst")
    void listOrders_authenticated_returnsPaginatedList() throws Exception {
        OrderResponse order = new OrderResponse(1, 42, "paid", 4990,
                OffsetDateTime.parse("2024-01-01T10:00:00Z"),
                OffsetDateTime.parse("2024-01-01T10:05:00Z"));
        when(orderService.listOrders(isNull(), isNull(), eq(50), eq(0)))
                .thenReturn(new OrderListResponse(List.of(order), 1));

        mockMvc.perform(get("/orders"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].id").value(1))
                .andExpect(jsonPath("$.items[0].customer_id").value(42))
                .andExpect(jsonPath("$.items[0].status").value("paid"))
                .andExpect(jsonPath("$.items[0].total_cents").value(4990));

        verify(orderService).listOrders(null, null, 50, 0);
    }

    @Test
    @WithMockUser(username = "42", roles = "analyst")
    void listOrders_passesFiltersAndClampsLimit() throws Exception {
        when(orderService.listOrders(eq("paid"), eq(7), eq(200), eq(10)))
                .thenReturn(new OrderListResponse(List.of(), 0));

        // limit=500 must be clamped to the contract maximum of 200
        mockMvc.perform(get("/orders")
                        .param("status", "paid")
                        .param("customer_id", "7")
                        .param("limit", "500")
                        .param("offset", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(0));

        verify(orderService).listOrders("paid", 7, 200, 10);
    }

    @Test
    @WithMockUser(username = "42", roles = "analyst")
    void listOrders_withInvalidLimit_returnsBadRequest() throws Exception {
        mockMvc.perform(get("/orders").param("limit", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"));

        verifyNoInteractions(orderService);
    }

    // ---------------------------------------------------------------------
    // POST /orders
    // ---------------------------------------------------------------------

    private static final String BODY = """
            {"customer_id":42,"items":[{"product_id":101,"quantity":2}]}""";

    @Test
    @WithAnonymousUser
    void createOrder_withoutAuthentication_returnsUnauthorized() throws Exception {
        mockMvc.perform(post("/orders")
                        .with(SecurityMockMvcRequestPostProcessors.csrf())
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(orderService);
    }

    @Test
    @WithMockUser(username = "42", roles = "analyst")
    void createOrder_valid_returnsCreated() throws Exception {
        String key = UUID.randomUUID().toString();
        OrderResponse created = new OrderResponse(99, 42, "paid", 1980,
                OffsetDateTime.parse("2024-01-01T10:00:00Z"),
                OffsetDateTime.parse("2024-01-01T10:00:00Z"));
        when(orderService.createOrder(eq(key), any())).thenReturn(created);

        mockMvc.perform(post("/orders")
                        .with(SecurityMockMvcRequestPostProcessors.csrf())
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(99))
                .andExpect(jsonPath("$.customer_id").value(42))
                .andExpect(jsonPath("$.status").value("paid"))
                .andExpect(jsonPath("$.total_cents").value(1980));

        verify(orderService).createOrder(eq(key), any());
    }

    @Test
    @WithMockUser(username = "42", roles = "analyst")
    void createOrder_missingIdempotencyKey_returnsBadRequest() throws Exception {
        mockMvc.perform(post("/orders")
                        .with(SecurityMockMvcRequestPostProcessors.csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"));

        verifyNoInteractions(orderService);
    }

    @Test
    @WithMockUser(username = "42", roles = "analyst")
    void createOrder_nonUuidKey_returnsBadRequest() throws Exception {
        mockMvc.perform(post("/orders")
                        .with(SecurityMockMvcRequestPostProcessors.csrf())
                        .header("Idempotency-Key", "not-a-uuid")
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"));

        verifyNoInteractions(orderService);
    }

    @Test
    @WithMockUser(username = "42", roles = "analyst")
    void createOrder_emptyItems_returnsBadRequest() throws Exception {
        mockMvc.perform(post("/orders")
                        .with(SecurityMockMvcRequestPostProcessors.csrf())
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"customer_id":42,"items":[]}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"));

        verifyNoInteractions(orderService);
    }

    // ---------------------------------------------------------------------
    // GET /orders/{order_id}
    // ---------------------------------------------------------------------

    @Test
    @WithAnonymousUser
    void getOrder_withoutAuthentication_returnsUnauthorized() throws Exception {
        mockMvc.perform(get("/orders/1"))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(orderService);
    }

    @Test
    @WithMockUser(username = "42", roles = "analyst")
    void getOrder_authenticated_returnsDetail() throws Exception {
        OrderDetailResponse detail = new OrderDetailResponse(
                7, 42, "paid", 1980,
                OffsetDateTime.parse("2024-01-01T10:00:00Z"),
                OffsetDateTime.parse("2024-01-01T10:00:00Z"),
                new OrderDetailResponse.Customer(42, "Alice", "alice@market.test"),
                List.of(new OrderDetailResponse.Item(101, "Product #101", 2, 990, 1980)));
        when(orderService.getOrder(7)).thenReturn(detail);

        mockMvc.perform(get("/orders/7"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(7))
                .andExpect(jsonPath("$.customer_id").value(42))
                .andExpect(jsonPath("$.status").value("paid"))
                .andExpect(jsonPath("$.customer.name").value("Alice"))
                .andExpect(jsonPath("$.items[0].product_id").value(101))
                .andExpect(jsonPath("$.items[0].product_name").value("Product #101"))
                .andExpect(jsonPath("$.items[0].line_total_cents").value(1980));

        verify(orderService).getOrder(7);
    }

    @Test
    @WithMockUser(username = "42", roles = "analyst")
    void getOrder_unknown_returnsNotFound() throws Exception {
        when(orderService.getOrder(999)).thenThrow(ApiException.notFound("Order 999 not found"));

        mockMvc.perform(get("/orders/999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    // ---------------------------------------------------------------------
    // PATCH /orders/{order_id}/status
    // ---------------------------------------------------------------------

    @Test
    @WithAnonymousUser
    void updateStatus_withoutAuthentication_returnsUnauthorized() throws Exception {
        mockMvc.perform(patch("/orders/1/status")
                        .with(SecurityMockMvcRequestPostProcessors.csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"preparing\"}"))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(orderService);
    }

    @Test
    @WithMockUser(username = "42", roles = "service_account")
    void updateStatus_valid_returnsUpdatedOrder() throws Exception {
        OrderResponse updated = new OrderResponse(7, 42, "preparing", 1980,
                OffsetDateTime.parse("2024-01-01T10:00:00Z"),
                OffsetDateTime.parse("2024-01-01T11:00:00Z"));
        when(orderService.updateStatus(eq(7), eq("preparing"))).thenReturn(updated);

        mockMvc.perform(patch("/orders/7/status")
                        .with(SecurityMockMvcRequestPostProcessors.csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"preparing\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(7))
                .andExpect(jsonPath("$.status").value("preparing"));

        verify(orderService).updateStatus(7, "preparing");
    }

    @Test
    @WithMockUser(username = "42", roles = "service_account")
    void updateStatus_invalidTransition_returnsConflict() throws Exception {
        when(orderService.updateStatus(eq(7), eq("pending")))
                .thenThrow(new ApiException(HttpStatus.CONFLICT, "CONFLICT",
                        "Invalid status transition: delivered -> pending"));

        mockMvc.perform(patch("/orders/7/status")
                        .with(SecurityMockMvcRequestPostProcessors.csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"pending\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"));
    }

    @Test
    @WithMockUser(username = "42", roles = "service_account")
    void updateStatus_blankStatus_returnsBadRequest() throws Exception {
        mockMvc.perform(patch("/orders/7/status")
                        .with(SecurityMockMvcRequestPostProcessors.csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"));

        verifyNoInteractions(orderService);
    }

}
