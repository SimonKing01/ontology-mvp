package com.example.ontology.datasource;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * 数据源（POSTGRES / CSV）。密码只存 AES-GCM 密文。
 */
@Entity
@Table(name = "ds_source")
public class DsSource {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 80, unique = true)
    private String name;

    @Column(nullable = false, length = 16)
    private String kind;

    private String host;

    private Integer port;

    @Column(name = "database_name")
    private String databaseName;

    private String schemaName;

    private String username;

    @Column(name = "password_cipher")
    private String passwordCipher;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    protected DsSource() {
    }

    public static DsSource create(String name, String kind, String host, Integer port, String databaseName,
                                  String schemaName, String username, String passwordCipher) {
        DsSource s = new DsSource();
        s.name = name;
        s.kind = kind;
        s.host = host;
        s.port = port;
        s.databaseName = databaseName;
        s.schemaName = schemaName;
        s.username = username;
        s.passwordCipher = passwordCipher;
        s.createdAt = Instant.now();
        s.updatedAt = Instant.now();
        return s;
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getKind() {
        return kind;
    }

    public String getHost() {
        return host;
    }

    public Integer getPort() {
        return port;
    }

    public String getDatabaseName() {
        return databaseName;
    }

    public String getSchemaName() {
        return schemaName;
    }

    public String getUsername() {
        return username;
    }

    public String getPasswordCipher() {
        return passwordCipher;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
