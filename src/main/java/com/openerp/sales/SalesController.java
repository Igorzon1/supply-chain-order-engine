package com.openerp.sales;

import com.openerp.common.DomainException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.Principal;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.HexFormat;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/orders")
public class SalesController {
    private final JdbcTemplate db;

    public SalesController(JdbcTemplate db) { this.db = db; }

    public record Line(@NotNull Long productId, @Positive long quantity) {}
    public record Create(@NotNull Long customerId, @NotEmpty List<@Valid Line> lines) {}
    public record Order(long id, long customerId, String status, String createdBy, List<Line> lines) {}

    @PostMapping @Transactional
    public Order create(@RequestHeader("Idempotency-Key") String key,
                        @Valid @RequestBody Create input, Principal principal) {
        if (key.isBlank() || key.length() > 120) {
            throw new DomainException(HttpStatus.BAD_REQUEST, "Idempotency-Key inválida");
        }
        if (input.customerId() <= 0 || input.lines().stream().map(Line::productId).anyMatch(id -> id <= 0) ||
                new HashSet<>(input.lines().stream().map(Line::productId).toList()).size() != input.lines().size()) {
            throw new DomainException(HttpStatus.BAD_REQUEST, "Itens repetidos ou identificador inválido");
        }
        List<Line> sorted = input.lines().stream().sorted(Comparator.comparing(Line::productId)).toList();
        String hash = hash(principal.getName(), input.customerId(), sorted);

        // Serializa tentativas com a mesma chave, inclusive quando a primeira ainda não confirmou a transação.
        db.queryForObject("select pg_advisory_xact_lock(hashtextextended(?, 0))", Object.class, key);
        List<String> prior = db.query("select request_hash from sales_order where idempotency_key=?",
                (rs, row) -> rs.getString(1), key);
        if (!prior.isEmpty()) {
            if (!prior.getFirst().equals(hash)) {
                throw new DomainException(HttpStatus.CONFLICT, "Chave de idempotência reutilizada com conteúdo diferente");
            }
            return getByKey(key);
        }
        if (db.queryForObject("select count(*) from customer where id=?", Integer.class, input.customerId()) == 0) {
            throw new DomainException(HttpStatus.NOT_FOUND, "Cliente não encontrado");
        }
        Long orderId = db.queryForObject("insert into sales_order(customer_id,created_by,status,idempotency_key,request_hash) values (?,?,'CRIADO',?,?) returning id",
                Long.class, input.customerId(), principal.getName(), key, hash);

        for (Line line : sorted) {
            db.update("insert into sales_line(sales_order_id,product_id,quantity) values (?,?,?)",
                    orderId, line.productId(), line.quantity());
        }
        // Ordem fixa dos locks reduz a chance de deadlock entre pedidos com vários SKUs.
        for (Line line : sorted) {
            List<long[]> balances = db.query("select physical,reserved from inventory_balance where product_id=? for update",
                    (rs, row) -> new long[]{rs.getLong(1), rs.getLong(2)}, line.productId());
            if (balances.isEmpty()) throw new DomainException(HttpStatus.NOT_FOUND, "Produto não encontrado");
            long[] balance = balances.getFirst();
            if (balance[0] - balance[1] < line.quantity()) {
                throw new DomainException(HttpStatus.CONFLICT, "Saldo insuficiente para produto " + line.productId());
            }
            db.update("update inventory_balance set reserved=reserved+? where product_id=?",
                    line.quantity(), line.productId());
            db.update("insert into inventory_ledger(product_id,kind,physical_delta,reserved_delta,source_type,source_id,actor) values (?,'RESERVATION',0,?,'ORDER',?,?)",
                    line.productId(), line.quantity(), orderId, principal.getName());
        }
        db.update("update sales_order set status='RESERVADO' where id=?", orderId);
        return get(orderId);
    }

    @GetMapping("/{id}")
    public Order get(@PathVariable long id) {
        return db.query("select id,customer_id,status,created_by from sales_order where id=?",
                (rs, row) -> new Order(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getString(4),
                        db.query("select product_id,quantity from sales_line where sales_order_id=? order by product_id",
                                (lineRs, lineRow) -> new Line(lineRs.getLong(1), lineRs.getLong(2)), id)), id)
                .stream().findFirst().orElseThrow(() -> new DomainException(HttpStatus.NOT_FOUND, "Pedido não encontrado"));
    }

    private Order getByKey(String key) {
        Long id = db.queryForObject("select id from sales_order where idempotency_key=?", Long.class, key);
        return get(id);
    }

    private static String hash(String actor, long customerId, List<Line> lines) {
        StringBuilder canonical = new StringBuilder(actor).append('|').append(customerId);
        for (Line line : lines) canonical.append('|').append(line.productId()).append(':').append(line.quantity());
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
