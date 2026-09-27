package com.openerp.catalog;

import jakarta.persistence.*;

@Entity
@Table(name = "product")
public class ProductEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false, unique = true, length = 64)
    private String sku;
    @Column(nullable = false, length = 160)
    private String name;

    protected ProductEntity() {}
    public ProductEntity(String sku, String name) { this.sku = sku; this.name = name; }
    public Long getId() { return id; }
    public String getSku() { return sku; }
    public String getName() { return name; }
}
