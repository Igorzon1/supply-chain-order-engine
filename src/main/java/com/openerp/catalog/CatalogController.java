package com.openerp.catalog;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import com.openerp.common.DomainException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class CatalogController {
    private final JdbcTemplate db;
    private final SupplierRepository suppliers;
    private final CustomerRepository customers;
    private final ProductRepository products;

    public CatalogController(JdbcTemplate db, SupplierRepository suppliers,
                             CustomerRepository customers, ProductRepository products) {
        this.db = db;
        this.suppliers = suppliers;
        this.customers = customers;
        this.products = products;
    }

    public record PartyInput(@NotBlank String externalCode, @NotBlank String name) {}
    public record ProductInput(@NotBlank String sku, @NotBlank String name) {}
    public record Created(long id) {}
    public record Party(long id, String externalCode, String name) {}
    public record Product(long id, String sku, String name) {}

    @PostMapping("/suppliers") @ResponseStatus(HttpStatus.CREATED)
    public Created supplier(@Valid @RequestBody PartyInput input) {
        return new Created(suppliers.saveAndFlush(new SupplierEntity(input.externalCode(), input.name())).getId());
    }

    @PostMapping("/customers") @ResponseStatus(HttpStatus.CREATED)
    public Created customer(@Valid @RequestBody PartyInput input) {
        return new Created(customers.saveAndFlush(new CustomerEntity(input.externalCode(), input.name())).getId());
    }

    @PostMapping("/products") @ResponseStatus(HttpStatus.CREATED) @Transactional
    public Created product(@Valid @RequestBody ProductInput input) {
        Long id = products.saveAndFlush(new ProductEntity(input.sku(), input.name())).getId();
        db.update("insert into inventory_balance(product_id) values (?)", id);
        return new Created(id);
    }

    @GetMapping("/suppliers/{id}")
    public Party supplier(@PathVariable long id) {
        var entity = suppliers.findById(id).orElseThrow(() -> new DomainException(HttpStatus.NOT_FOUND, "Fornecedor não encontrado"));
        return new Party(entity.getId(), entity.getExternalCode(), entity.getName());
    }

    @GetMapping("/customers/{id}")
    public Party customer(@PathVariable long id) {
        var entity = customers.findById(id).orElseThrow(() -> new DomainException(HttpStatus.NOT_FOUND, "Cliente não encontrado"));
        return new Party(entity.getId(), entity.getExternalCode(), entity.getName());
    }

    @GetMapping("/products/{id}")
    public Product product(@PathVariable long id) {
        var entity = products.findById(id).orElseThrow(() -> new DomainException(HttpStatus.NOT_FOUND, "Produto não encontrado"));
        return new Product(entity.getId(), entity.getSku(), entity.getName());
    }
}
