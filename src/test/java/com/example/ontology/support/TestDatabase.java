package com.example.ontology.support;

import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.net.URI;

/**
 * 测试数据库来源：
 *
 * <ol>
 *   <li>默认启动 Testcontainers PostgreSQL 16（CI / 正常 Docker 环境）；</li>
 *   <li>若本机 Testcontainers 连不上 Docker（例如 Docker Engine 29 的命名管道不兼容旧版 docker-java），
 *       回退到 {@code TEST_DB_URL} 指向的一个<b>空库</b>，例如
 *       {@code TEST_DB_URL=jdbc:postgresql://localhost:55434/ontology_test mvn test}。</li>
 * </ol>
 *
 * <p>回退只是环境适配，不改变测试内容：Flyway 会在这个库上重建全部表。</p>
 */
public final class TestDatabase {

    private static final String URL;
    private static final String USERNAME;
    private static final String PASSWORD;
    private static final String HOST;
    private static final int PORT;
    private static final String DATABASE;
    private static final boolean CONTAINERIZED;

    static {
        PostgreSQLContainer<?> container = null;
        boolean dockerAvailable = false;
        try {
            dockerAvailable = DockerClientFactory.instance().isDockerAvailable();
        } catch (Throwable ignored) {
            dockerAvailable = false;
        }

        if (dockerAvailable) {
            container = new PostgreSQLContainer<>(DockerImageName.parse("postgres:16"))
                    .withDatabaseName("ontology")
                    .withUsername("ontology")
                    .withPassword("ontology");
            container.start();
            URL = container.getJdbcUrl();
            USERNAME = container.getUsername();
            PASSWORD = container.getPassword();
            HOST = container.getHost();
            PORT = container.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT);
            DATABASE = container.getDatabaseName();
        } else {
            String fallback = System.getenv("TEST_DB_URL");
            if (fallback == null || fallback.isBlank()) {
                throw new IllegalStateException("""
                        Testcontainers 无法连接 Docker，且未设置 TEST_DB_URL。
                        两种解决方式：
                          1) 启动可用的 Docker（Testcontainers 会自动拉起 postgres:16）；
                          2) 指定一个空库：TEST_DB_URL=jdbc:postgresql://localhost:55434/ontology_test mvn test
                        """);
            }
            URL = fallback;
            USERNAME = System.getenv().getOrDefault("TEST_DB_USER", "ontology");
            PASSWORD = System.getenv().getOrDefault("TEST_DB_PASSWORD", "ontology");
            URI uri = URI.create(fallback.substring("jdbc:".length()));
            HOST = uri.getHost();
            PORT = uri.getPort() <= 0 ? 5432 : uri.getPort();
            DATABASE = uri.getPath().replaceFirst("^/", "");
        }
        CONTAINERIZED = container != null;
    }

    private TestDatabase() {
    }

    public static String url() {
        return URL;
    }

    public static String username() {
        return USERNAME;
    }

    public static String password() {
        return PASSWORD;
    }

    public static String host() {
        return HOST;
    }

    public static int port() {
        return PORT;
    }

    public static String database() {
        return DATABASE;
    }

    public static boolean containerized() {
        return CONTAINERIZED;
    }
}
