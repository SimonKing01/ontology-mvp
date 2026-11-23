package com.example.ontology.meta;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

/**
 * 元模型定义（当前版本）。JPA 只用于元模型与审计表，绝不用于对象数据。
 */
@Entity
@Table(name = "ont_definition")
public class OntDefinition {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 32)
    private String kind;

    @Column(nullable = false, length = 80)
    private String code;

    @Column(nullable = false)
    private int revision;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private String payload;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    protected OntDefinition() {
    }

    public static OntDefinition create(String kind, String code, int revision, String payload, Instant now) {
        OntDefinition d = new OntDefinition();
        d.kind = kind;
        d.code = code;
        d.revision = revision;
        d.payload = payload;
        d.createdAt = now;
        d.updatedAt = now;
        return d;
    }

    public void updateTo(int revision, String payload, Instant now) {
        this.revision = revision;
        this.payload = payload;
        this.updatedAt = now;
    }

    public Long getId() {
        return id;
    }

    public String getKind() {
        return kind;
    }

    public String getCode() {
        return code;
    }

    public int getRevision() {
        return revision;
    }

    public String getPayload() {
        return payload;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
