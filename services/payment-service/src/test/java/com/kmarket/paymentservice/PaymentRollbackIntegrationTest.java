package com.kmarket.paymentservice;

import com.kmarket.paymentservice.dto.PaymentCreateRequest;
import com.kmarket.paymentservice.entity.Payment;
import com.kmarket.paymentservice.repository.PaymentRepository;
import com.kmarket.paymentservice.service.PaymentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

/**
 * Proves that a capture is written inside a single local transaction that rolls back
 * cleanly on failure: if persisting the successful payment throws, nothing is left behind
 * in PostgreSQL. Mirrors the orders-service {@code OutboxAtomicityIntegrationTest}.
 *
 * <p>Runs against a real (Testcontainers) PostgreSQL. The repository is spied so its
 * {@code saveAndFlush} fails mid-transaction - the only way the database can stay empty
 * afterwards is if the write participated in a transaction that was rolled back.</p>
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class PaymentRollbackIntegrationTest {

    @Autowired
    private PaymentService paymentService;

    @MockitoSpyBean
    private PaymentRepository paymentRepository;

    @BeforeEach
    void setUp() {
        paymentRepository.deleteAll();
    }

    @Test
    void capture_whenPersistFails_rollsBackTransaction() {
        // Force the INSERT of the succeeded payment to blow up.
        doThrow(new RuntimeException("boom: simulated persistence failure"))
                .when(paymentRepository).saveAndFlush(any(Payment.class));

        String key = UUID.randomUUID().toString();
        PaymentCreateRequest request = new PaymentCreateRequest(6_000, 4990, "EUR");

        assertThatThrownBy(() -> paymentService.capture(key, request))
                .isInstanceOf(RuntimeException.class);

        // The transaction rolled back: no partial payment row survived.
        assertThat(paymentRepository.count()).isZero();
    }
}
