package com.kmarket.paymentservice.service;

import com.kmarket.paymentservice.domain.PaymentStatus;
import com.kmarket.paymentservice.dto.PaymentCreateRequest;
import com.kmarket.paymentservice.dto.PaymentResponse;
import com.kmarket.paymentservice.entity.Payment;
import com.kmarket.paymentservice.exception.ApiException;
import com.kmarket.paymentservice.exception.PaymentDeclinedException;
import com.kmarket.paymentservice.repository.PaymentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Business logic for the payment contract: idempotent capture ({@code POST /payments}),
 * lookup ({@code GET /payments/{order_id}}) and refund ({@code POST /payments/{order_id}/refund}).
 *
 * <p><b>Idempotency.</b> Orders Service may retry after a network failure, so capture is
 * idempotent on the {@code Idempotency-Key} header:</p>
 * <ul>
 *   <li>same key + same request → the existing result is returned, nothing is re-charged;</li>
 *   <li>same key + different request → {@code 409} (contract conflict);</li>
 *   <li>concurrent requests with the same key → the {@code UNIQUE (idempotency_key)}
 *       constraint lets exactly one insert win; the loser catches the
 *       {@link DataIntegrityViolationException} and replays the winner's result.</li>
 * </ul>
 * The duplicate-prevention is therefore enforced in PostgreSQL, not only in Java.
 *
 * <p><b>Simulation.</b> No real provider: a payment strictly above the limit (200 € =
 * {@code 20000} cents) is deterministically declined and stored as {@code failed} with
 * {@code failure_reason = 'amount_over_limit'} (the value documented in the DB schema);
 * otherwise it succeeds. A decline is a business outcome → {@code 422}, never a 500.</p>
 *
 * <p><b>Transactions.</b> The outcome and its idempotency information are the same row, so
 * a single short local transaction persists them atomically. No external calls and no
 * distributed transaction are involved.</p>
 *
 * <p><b>Contract/schema note.</b> The {@code payments.payments} table has no
 * {@code request_hash} column, so the request fingerprint is the pair that is persisted
 * anyway — {@code (order_id, amount_cents)}. Replaying a key with a different order or
 * amount is detected by comparing those stored columns.</p>
 */
@Service
@RequiredArgsConstructor
public class PaymentService {

    /** DB value documented in {@code 04_payments.sql} for an over-limit decline. */
    private static final String REASON_AMOUNT_OVER_LIMIT = "amount_over_limit";

    private final PaymentRepository paymentRepository;

    /**
     * Self-reference used to invoke {@link #persistFailed} through the Spring proxy so its
     * {@code REQUIRES_NEW} transaction actually starts (a plain {@code this.persistFailed(...)}
     * call would bypass the transactional advice). Same pattern as orders-service.
     */
    @org.springframework.beans.factory.annotation.Autowired
    @org.springframework.context.annotation.Lazy
    private PaymentService self;

    /** The decline threshold in cents (200 € by default); payments strictly above it fail. */
    @Value("${payments.limit-cents:20000}")
    private int limitCents;

    /**
     * Captures a payment idempotently (contract step 3).
     *
     * @param idempotencyKey the {@code Idempotency-Key} header (already validated as a UUID)
     * @param request        the validated {@code PaymentCreate} body
     * @return the resulting {@code succeeded} payment (HTTP 201 on first capture / replay)
     * @throws ApiException              {@code 409} if the key was used for a different request
     * @throws PaymentDeclinedException  {@code 422} if the amount is over the limit
     */
    @Transactional
    public PaymentResponse capture(String idempotencyKey, PaymentCreateRequest request) {
        // 1. Idempotency replay: same key must mean the same request.
        var existing = paymentRepository.findByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) {
            return replayOrConflict(existing.get(), request);
        }

        // 2. Business rule: a payment strictly over the limit is declined. We still persist
        //    the attempt as 'failed' (so a retry with the same key replays the same failure)
        //    and surface it as a 422 business outcome.
        if (request.amountCents() > limitCents) {
            self.persistFailed(idempotencyKey, request);
            throw new PaymentDeclinedException(
                    "Amount " + request.amountCents() + " cents exceeds the limit", request.amountCents(), limitCents);
        }

        // 3. Simulated successful capture + idempotency record = one row, one transaction.
        Payment payment = new Payment();
        payment.setOrderId(request.orderId());
        payment.setIdempotencyKey(idempotencyKey);
        payment.setAmountCents(request.amountCents());
        payment.setStatus(PaymentStatus.SUCCEEDED.value());
        payment.setFailureReason(null);

        try {
            paymentRepository.saveAndFlush(payment);
        } catch (DataIntegrityViolationException ex) {
            // A concurrent request with the same key won the UNIQUE race: the transaction is
            // now aborted, so we do NOT touch the DB again here. The duplicate is reported as
            // a 409; the caller may safely re-issue the GET / the same POST to read the result.
            throw ApiException.conflict("A payment already exists for this Idempotency-Key");
        }
        return PaymentResponse.from(payment);
    }

    /**
     as 1 order can have multiple payment records -> only retrieve the lastest payment
     */
    @Transactional(readOnly = true)
    public PaymentResponse getByOrderId(int orderId) {
        return paymentRepository.findFirstByOrderIdOrderByIdDesc(orderId)
                .map(PaymentResponse::from)
                .orElseThrow(() -> ApiException.notFound("No payment found for order " + orderId));
    }

    /**
     * Refunds a succeeded payment ({@code POST /payments/{order_id}/refund}, Saga
     * compensation): flips {@code succeeded → refunded}.
     *
     * @throws ApiException {@code 404} if the order has no payment, {@code 409} if the
     *                      payment is not in a refundable ({@code succeeded}) state
     */
    @Transactional
    public PaymentResponse refund(int orderId) {
        Payment payment = paymentRepository.findFirstByOrderIdOrderByIdDesc(orderId)
                .orElseThrow(() -> ApiException.notFound("No payment found for order " + orderId));

        String status = payment.getStatus();
        if (PaymentStatus.REFUNDED.value().equals(status)) {
            throw ApiException.conflict("Payment for order " + orderId + " is already refunded");
        }
        if (!PaymentStatus.SUCCEEDED.value().equals(status)) {
            throw ApiException.conflict(
                    "Payment for order " + orderId + " cannot be refunded (status " + status + ")");
        }

        // CHECK (status = 'failed' OR failure_reason IS NULL): a refunded row must carry no reason.
        payment.setStatus(PaymentStatus.REFUNDED.value());
        payment.setFailureReason(null);
        paymentRepository.saveAndFlush(payment);
        return PaymentResponse.from(payment);
    }

    /**
     * Replays a previous outcome when the key already exists, or rejects the request with a
     * {@code 409} when the same key is reused for a different order/amount (the stored
     * {@code (order_id, amount_cents)} are the request fingerprint).
     */
    private PaymentResponse replayOrConflict(Payment existing, PaymentCreateRequest request) {
        boolean sameRequest = existing.getOrderId().equals(request.orderId())
                && existing.getAmountCents().equals(request.amountCents());
        if (!sameRequest) {
            throw ApiException.conflict("Idempotency-Key already used with a different request payload");
        }
        if (PaymentStatus.FAILED.value().equals(existing.getStatus())) {
            // The first attempt was declined; replay the same business outcome, not a 201.
            throw new PaymentDeclinedException(
                    "Amount " + existing.getAmountCents() + " cents exceeds the limit",
                    existing.getAmountCents(), limitCents);
        }
        return PaymentResponse.from(existing);
    }

    /**
     * Persists a declined attempt as {@code failed} in its own committed transaction so the
     * row survives even though {@link #capture} ultimately throws a {@code 422}. Keyed by the
     * idempotency key, so a retry replays the same failure instead of charging.
     */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    public void persistFailed(String idempotencyKey, PaymentCreateRequest request) {
        Payment payment = new Payment();
        payment.setOrderId(request.orderId());
        payment.setIdempotencyKey(idempotencyKey);
        payment.setAmountCents(request.amountCents());
        payment.setStatus(PaymentStatus.FAILED.value());
        payment.setFailureReason(REASON_AMOUNT_OVER_LIMIT);
        try {
            paymentRepository.saveAndFlush(payment);
        } catch (DataIntegrityViolationException ex) {
            // Concurrent duplicate for the same key: the other thread already recorded the
            // outcome. Nothing more to do; the 422 is still the correct answer for this request.
        }
    }
}
