package com.openerp.procurement;

import com.openerp.common.DomainException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.security.Principal;
import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/purchases")
public class ProcurementController {
    private final JdbcTemplate db;

    public ProcurementController(JdbcTemplate db) { this.db = db; }

    public record Line(@NotNull Long productId, @Positive long quantity,
                       @NotNull @PositiveOrZero BigDecimal unitCost) {}
    public record Create(@NotNull Long supplierId, @NotEmpty List<@Valid Line> lines) {}
    public record Purchase(long id, long supplierId, String createdBy, OffsetDateTime receivedAt) {}

    @PostMapping @ResponseStatus(HttpStatus.CREATED) @Transactional
    public Purchase create(@Valid @RequestBody Create input, Principal principal) {
        if (input.lines().stream().map(Line::productId).anyMatch(id -> id <= 0) ||
                input.supplierId() <= 0 ||
                new HashSet<>(input.lines().stream().map(Line::productId).toList()).size() != input.lines().size()) {
            throw new DomainException(HttpStatus.BAD_REQUEST, "Itens repetidos ou identificador inválido");
        }
        if (db.queryForObject("select count(*) from supplier where id=?", Integer.class, input.supplierId()) == 0) {
            throw new DomainException(HttpStatus.NOT_FOUND, "Fornecedor não encontrado");
        }
        Long id = db.queryForObject("insert into purchase_order(supplier_id,created_by) values (?,?) returning id",
                Long.class, input.supplierId(), principal.getName());
        for (Line line : input.lines()) {
            db.update("insert into purchase_line(purchase_id,product_id,quantity,unit_cost) values (?,?,?,?)",
                    id, line.productId(), line.quantity(), line.unitCost().setScale(4, RoundingMode.HALF_UP));
        }
        return get(id);
    }

    @GetMapping("/{id}")
    public Purchase get(@PathVariable long id) {
        return db.query("select id,supplier_id,created_by,received_at from purchase_order where id=?",
                (rs, row) -> new Purchase(rs.getLong(1), rs.getLong(2), rs.getString(3),
                        rs.getObject(4, OffsetDateTime.class)), id)
                .stream().findFirst().orElseThrow(() -> new DomainException(HttpStatus.NOT_FOUND, "Compra não encontrada"));
    }

    @PostMapping("/{id}/receive") @Transactional
    public Purchase receive(@PathVariable long id, Principal principal) {
        List<Boolean> received = db.query("select received_at is not null from purchase_order where id=? for update",
                (rs, row) -> rs.getBoolean(1), id);
        if (received.isEmpty()) throw new DomainException(HttpStatus.NOT_FOUND, "Compra não encontrada");
        if (received.getFirst()) return get(id);

        List<Line> lines = db.query("select product_id,quantity,unit_cost from purchase_line where purchase_id=? order by product_id",
                (rs, row) -> new Line(rs.getLong(1), rs.getLong(2), rs.getBigDecimal(3)), id);
        for (Line line : lines) {
            var balance = db.queryForMap("select physical,average_cost from inventory_balance where product_id=? for update", line.productId());
            long oldPhysical = ((Number) balance.get("physical")).longValue();
            BigDecimal oldCost = (BigDecimal) balance.get("average_cost");
            long newPhysical = Math.addExact(oldPhysical, line.quantity());
            BigDecimal average = oldCost.multiply(BigDecimal.valueOf(oldPhysical))
                    .add(line.unitCost().multiply(BigDecimal.valueOf(line.quantity())))
                    .divide(BigDecimal.valueOf(newPhysical), 4, RoundingMode.HALF_UP);
            db.update("update inventory_balance set physical=?,average_cost=? where product_id=?",
                    newPhysical, average, line.productId());
            db.update("insert into inventory_ledger(product_id,kind,physical_delta,reserved_delta,source_type,source_id,actor) values (?,'RECEIPT',?,0,'PURCHASE',?,?)",
                    line.productId(), line.quantity(), id, principal.getName());
        }
        db.update("update purchase_order set received_at=now() where id=?", id);
        return get(id);
    }
}
