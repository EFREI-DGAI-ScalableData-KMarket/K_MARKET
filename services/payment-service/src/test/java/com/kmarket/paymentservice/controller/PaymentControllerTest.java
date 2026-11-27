package com.kmarket.paymentservice.controller;

import com.kmarket.paymentservice.config.SecurityConfig;
import com.kmarket.paymentservice.dto.PaymentResponse;
import com.kmarket.paymentservice.exception.ApiException;
import com.kmarket.paymentservice.exception.GlobalExceptionHandler;
import com.kmarket.paymentservice.exception.PaymentDeclinedException;
import com.kmarket.paymentservice.service.PaymentService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithAnonymousUser;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.OffsetDateTime;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Web-layer tests for {@link PaymentController}.
 *
 * <p>Uses the {@code @WebMvcTest} slice so only the MVC stack is loaded; the real
 * {@link SecurityConfig} and {@link GlobalExceptionHandler} are imported so the Bearer-JWT
 * rules and the contract error mapping are exercised, while the {@link PaymentService}
 * collaborator is mocked (same approach as {@code OrderControllerTest}).</p>
 */
@WebMvcTest(PaymentController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class})
@TestPropertySource(properties = "app.jwt.secret=kmarket-local-development-jwt-secret-32chars")
class PaymentControllerTest {

    /** A valid {@code PaymentCreate} body: order 1234, 49,90 euros (<= 200 euros -> would succeed). */
    private static final String VALID_BODY = """
            {"order_id":1234,"amount_cents":4990,"currency":"EUR"}""";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PaymentService paymentService;

    private static PaymentResponse succeeded(int id, int orderId, int amountCents) {
        return new PaymentResponse(id, orderId, amountCents, "EUR", "succeeded", null,
                OffsetDateTime.parse("2024-01-01T10:00:00Z"), null);
    }

    // ---------------------------------------------------------------------
    // POST /payments - happy path
    // ---------------------------------------------------------------------

    @Test
    @WithMockUser(username = "svc", roles = "service_account")
    void createPayment_valid_returnsCreated() throws Exception {
        String key = UUID.randomUUID().toString();
        when(paymentService.capture(eq(key), any())).thenReturn(succeeded(1, 1234, 4990));

        mockMvc.perform(post("/payments")
                        .with(SecurityMockMvcRequestPostProcessors.csrf())
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.order_id").value(1234))
                .andExpect(jsonPath("$.amount_cents").value(4990))
                .andExpect(jsonPath("$.currency").value("EUR"))
                .andExpect(jsonPath("$.status").value("succeeded"));

        verify(paymentService).capture(eq(key), any());
    }

    @Test
    @WithMockUser(username = "admin", roles = "admin")
    void createPayment_adminRole_isAllowed() throws Exception {
        String key = UUID.randomUUID().toString();
        when(paymentService.capture(eq(key), any())).thenReturn(succeeded(2, 1234, 4990));

        mockMvc.perform(post("/payments")
                        .with(SecurityMockMvcRequestPostProcessors.csrf())
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isCreated());

        verify(paymentService).capture(eq(key), any());
    }

    // ---------------------------------------------------------------------
    // POST /payments - declined above 200 euros (business outcome -> 422)
    // ---------------------------------------------------------------------

    @Test
    @WithMockUser(username = "svc", roles = "service_account")
    void createPayment_amountOverLimit_returnsUnprocessable() throws Exception {
        String key = UUID.randomUUID().toString();
        when(paymentService.capture(eq(key), any()))
                .thenThrow(new PaymentDeclinedException("Amount 25000 cents exceeds the limit",
                        25000, 20000));

        mockMvc.perform(post("/payments")
                        .with(SecurityMockMvcRequestPostProcessors.csrf())
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"order_id":1234,"amount_cents":25000}"""))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("PAYMENT_FAILED"))
                .andExpect(jsonPath("$.reason").value("amount_exceeds_limit"))
                .andExpect(jsonPath("$.amount_cents").value(25000))
                .andExpect(jsonPath("$.limit_cents").value(20000));
    }

    // ---------------------------------------------------------------------
    // POST /payments - invalid amount / currency / missing fields -> 400
    // ---------------------------------------------------------------------

    @Test
    @WithMockUser(username = "svc", roles = "service_account")
    void createPayment_invalidAmount_returnsBadRequest() throws Exception {
        // amount_cents must be >= 1 (@Min(1)); 0 is invalid.
        mockMvc.perform(post("/payments")
                        .with(SecurityMockMvcRequestPostProcessors.csrf())
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"order_id":1234,"amount_cents":0}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"));

        verifyNoInteractions(paymentService);
    }

    @Test
    @WithMockUser(username = "svc", roles = "service_account")
    void createPayment_invalidCurrency_returnsBadRequest() throws Exception {
        // currency is restricted to EUR by the contract (@Pattern).
        mockMvc.perform(post("/payments")
                        .with(SecurityMockMvcRequestPostProcessors.csrf())
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"order_id":1234,"amount_cents":4990,"currency":"USD"}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"));

        verifyNoInteractions(paymentService);
    }

    @Test
    @WithMockUser(username = "svc", roles = "service_account")
    void createPayment_missingRequiredFields_returnsBadRequest() throws Exception {
        // order_id and amount_cents are both @NotNull; an empty body violates both.
        mockMvc.perform(post("/payments")
                        .with(SecurityMockMvcRequestPostProcessors.csrf())
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"));

        verifyNoInteractions(paymentService);
    }

    // ---------------------------------------------------------------------
    // POST /payments - Idempotency-Key header validation -> 400
    // ---------------------------------------------------------------------

    @Test
    @WithMockUser(username = "svc", roles = "service_account")
    void createPayment_missingIdempotencyKey_returnsBadRequest() throws Exception {
        mockMvc.perform(post("/payments")
                        .with(SecurityMockMvcRequestPostProcessors.csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"));

        verifyNoInteractions(paymentService);
    }

    @Test
    @WithMockUser(username = "svc", roles = "service_account")
    void createPayment_nonUuidIdempotencyKey_returnsBadRequest() throws Exception {
        mockMvc.perform(post("/payments")
                        .with(SecurityMockMvcRequestPostProcessors.csrf())
                        .header("Idempotency-Key", "not-a-uuid")
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"));

        verifyNoInteractions(paymentService);
    }

    // ---------------------------------------------------------------------
    // POST /payments - authentication / authorization
    // ---------------------------------------------------------------------

    @Test
    @WithAnonymousUser
    void createPayment_withoutAuthentication_returnsUnauthorized() throws Exception {
        mockMvc.perform(post("/payments")
                        .with(SecurityMockMvcRequestPostProcessors.csrf())
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));

        verifyNoInteractions(paymentService);
    }

    @Test
    @WithMockUser(username = "42", roles = "analyst")
    void createPayment_insufficientRole_returnsForbidden() throws Exception {
        // analyst is read-only back-office; it cannot charge a payment (role rule).
        mockMvc.perform(post("/payments")
                        .with(SecurityMockMvcRequestPostProcessors.csrf())
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        verifyNoInteractions(paymentService);
    }

    // ---------------------------------------------------------------------
    // GET /payments/{order_id} - retrieval, not-found, auth
    // ---------------------------------------------------------------------

    @Test
    @WithMockUser(username = "svc", roles = "service_account")
    void getPayment_existing_returnsPayment() throws Exception {
        when(paymentService.getByOrderId(1234)).thenReturn(succeeded(7, 1234, 4990));

        mockMvc.perform(get("/payments/1234"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(7))
                .andExpect(jsonPath("$.order_id").value(1234))
                .andExpect(jsonPath("$.status").value("succeeded"));

        verify(paymentService).getByOrderId(1234);
    }

    @Test
    @WithMockUser(username = "42", roles = "analyst")
    void getPayment_analystReadRole_isAllowed() throws Exception {
        when(paymentService.getByOrderId(1234)).thenReturn(succeeded(7, 1234, 4990));

        mockMvc.perform(get("/payments/1234"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(7));

        verify(paymentService).getByOrderId(1234);
    }

    @Test
    @WithMockUser(username = "svc", roles = "service_account")
    void getPayment_unknownOrder_returnsNotFound() throws Exception {
        when(paymentService.getByOrderId(999))
                .thenThrow(ApiException.notFound("No payment found for order 999"));

        mockMvc.perform(get("/payments/999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));

        verify(paymentService).getByOrderId(999);
    }

    @Test
    @WithAnonymousUser
    void getPayment_withoutAuthentication_returnsUnauthorized() throws Exception {
        mockMvc.perform(get("/payments/1234"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));

        verifyNoInteractions(paymentService);
    }

    @Test
    @WithMockUser(username = "7", roles = "catalog_manager")
    void getPayment_insufficientRole_returnsForbidden() throws Exception {
        // catalog_manager is not part of the payment read roles.
        mockMvc.perform(get("/payments/1234"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        verify(paymentService, org.mockito.Mockito.never()).getByOrderId(anyInt());
    }
}
