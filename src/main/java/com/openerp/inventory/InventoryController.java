package com.openerp.inventory;

import com.openerp.common.DomainException;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/inventory")
public class InventoryController {
    private final JdbcTemplate db;

    public InventoryController(JdbcTemplate db) { this.db = db; }

    public record Balance(long productId, String sku, long physical, long reserved,
                          long available, BigDecimal averageCost) {}
    public record Movement(long id, String kind, long physicalDelta, long reservedDelta,
                           String sourceType, long sourceId, String actor, OffsetDateTime occurredAt) {}

    @GetMapping("/{sku}")
    public Balance balance(@PathVariable String sku) {
        return db.query("select p.id, p.sku, b.physical, b.reserved, b.average_cost from product p join inventory_balance b on b.product_id=p.id where p.sku=?",
                (rs, row) -> new Balance(rs.getLong(1), rs.getString(2), rs.getLong(3),
                        rs.getLong(4), rs.getLong(3) - rs.getLong(4), rs.getBigDecimal(5)), sku)
                .stream().findFirst().orElseThrow(() -> new DomainException(HttpStatus.NOT_FOUND, "SKU não encontrado"));
    }

    @GetMapping("/{sku}/movements")
    public List<Movement> movements(@PathVariable String sku) {
        Balance balance = balance(sku);
        return db.query("select id,kind,physical_delta,reserved_delta,source_type,source_id,actor,occurred_at from inventory_ledger where product_id=? order by id",
                (rs, row) -> new Movement(rs.getLong(1), rs.getString(2), rs.getLong(3),
                        rs.getLong(4), rs.getString(5), rs.getLong(6), rs.getString(7),
                        rs.getObject(8, OffsetDateTime.class)), balance.productId());
    }
}
