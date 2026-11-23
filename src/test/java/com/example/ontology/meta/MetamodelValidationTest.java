package com.example.ontology.meta;

import com.example.ontology.common.PropertyType;
import com.example.ontology.meta.model.ActionTypeDef;
import com.example.ontology.meta.model.LimitsDef;
import com.example.ontology.meta.model.MutationDef;
import com.example.ontology.meta.model.ObjectTypeDef;
import com.example.ontology.meta.model.ObjectTypeStatus;
import com.example.ontology.meta.model.PreconditionDef;
import com.example.ontology.meta.model.PropertyDef;
import com.example.ontology.meta.model.PropertyOwner;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 元模型校验的单元测试：不启动 Spring，直接验证规则。
 */
class MetamodelValidationTest {

    private ObjectTypeDef flight() {
        return new ObjectTypeDef("Flight", "航班", "ds_flight", "flightNo", "flightNo",
                ObjectTypeStatus.ACTIVE, List.of(
                new PropertyDef("flightNo", "航班号", "flight_no", PropertyType.STRING, true,
                        PropertyOwner.SOURCE, false, null),
                new PropertyDef("status", "状态", null, PropertyType.STRING, false,
                        PropertyOwner.ONTOLOGY, false, null)));
    }

    private ActionTypeDef action(String code, List<MutationDef> mutations) {
        return new ActionTypeDef(code, code, "Flight", List.of(), List.of(), mutations, new LimitsDef(10));
    }

    @Test
    void 拒绝修改_source_属性的_Action() {
        ActionTypeDef bad = action("BadCancel", List.of(
                new MutationDef("update", "flightNo", "literal:'X'", null, null, null, null, null, null)));

        List<String> errors = MetamodelValidator.validate(
                Map.of("Flight", flight()), Map.of(), Map.of("BadCancel", bad));

        assertThat(errors).anyMatch(e -> e.contains("违反 R7") && e.contains("flightNo"));
    }

    @Test
    void 拒绝未声明属性的前置条件() {
        ActionTypeDef bad = new ActionTypeDef("BadCancel", "bad", "Flight", List.of(), List.of(
                new PreconditionDef("notEquals", "notExist", "X", "E1", "msg", null, null, null)),
                List.of(new MutationDef("update", "status", "literal:'CANCELLED'", null, null, null, null, null, null)),
                new LimitsDef(10));

        List<String> errors = MetamodelValidator.validate(
                Map.of("Flight", flight()), Map.of(), Map.of("BadCancel", bad));

        assertThat(errors).anyMatch(e -> e.contains("prop 未在 Flight 中声明"));
    }

    @Test
    void 拒绝_ontology_属性携带_sourcePath() {
        ObjectTypeDef bad = new ObjectTypeDef("Flight", "航班", "ds", "flightNo", "flightNo",
                ObjectTypeStatus.ACTIVE, List.of(
                new PropertyDef("flightNo", "航班号", "flight_no", PropertyType.STRING, true,
                        PropertyOwner.SOURCE, false, null),
                new PropertyDef("status", "状态", "status_col", PropertyType.STRING, false,
                        PropertyOwner.ONTOLOGY, false, null)));

        List<String> errors = MetamodelValidator.validate(Map.of("Flight", bad), Map.of(), Map.of());

        assertThat(errors).anyMatch(e -> e.contains("owner=ontology 不允许声明 sourcePath"));
    }

    @Test
    void 拒绝缺少表达式的_derived_属性() {
        ObjectTypeDef bad = new ObjectTypeDef("Flight", "航班", "ds", "flightNo", "flightNo",
                ObjectTypeStatus.ACTIVE, List.of(
                new PropertyDef("flightNo", "航班号", "flight_no", PropertyType.STRING, true,
                        PropertyOwner.SOURCE, false, null),
                new PropertyDef("delayMinutes", "延误", null, PropertyType.INTEGER, false,
                        PropertyOwner.DERIVED, false, null)));

        List<String> errors = MetamodelValidator.validate(Map.of("Flight", bad), Map.of(), Map.of());

        assertThat(errors).anyMatch(e -> e.contains("owner=derived 必须声明 derivedExpr"));
    }

    @Test
    void 合法模型无错误() {
        ActionTypeDef cancel = new ActionTypeDef("CancelFlight", "取消", "Flight",
                List.of(new com.example.ontology.meta.model.ActionParamDef("flightId", "objectRef", true,
                        "Flight", false, null)),
                List.of(new PreconditionDef("notEquals", "status", "DEPARTED", "ORDER_DEPARTED",
                        "已起飞不能取消", null, null, null)),
                List.of(new MutationDef("update", "status", "literal:'CANCELLED'", null, null, null, null, null, null)),
                new LimitsDef(1));

        List<String> errors = MetamodelValidator.validate(
                Map.of("Flight", flight()), Map.of(), Map.of("CancelFlight", cancel));

        assertThat(errors).isEmpty();
    }
}
