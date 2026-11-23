package com.example.ontology.datasource;

import com.example.ontology.common.ErrorCode;
import com.example.ontology.common.OntologyException;
import com.example.ontology.config.CryptoService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 数据源服务。密码只以密文落库，接口永远不回显密码。
 */
@Service
public class DatasourceService {

    private final DsSourceRepository repository;
    private final CryptoService crypto;
    private final ObjectMapper mapper;

    public DatasourceService(DsSourceRepository repository, CryptoService crypto, ObjectMapper mapper) {
        this.repository = repository;
        this.crypto = crypto;
        this.mapper = mapper;
    }

    @Transactional
    public DatasourceDtos.SourceView create(DatasourceDtos.SourceRequest req) {
        if (req.name() == null || req.name().isBlank()) {
            throw OntologyException.badRequest("数据源 name 不能为空");
        }
        String kind = req.kind() == null ? "" : req.kind().trim().toUpperCase(Locale.ROOT);
        if (!kind.equals("POSTGRES") && !kind.equals("CSV")) {
            throw OntologyException.badRequest("数据源 kind 只支持 POSTGRES | CSV");
        }
        repository.findByName(req.name()).ifPresent(s -> {
            throw OntologyException.conflict("数据源已存在: " + req.name(), Map.of("name", req.name()));
        });
        if (kind.equals("POSTGRES")) {
            if (req.host() == null || req.host().isBlank() || req.port() == null || req.database() == null) {
                throw OntologyException.badRequest("POSTGRES 数据源必须提供 host/port/database");
            }
        } else if (req.host() == null || req.host().isBlank()) {
            throw OntologyException.badRequest("CSV 数据源必须提供 host（CSV 文件所在目录）");
        }
        DsSource saved = repository.save(DsSource.create(
                req.name().trim(), kind, req.host(), req.port(), req.database(), req.schema(),
                req.username(), crypto.encrypt(req.password())));
        return view(saved);
    }

    @Transactional(readOnly = true)
    public List<DatasourceDtos.SourceView> list() {
        return repository.findAllByOrderByIdAsc().stream().map(this::view).toList();
    }

    @Transactional(readOnly = true)
    public DsSource require(Long id) {
        return repository.findById(id)
                .orElseThrow(() -> new OntologyException(ErrorCode.DATASET_NOT_FOUND,
                        "数据源不存在: " + id, Map.of("sourceId", id)));
    }

    private DatasourceDtos.SourceView view(DsSource s) {
        return new DatasourceDtos.SourceView(s.getId(), s.getName(), s.getKind(), s.getHost(), s.getPort(),
                s.getDatabaseName(), s.getSchemaName(), s.getUsername(),
                s.getPasswordCipher() != null && !s.getPasswordCipher().isBlank(), s.getCreatedAt());
    }
}
