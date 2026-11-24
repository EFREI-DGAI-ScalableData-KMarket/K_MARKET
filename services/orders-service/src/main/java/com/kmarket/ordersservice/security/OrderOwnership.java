package com.kmarket.ordersservice.security;

import com.kmarket.ordersservice.exception.ApiException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * Enforces that a caller may only create an order <em>for themselves</em>.
 *
 * <p>auth-service mints JWTs whose {@code sub} claim is the user id and whose
 * {@code roles} claim lists the caller's roles (see {@code JwtService}). A regular
 * customer's {@code sub} must therefore match the {@code customer_id} in the request
 * body. Back-office / Saga callers that legitimately act on behalf of any customer
 * (roles {@code service_account}, {@code admin}) bypass the check.</p>
 */
@Component
public class OrderOwnership {

    /** Roles allowed to place an order for any customer (Saga, seeding, admin tooling). */
    private static final Set<String> PRIVILEGED_AUTHORITIES =
            Set.of("ROLE_service_account", "ROLE_admin");

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
        if (isPrivileged(authentication)) {
            return;
        }

        Integer subjectId = parseSubjectId(authentication);
        if (subjectId == null || !subjectId.equals(customerId)) {
            throw ApiException.forbidden(
                    "You can only create orders for your own account (customer_id " + customerId + ")");
        }
    }

    private boolean isPrivileged(Authentication authentication) {
        if (authentication == null) {
            return false;
        }
        for (GrantedAuthority authority : authentication.getAuthorities()) {
            if (PRIVILEGED_AUTHORITIES.contains(authority.getAuthority())) {
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
