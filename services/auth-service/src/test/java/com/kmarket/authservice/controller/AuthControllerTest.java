package com.kmarket.authservice.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kmarket.authservice.config.SecurityConfig;
import com.kmarket.authservice.entity.Role;
import com.kmarket.authservice.entity.User;
import com.kmarket.authservice.exception.ApiException;
import com.kmarket.authservice.security.JwtAuthenticationFilter;
import com.kmarket.authservice.security.JwtService;
import com.kmarket.authservice.service.AuthService;
import com.kmarket.authservice.dto.LoginRequest;
import com.kmarket.authservice.dto.RefreshRequest;
import com.kmarket.authservice.dto.TokenPair;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithAnonymousUser;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.OffsetDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Web-layer (API) tests for {@link AuthController}.
 *
 * <p>Uses the {@code @WebMvcTest} slice so only the MVC stack is loaded; the real
 * {@link SecurityConfig}/{@link JwtAuthenticationFilter}/{@link JwtService} are imported so the
 * security rules of the contract are exercised, while the {@link AuthService} collaborator is mocked.
 */
@WebMvcTest(AuthController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class, JwtService.class})
class AuthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @MockitoBean
    private AuthService authService;

    // ---------------------------------------------------------------------
    // POST /auth/login
    // ---------------------------------------------------------------------

    @Test
    @WithAnonymousUser
    void login_withValidCredentials_returnsTokenPair() throws Exception {
        TokenPair tokens = TokenPair.bearer("access-jwt", "refresh-opaque", 900);
        when(authService.login(any(LoginRequest.class))).thenReturn(tokens);

        LoginRequest body = new LoginRequest("user@example.com", "password123");

        mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.access_token").value("access-jwt"))
                .andExpect(jsonPath("$.refresh_token").value("refresh-opaque"))
                .andExpect(jsonPath("$.token_type").value("Bearer"))
                .andExpect(jsonPath("$.expires_in").value(900));
    }

    @Test
    @WithAnonymousUser
    void login_withBadCredentials_returnsUnauthorizedError() throws Exception {
        when(authService.login(any(LoginRequest.class)))
                .thenThrow(ApiException.unauthorized("Invalid credentials"));

        LoginRequest body = new LoginRequest("user@example.com", "wrong-password");

        mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
                .andExpect(jsonPath("$.message").value("Invalid credentials"));
    }

    @Test
    @WithAnonymousUser
    void login_withInvalidBody_returnsBadRequest() throws Exception {
        // Blank password + non-email address both violate the request constraints.
        LoginRequest body = new LoginRequest("not-an-email", "");

        mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"));

        verifyNoInteractions(authService);
    }

    // ---------------------------------------------------------------------
    // POST /auth/refresh
    // ---------------------------------------------------------------------

    @Test
    @WithAnonymousUser
    void refresh_withValidToken_returnsNewTokenPair() throws Exception {
        TokenPair tokens = TokenPair.bearer("new-access", "new-refresh", 900);
        when(authService.refresh(eq("refresh-opaque"))).thenReturn(tokens);

        RefreshRequest body = new RefreshRequest("refresh-opaque");

        mockMvc.perform(post("/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.access_token").value("new-access"))
                .andExpect(jsonPath("$.refresh_token").value("new-refresh"));

        verify(authService).refresh("refresh-opaque");
    }

    @Test
    @WithAnonymousUser
    void refresh_withBlankToken_returnsBadRequest() throws Exception {
        RefreshRequest body = new RefreshRequest("");

        mockMvc.perform(post("/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"));

        verifyNoInteractions(authService);
    }

    // ---------------------------------------------------------------------
    // POST /auth/token (API key exchange)
    // ---------------------------------------------------------------------

    @Test
    @WithAnonymousUser
    void token_withApiKeyHeader_returnsServiceToken() throws Exception {
        TokenPair tokens = TokenPair.bearer("service-jwt", null, 900);
        when(authService.exchangeApiKey(eq("secret-api-key"))).thenReturn(tokens);

        mockMvc.perform(post("/auth/token")
                        .header("X-API-Key", "secret-api-key"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.access_token").value("service-jwt"))
                .andExpect(jsonPath("$.token_type").value("Bearer"));

        verify(authService).exchangeApiKey("secret-api-key");
    }

    @Test
    @WithAnonymousUser
    void token_withoutApiKeyHeader_returnsUnauthorized() throws Exception {
        mockMvc.perform(post("/auth/token"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
                .andExpect(jsonPath("$.message").value("Missing X-API-Key header"));

        verifyNoInteractions(authService);
    }

    // ---------------------------------------------------------------------
    // GET /auth/me
    // ---------------------------------------------------------------------

    @Test
    void me_withoutAuthentication_returnsUnauthorized() throws Exception {
        mockMvc.perform(get("/auth/me"))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(authService);
    }

    @Test
    @WithMockUser(username = "42", roles = "admin")
    void me_withAuthenticatedUser_returnsProfile() throws Exception {
        when(authService.getById(42)).thenReturn(sampleUser(42, "user@example.com", "admin"));

        mockMvc.perform(get("/auth/me"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(42))
                .andExpect(jsonPath("$.email").value("user@example.com"))
                .andExpect(jsonPath("$.is_active").value(true))
                .andExpect(jsonPath("$.roles[0]").value("admin"));

        verify(authService).getById(42);
    }

    @Test
    @WithMockUser(username = "7", roles = "service_account")
    void me_withServiceAccount_returnsForbidden() throws Exception {
        mockMvc.perform(get("/auth/me"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        verifyNoInteractions(authService);
    }

    // ---------------------------------------------------------------------
    // POST /auth/logout
    // ---------------------------------------------------------------------

    @Test
    void logout_withoutAuthentication_returnsUnauthorized() throws Exception {
        mockMvc.perform(post("/auth/logout").with(csrf()))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(authService);
    }

    @Test
    @WithMockUser(username = "42", roles = "admin")
    void logout_withAuthenticatedUser_returnsNoContent() throws Exception {
        mockMvc.perform(post("/auth/logout").with(csrf()))
                .andExpect(status().isNoContent());

        verify(authService).logout(42);
    }

    @Test
    @WithMockUser(username = "7", roles = "service_account")
    void logout_withServiceAccount_returnsForbidden() throws Exception {
        mockMvc.perform(post("/auth/logout").with(csrf()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        verify(authService, org.mockito.Mockito.never()).logout(anyInt());
    }

    private static User sampleUser(Integer id, String email, String roleName) {
        Role role = new Role();
        role.setName(roleName);

        User user = new User();
        user.setId(id);
        user.setEmail(email);
        user.setActive(true);
        user.setCreatedAt(OffsetDateTime.now());
        user.setRoles(List.of(role));
        return user;
    }
}
