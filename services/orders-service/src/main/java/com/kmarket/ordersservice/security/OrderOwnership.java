package com.kmarket.ordersservice.security;

import com.kmarket.ordersservice.exception.ApiException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * Resource-level (ownership) authorization for orders, complementing the coarse
 * role rules in {@code SecurityConfig}.
 *
 * <p>auth-service mints JWTs whose {@code sub} claim is the user id and whose
 * {@code roles} claim lists the caller's roles (see {@code JwtService}). A regular
 * customer's {@code sub} must therefore match the {@code customer_id} of the order they
 * create, read or list. Back-office / Saga callers that legitimately act on behalf of
 * any customer bypass ownership checks (see {@link #PRIVILEGED_AUTHORITIES}).</p>
 */
@Component
public class OrderOwnership {

    /** Roles allowed to act on <em>any</em> customer's orders (Saga, analytics, logistics, admin). */
    private static final Set<String> PRIVILEGED_AUTHORITIES =
            Set.of("ROLE_admin", "ROLE_analyst", "ROLE_logistics", "ROLE_service_account");

    /** Roles allowed to create an order <em>for another</em> customer (Saga / admin tooling only). */
    private static final Set<String> CREATE_FOR_OTHERS_AUTHORITIES =
            Set.of("ROLE_admin", "ROLE_service_account");

    /**
     * Verifies that {@code authentication} is allowed to create an order for
     * {@code customerId}.
     *
     * @param authentication the current authenticated caller (never {@code null} on a
     *                       secured endpoint)
     * @param customerId     the {@code customer_id} from the request body
     * @throws ApiException {@code 403} if a non-privileged caller tries to order for
     *                      another customer, or the subject is not a usable id
     */
    public void assertCanCreateFor(Authentication authentication, Integer customerId) {
        if (customerId == null) {
            // Field-level @NotNull validation already covers this; guard defensively.
            throw ApiException.badRequest("customer_id is required");
        }
        if (hasAnyAuthority(authentication, CREATE_FOR_OTHERS_AUTHORITIES)) {
            return;
        }

        Integer subjectId = parseSubjectId(authentication);
        if (subjectId == null || !subjectId.equals(customerId)) {
            throw ApiException.forbidden(
                    "You can only create orders for your own account (customer_id " + customerId + ")");
        }
    }

    /**
     * Like single-order read ownership but denies a non-owner with {@code 404} instead of {@code 403} so a customer cannot even learn that another
     * customer's order exists (resource-existence non-disclosure). Privileged roles
     * ({@link #PRIVILEGED_AUTHORITIES}) may read any order.
     *
     * <p>Since {@code customer_id == auth user id == JWT sub}, the owner check compares the
     * subject directly with the order's {@code customer_id}.</p>
     *
     * @throws ApiException {@code 404} if a non-privileged caller reads another customer's order
     */
    public void assertCanReadOrNotFound(Authentication authentication, Integer ownerCustomerId, int orderId) {
        if (isPrivileged(authentication)) {
            return;
        }
        Integer subjectId = parseSubjectId(authentication);
        if (subjectId == null || !subjectId.equals(ownerCustomerId)) {
            throw ApiException.notFound("Order " + orderId + " not found");
        }
    }

    /**
     * Computes the {@code customer_id} filter to apply when listing orders.
     *
     * <ul>
     *   <li>Privileged roles: the requested {@code customerId} is honoured as-is
     *       ({@code null} means "all customers").</li>
     *   <li>Regular customers: the filter is forced to their own id, so they can only
     *       ever list their own orders; requesting another customer's id is a {@code 403}.</li>
     * </ul>
     *
     * @return the {@code customer_id} the service must filter on
     */
    public Integer effectiveCustomerFilter(Authentication authentication, Integer requestedCustomerId) {
        if (isPrivileged(authentication)) {
            return requestedCustomerId;
        }
        Integer subjectId = parseSubjectId(authentication);
        if (subjectId == null) {
            throw ApiException.forbidden("You can only list your own orders");
        }
        if (requestedCustomerId != null && !requestedCustomerId.equals(subjectId)) {
            throw ApiException.forbidden("You can only list your own orders");
        }
        return subjectId;
    }

    private boolean isPrivileged(Authentication authentication) {
        return hasAnyAuthority(authentication, PRIVILEGED_AUTHORITIES);
    }

    private boolean hasAnyAuthority(Authentication authentication, Set<String> wanted) {
        if (authentication == null) {
            return false;
        }
        for (GrantedAuthority authority : authentication.getAuthorities()) {
            if (wanted.contains(authority.getAuthority())) {
                return true;
            }
        }
        return false;
    }

    /** The JWT subject is the user id (a numeric string); returns {@code null} if unusable. */
    private Integer parseSubjectId(Authentication authentication) {
        if (authentication == null || authentication.getName() == null) {
            return null;
        }
        try {
            return Integer.valueOf(authentication.getName());
        } catch (NumberFormatException ex) {
            return null;
        }
    }
}
