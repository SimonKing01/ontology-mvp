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
 * 元模型历史版本：每次修改把旧 payload 落到这里，revision 单调递增。
 */
@Entity
@Table(name = "ont_definition_history")
public class OntDefinitionHistory {

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

    protected OntDefinitionHistory() {
    }

    public static OntDefinitionHistory of(String kind, String code, int revision, String payload, Instant now) {
        OntDefinitionHistory h = new OntDefinitionHistory();
        h.kind = kind;
        h.code = code;
        h.revision = revision;
        h.payload = payload;
        h.createdAt = now;
        return h;
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
}
