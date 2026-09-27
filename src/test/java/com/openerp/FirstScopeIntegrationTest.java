package com.openerp;

import com.openerp.common.DomainException;
import com.openerp.procurement.ProcurementController;
import com.openerp.sales.SalesController;
import java.math.BigDecimal;
import java.security.Principal;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@Testcontainers
class FirstScopeIntegrationTest {
    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("app.staff.password", () -> "test-only-password");
    }

    @Autowired JdbcTemplate db;
    @Autowired ProcurementController purchases;
    @Autowired SalesController orders;
    private final Principal staff = () -> "tester";

    private long product(long quantity) {
        String suffix = java.util.UUID.randomUUID().toString();
        long id = db.queryForObject("insert into product(sku,name) values (?,?) returning id", Long.class,
                "SKU-" + suffix, "Test product");
        db.update("insert into inventory_balance(product_id,physical) values (?,?)", id, quantity);
        return id;
    }

    private long customer() {
        String code = java.util.UUID.randomUUID().toString();
        return db.queryForObject("insert into customer(external_code,name) values (?,?) returning id",
                Long.class, code, "Test customer");
    }

    private long supplier() {
        String code = java.util.UUID.randomUUID().toString();
        return db.queryForObject("insert into supplier(external_code,name) values (?,?) returning id",
                Long.class, code, "Test supplier");
    }

    @Test
    void receivingIsOnceAndUpdatesWeightedCost() {
        long product = product(10);
        db.update("update inventory_balance set average_cost=10 where product_id=?", product);
        var purchase = purchases.create(new ProcurementController.Create(supplier(),
                List.of(new ProcurementController.Line(product, 10, new BigDecimal("20.0000")))), staff);
        purchases.receive(purchase.id(), staff);
        purchases.receive(purchase.id(), staff);
        var balance = db.queryForMap("select physical,average_cost from inventory_balance where product_id=?", product);
        assertEquals(20L, ((Number) balance.get("physical")).longValue());
        assertEquals(0, new BigDecimal("15.0000").compareTo((BigDecimal) balance.get("average_cost")));
        assertEquals(1, db.queryForObject("select count(*) from inventory_ledger where product_id=?", Integer.class, product));
    }

    @Test
    void orderKeyReturnsSameOrderAndRejectsDifferentPayload() {
        long product = product(3);
        long customer = customer();
        String key = java.util.UUID.randomUUID().toString();
        var request = new SalesController.Create(customer, List.of(new SalesController.Line(product, 1)));
        var first = orders.create(key, request, staff);
        var again = orders.create(key, request, staff);
        assertEquals(first.id(), again.id());
        assertEquals(1, db.queryForObject("select reserved from inventory_balance where product_id=?", Integer.class, product));
        DomainException conflict = assertThrows(DomainException.class,
                () -> orders.create(key, new SalesController.Create(customer,
                        List.of(new SalesController.Line(product, 2))), staff));
        assertEquals(HttpStatus.CONFLICT, conflict.status());
    }

    @Test
    void concurrentRetriesWithSameKeyCreateOneOrder() throws Exception {
        long product = product(2);
        long customer = customer();
        String key = java.util.UUID.randomUUID().toString();
        var request = new SalesController.Create(customer, List.of(new SalesController.Line(product, 1)));
        try (var pool = Executors.newFixedThreadPool(2)) {
            List<Future<SalesController.Order>> results = pool.invokeAll(List.of(
                    () -> orders.create(key, request, staff),
                    () -> orders.create(key, request, staff)));
            assertEquals(results.get(0).get().id(), results.get(1).get().id());
        }
        assertEquals(1, db.queryForObject("select reserved from inventory_balance where product_id=?", Integer.class, product));
        assertEquals(1, db.queryForObject("select count(*) from sales_order where idempotency_key=?", Integer.class, key));
    }

    @Test
    void concurrentOrdersNeverReserveMoreThanAvailable() throws Exception {
        long product = product(1);
        long customer = customer();
        try (var pool = Executors.newFixedThreadPool(8)) {
            List<Callable<Boolean>> attempts = IntStream.range(0, 20).<Callable<Boolean>>mapToObj(i -> () -> {
                try {
                    orders.create("race-" + java.util.UUID.randomUUID(),
                            new SalesController.Create(customer, List.of(new SalesController.Line(product, 1))), staff);
                    return true;
                } catch (DomainException ex) {
                    assertEquals(HttpStatus.CONFLICT, ex.status());
                    return false;
                }
            }).toList();
            List<Future<Boolean>> results = pool.invokeAll(attempts);
            assertEquals(1, results.stream().filter(f -> {
                try { return f.get(); } catch (Exception e) { throw new RuntimeException(e); }
            }).count());
        }
        assertEquals(1, db.queryForObject("select reserved from inventory_balance where product_id=?", Integer.class, product));
        assertEquals(1, db.queryForObject("select count(*) from inventory_ledger where product_id=? and kind='RESERVATION'", Integer.class, product));
    }

    @Test
    void multiItemReservationRollsBackWhenOneItemIsUnavailable() {
        long available = product(1);
        long unavailable = product(0);
        DomainException ex = assertThrows(DomainException.class, () -> orders.create(
                java.util.UUID.randomUUID().toString(), new SalesController.Create(customer(),
                        List.of(new SalesController.Line(available, 1), new SalesController.Line(unavailable, 1))), staff));
        assertEquals(HttpStatus.CONFLICT, ex.status());
        assertEquals(0, db.queryForObject("select reserved from inventory_balance where product_id=?", Integer.class, available));
        assertEquals(0, db.queryForObject("select count(*) from inventory_ledger where product_id=?", Integer.class, available));
    }
}
