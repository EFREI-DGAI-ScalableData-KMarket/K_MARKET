# Intégrer Orders Service au Payment Service

Ce guide explique comment `orders-service` (port `8001`) doit appeler
`payment-service` (port `8003`) pour l'**étape 3 de la Saga** : encaisser une
commande après réservation du stock, puis la rembourser en compensation si la
commande échoue.

Il reprend **exactement** le patron déjà en place pour l'intégration
Orders → Stock (`StockClient` / `StockRestClient` + `ServiceTokenProvider`),
afin de rester homogène avec le reste de la base de code.

> Au moment de la rédaction, `orders-service` appelle déjà `stock-service` mais
> **pas encore** `payment-service`. Ce document décrit le câblage à ajouter.

---

## 1. Vue d'ensemble de la Saga

```
POST /v1/orders  (orders-service)
  │
  ├─ Tx1  persister la commande 'pending' (+ idempotence)
  │
  ├─ Étape 2  POST /v1/stock/reserve   (stock-service, JWT service_account)
  │             201 → réservé | 422 → insuffisant | timeout → UNCERTAIN
  │
  ├─ Étape 3  POST /v1/payments        (payment-service, JWT service_account)   ← CE GUIDE
  │             201 → succeeded | 422 → refusé | 409 → clé déjà utilisée
  │
  ├─ Tx2  transition de statut : paid / payment_failed / stock_reserved …
  │
  └─ Compensation (si annulation après paiement) :
        POST /v1/payments/{order_id}/refund   (payment-service)   ← CE GUIDE
        POST /v1/stock/release                (stock-service)
```

Règles clés :

- Orders Service s'authentifie avec sa **propre identité technique**
  (`service_account`), **jamais** le token de l'utilisateur final (décision D6).
- L'appel de paiement est fait **hors transaction base** (comme l'appel stock) :
  une transaction locale courte persiste la commande, l'appel HTTP a lieu entre
  deux transactions, puis une seconde transaction applique le statut.
- Chaque appel `POST /payments` porte un `Idempotency-Key` **stable par commande**
  pour qu'un retry ne débite jamais deux fois.

---

## 2. Variables d'environnement à ajouter à orders-service

Dans `docker-compose.yml`, service `orders-service`, ajoute (même style que
`STOCK_BASE_URL`) :

```yaml
    environment:
      # … existant (ORDERS_DB_*, JWT_SECRET, STOCK_BASE_URL, AUTH_BASE_URL, ORDERS_API_KEY) …
      # Service Paiement (REST) — étape 3 de la Saga.
      PAYMENTS_BASE_URL: http://payment-service:8003/v1
    depends_on:
      # … existant …
      payment-service:
        condition: service_started
```

Et dans `services/orders-service/src/main/resources/application.properties` :

```properties
# Payment service (REST) — Saga step 3: capture payment on POST /orders.
# orders-service reuses its service_account JWT (ORDERS_API_KEY → auth /auth/token).
# When PAYMENTS_BASE_URL is unset, the capture step is skipped (order stays 'stock_reserved').
payments.base-url=${PAYMENTS_BASE_URL:}
```

> `payment-service` exige le rôle `service_account` ou `admin` sur
> `POST /payments` et `POST /payments/{order_id}/refund` — exactement les rôles
> déjà obtenus par orders-service pour appeler Stock. **Aucune nouvelle clé d'API
> n'est nécessaire** : le `ServiceTokenProvider` existant fournit déjà le bon JWT.

---

## 3. Client Java à ajouter dans orders-service

Crée le seam `PaymentClient` + implémentation REST, calqué sur `StockClient` /
`StockRestClient`. Il réutilise le `ServiceTokenProvider` déjà présent.

`PaymentClient.java` (interface) :

```java
package com.kmarket.ordersservice.client;

/** Capture a payment for an order (Saga step 3). */
public interface PaymentClient {
    PaymentOutcome capture(int orderId, long amountCents, String idempotencyKey);
    void refund(int orderId); // compensation
}
```

`PaymentOutcome.java` :

```java
package com.kmarket.ordersservice.client;

/** Classification of a POST /payments call, mirroring StockReservationOutcome. */
public enum PaymentOutcome {
    SUCCEEDED,   // HTTP 201 (or idempotent replay of a success)
    DECLINED,    // HTTP 422 (amount over limit / card declined)
    UNCERTAIN    // timeout / 5xx / transport error → reconcile, do NOT mark failed
}
```

`PaymentRestClient.java` — même structure que `StockRestClient` :

```java
package com.kmarket.ordersservice.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

import java.util.Map;

public class PaymentRestClient implements PaymentClient {

    private static final Logger log = LoggerFactory.getLogger(PaymentRestClient.class);

    private final RestClient restClient;
    private final ServiceTokenProvider tokenProvider;

    public PaymentRestClient(RestClient.Builder builder, String baseUrl, ServiceTokenProvider tokenProvider) {
        this.restClient = builder.baseUrl(baseUrl).build();
        this.tokenProvider = tokenProvider;
    }

    @Override
    public PaymentOutcome capture(int orderId, long amountCents, String idempotencyKey) {
        String token;
        try {
            token = tokenProvider.getToken();
        } catch (RuntimeException ex) {
            log.warn("Payment capture for order {} aborted: no service token", orderId, ex);
            return PaymentOutcome.UNCERTAIN;
        }

        Map<String, Object> body = Map.of(
                "order_id", orderId, "amount_cents", amountCents, "currency", "EUR");
        try {
            return restClient.post()
                    .uri("/payments")
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("Authorization", "Bearer " + token)
                    .header("Idempotency-Key", idempotencyKey)  // UUID stable par commande
                    .body(body)
                    .exchange((request, response) -> {
                        HttpStatus status = HttpStatus.resolve(response.getStatusCode().value());
                        if (status == HttpStatus.CREATED) {
                            return PaymentOutcome.SUCCEEDED;
                        }
                        if (status == HttpStatus.CONFLICT) {
                            // 409: a payment already exists for this key → read it back.
                            return PaymentOutcome.SUCCEEDED;
                        }
                        if (status == HttpStatus.UNPROCESSABLE_CONTENT) {
                            return PaymentOutcome.DECLINED;
                        }
                        log.warn("Payment capture for order {} returned {}", orderId, response.getStatusCode());
                        return PaymentOutcome.UNCERTAIN;
                    });
        } catch (Exception ex) {
            log.warn("Payment capture for order {} failed (treated as UNCERTAIN)", orderId, ex);
            return PaymentOutcome.UNCERTAIN;
        }
    }

    @Override
    public void refund(int orderId) {
        String token = tokenProvider.getToken();
        restClient.post()
                .uri("/payments/{orderId}/refund", orderId)
                .header("Authorization", "Bearer " + token)
                .retrieve()
                .toBodilessEntity();
    }
}
```


`PaymentConfig.java` — configuration conditionnelle, comme `StockConfig` :

```java
package com.kmarket.ordersservice.config;

import com.kmarket.ordersservice.client.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
public class PaymentConfig {

    private static final Logger log = LoggerFactory.getLogger(PaymentConfig.class);

    @Bean
    @ConditionalOnMissingBean(PaymentClient.class)
    public PaymentClient paymentClient(ObjectProvider<RestClient.Builder> builders, Environment env) {
        String base = env.getProperty("payments.base-url", "").trim();
        if (base.isEmpty()) {
            log.warn("payments.base-url not set: capture step skipped. Set PAYMENTS_BASE_URL to enable it.");
            return new UnconfiguredPaymentClient(); // no-op → order stays 'stock_reserved'
        }
        String authBaseUrl = env.getProperty("auth.base-url", "").trim();
        String apiKey = env.getProperty("orders.api-key", "").trim();

        SimpleClientHttpRequestFactory rf = new SimpleClientHttpRequestFactory();
        rf.setConnectTimeout(2000);
        rf.setReadTimeout(5000);
        RestClient.Builder b = builders.getIfAvailable(RestClient::builder).clone().requestFactory(rf);

        ServiceTokenProvider tokenProvider = new ServiceTokenProvider(b, authBaseUrl, apiKey);
        return new PaymentRestClient(b, base, tokenProvider);
    }
}
```

> `UnconfiguredPaymentClient` est l'équivalent de `UnconfiguredStockClient` :
> il renvoie `UNCERTAIN` (ou ne fait rien pour `refund`) quand le service n'est
> pas configuré, afin que l'app tourne en autonomie dans les tests.

---

## 4. Brancher la capture dans la Saga (`OrderService`)

Après l'étape stock réussie (`RESERVED`), appelle la capture **hors transaction**,
puis applique le statut dans une transaction dédiée (méthode `transition(...)`
existante). Clé d'idempotence **stable par commande** :

```java
// Dans OrderService.createOrder(...), après stock RESERVED :
String payKey = UUID.nameUUIDFromBytes(("order-" + orderId).getBytes(UTF_8)).toString();
PaymentOutcome outcome = paymentClient.capture(orderId, order.getTotalCents(), payKey);

switch (outcome) {
    case SUCCEEDED   -> self.transition(orderId, OrderStatus.PAID);
    case DECLINED    -> self.transition(orderId, OrderStatus.PAYMENT_FAILED);
    case UNCERTAIN   -> { /* laisser 'stock_reserved' pour réconciliation */ }
}
```

- L'`Idempotency-Key` dérivé de l'`order_id` garantit qu'un **retry** (même sur un
  nouveau process) réutilise la même clé → pas de double débit.
- Sur `UNCERTAIN`, la commande **reste `stock_reserved`** ; un job de
  réconciliation peut relire `GET /v1/payments/{order_id}` pour trancher sans
  recharger.

---

## 5. Compensation (remboursement)

Si une commande `paid` est annulée, compense dans l'ordre inverse : rembourser le
paiement **avant** de libérer le stock.

```java
// Lors de l'annulation d'une commande 'paid' :
paymentClient.refund(orderId);      // POST /v1/payments/{orderId}/refund → 200 refunded
stockClient.release(orderId, ...);  // puis libérer le stock
self.transition(orderId, OrderStatus.CANCELLED);
```

`refund` est tolérant : `payment-service` renvoie `409` si le paiement est déjà
remboursé ou jamais `succeeded` ; traite ce cas comme déjà compensé (idempotent).

---

## 6. Récupération après réponse perdue

Si Orders Service ne reçoit jamais la réponse HTTP du `POST /payments` :

1. **Rejouer** `POST /payments` avec le **même** `Idempotency-Key` → l'issue
   enregistrée est renvoyée (même `id`), sans second débit.
2. **ou** lire `GET /v1/payments/{order_id}` pour récupérer le statut
   (`succeeded` / `failed` / `refunded`) et décider du statut de la commande.

Les deux chemins sont sûrs : exactement une ligne de paiement existe par commande.

---

## 7. Checklist d'intégration

- [ ] `PAYMENTS_BASE_URL` ajouté au service `orders-service` du `docker-compose.yml`
      (+ `depends_on: payment-service`).
- [ ] `payments.base-url` ajouté à `orders-service/application.properties`.
- [ ] `PaymentClient` + `PaymentOutcome` + `PaymentRestClient` +
      `UnconfiguredPaymentClient` + `PaymentConfig` ajoutés.
- [ ] Appel `capture(...)` branché dans `OrderService.createOrder` (hors transaction).
- [ ] Compensation `refund(...)` branchée sur l'annulation d'une commande `paid`.
- [ ] `Idempotency-Key` stable par `order_id`.
- [ ] Tests : succès → `paid`, refus (> 200 €) → `payment_failed`,
      timeout → reste `stock_reserved`.

