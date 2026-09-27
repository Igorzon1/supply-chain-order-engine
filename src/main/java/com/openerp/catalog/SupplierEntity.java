package com.openerp.catalog;

import jakarta.persistence.*;

@Entity
@Table(name = "supplier")
public class SupplierEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "external_code", nullable = false, unique = true, length = 64)
    private String externalCode;
    @Column(nullable = false, length = 160)
    private String name;

    protected SupplierEntity() {}
    public SupplierEntity(String externalCode, String name) { this.externalCode = externalCode; this.name = name; }
    public Long getId() { return id; }
    public String getExternalCode() { return externalCode; }
    public String getName() { return name; }
}
