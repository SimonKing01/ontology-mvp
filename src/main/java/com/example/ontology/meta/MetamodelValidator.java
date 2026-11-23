package com.example.ontology.meta;

import com.example.ontology.action.expr.ValueExpr;
import com.example.ontology.common.PropertyType;
import com.example.ontology.meta.model.ActionParamDef;
import com.example.ontology.meta.model.ActionTypeDef;
import com.example.ontology.meta.model.LinkTypeDef;
import com.example.ontology.meta.model.MutationDef;
import com.example.ontology.meta.model.ObjectTypeDef;
import com.example.ontology.meta.model.PreconditionDef;
import com.example.ontology.meta.model.PropertyDef;
import com.example.ontology.meta.model.PropertyOwner;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 元模型校验器。
 *
 * <p>这是 R7「每个属性必须声明状态归属」和 R3「Action 是唯一写入口」的守门人：
 * <b>如果某个 Action 试图修改 owner=source/derived 的属性，必须在启动期就报错</b>，
 * 而不是等运行期发现写了不该写的列。</p>
 */
public final class MetamodelValidator {

    private static final Pattern CODE = Pattern.compile("^[A-Za-z][A-Za-z0-9_]{0,63}$");
    private static final Pattern DERIVED_EXPR = Pattern.compile(
            "^(src:[A-Za-z_][A-Za-z0-9_.]*"
                    + "|link:[A-Za-z][A-Za-z0-9_]*\\.[A-Za-z][A-Za-z0-9_]*"
                    + "|count:[A-Za-z][A-Za-z0-9_]*"
                    + "|sum:[A-Za-z][A-Za-z0-9_]*\\.[A-Za-z][A-Za-z0-9_]*)$");

    private static final Set<String> SCALAR_PARAM_TYPES = Set.of(
            "string", "integer", "long", "decimal", "boolean", "date", "timestamp");

    private static final Set<String> PROP_CONDITIONS = Set.of(
            "equals", "notEquals", "in", "notIn", "gt", "gte", "lt", "lte", "notNull", "isNull");
    private static final Set<String> LINK_CONDITIONS = Set.of("hasLink", "notHasLink");
    private static final Set<String> COUNT_CONDITIONS = Set.of("relatedCountLt");
    private static final Set<String> MUTATION_OPS = Set.of("update", "create", "createLink", "deleteLink");

    private MetamodelValidator() {
    }

    /**
     * 全量校验：返回所有错误（不短路），便于一次性修完。
     */
    public static List<String> validate(Map<String, ObjectTypeDef> types,
                                        Map<String, LinkTypeDef> links,
                                        Map<String, ActionTypeDef> actions) {
        List<String> errors = new ArrayList<>();
        types.values().forEach(t -> validateObjectType(t, errors));
        links.values().forEach(l -> validateLinkType(l, types, errors));
        actions.values().forEach(a -> validateActionType(a, types, links, errors));
        types.values().forEach(t -> validateDerivedExprs(t, types, links, errors));
        return errors;
    }

    // ------------------------------------------------------------------
    // 对象类型
    // ------------------------------------------------------------------
    private static void validateObjectType(ObjectTypeDef t, List<String> errors) {
        String where = "ObjectType[" + t.code() + "]";
        if (!code(t.code())) {
            errors.add(where + " code 不合法（字母开头，字母/数字/下划线，≤64）");
            return;
        }
        if (blank(t.name())) {
            errors.add(where + " name 不能为空");
        }
        if (blank(t.datasetId())) {
            errors.add(where + " datasetId 不能为空（对象类型必须映射到数据集）");
        }
        List<PropertyDef> props = t.propertiesOrEmpty();
        if (props.isEmpty()) {
            errors.add(where + " 至少需要一个属性");
            return;
        }
        Set<String> keys = new HashSet<>();
        for (PropertyDef p : props) {
            String pw = where + "." + p.key();
            if (blank(p.key()) || !CODE.matcher(p.key()).matches()) {
                errors.add(pw + " 属性 key 不合法");
                continue;
            }
            if (!keys.add(p.key())) {
                errors.add(pw + " 属性 key 重复");
            }
            if (p.type() == null) {
                errors.add(pw + " type 不能为空");
            }
            if (p.owner() == null) {
                errors.add(pw + " owner 不能为空（必须声明 source|ontology|derived，R7 不允许无主状态）");
                continue;
            }
            switch (p.owner()) {
                case SOURCE -> {
                    if (blank(p.sourcePath())) {
                        errors.add(pw + " owner=source 必须声明 sourcePath");
                    }
                    if (!blank(p.derivedExpr())) {
                        errors.add(pw + " owner=source 不允许声明 derivedExpr");
                    }
                }
                case ONTOLOGY -> {
                    if (!blank(p.sourcePath())) {
                        errors.add(pw + " owner=ontology 不允许声明 sourcePath（上游没有这一列，本体才是唯一真相）");
                    }
                    if (!blank(p.derivedExpr())) {
                        errors.add(pw + " owner=ontology 不允许声明 derivedExpr");
                    }
                }
                case DERIVED -> {
                    if (blank(p.derivedExpr())) {
                        errors.add(pw + " owner=derived 必须声明 derivedExpr");
                    } else if (!DERIVED_EXPR.matcher(p.derivedExpr().trim()).matches()) {
                        errors.add(pw + " derivedExpr 不合法: " + p.derivedExpr()
                                + "（允许 src:列 / link:LinkType.prop / count:LinkType / sum:LinkType.prop）");
                    }
                    if (!blank(p.sourcePath())) {
                        errors.add(pw + " owner=derived 不允许声明 sourcePath");
                    }
                }
            }
        }
        if (!blank(t.idField())) {
            PropertyDef idp = t.find(t.idField());
            if (idp == null) {
                errors.add(where + " idField=" + t.idField() + " 未在 properties 中声明");
            } else {
                if (idp.isArray()) {
                    errors.add(where + " idField 不能是多值属性");
                }
                if (idp.owner() == PropertyOwner.DERIVED) {
                    errors.add(where + " idField 不能是 derived 属性（身份必须稳定且来自上游或本体）");
                }
                if (idp.type() != null && !(idp.type() == PropertyType.STRING
                        || idp.type() == PropertyType.INTEGER || idp.type() == PropertyType.LONG)) {
                    errors.add(where + " idField 类型必须是 string/integer/long");
                }
            }
        } else {
            errors.add(where + " idField 不能为空");
        }
        if (!blank(t.titleField())) {
            PropertyDef tp = t.find(t.titleField());
            if (tp == null) {
                errors.add(where + " titleField=" + t.titleField() + " 未在 properties 中声明");
            } else if (tp.isArray()) {
                errors.add(where + " titleField 不能是多值属性");
            }
        }
    }

    // ------------------------------------------------------------------
    // 链接类型
    // ------------------------------------------------------------------
    private static void validateLinkType(LinkTypeDef l, Map<String, ObjectTypeDef> types, List<String> errors) {
        String where = "LinkType[" + l.code() + "]";
        if (!code(l.code())) {
            errors.add(where + " code 不合法");
            return;
        }
        if (!types.containsKey(l.fromTypeCode())) {
            errors.add(where + " fromTypeCode 不存在: " + l.fromTypeCode());
        }
        if (!types.containsKey(l.toTypeCode())) {
            errors.add(where + " toTypeCode 不存在: " + l.toTypeCode());
        }
        if (l.cardinality() == null) {
            errors.add(where + " cardinality 不能为空（1:1|1:N|N:M）");
        }
        if (blank(l.fromField()) || blank(l.toField())) {
            errors.add(where + " fromField/toField 不能为空（链接靠源列 JOIN 物化，不能凭空猜关系）");
        }
    }

    // ------------------------------------------------------------------
    // Action 类型
    // ------------------------------------------------------------------
    private static void validateActionType(ActionTypeDef a, Map<String, ObjectTypeDef> types,
                                           Map<String, LinkTypeDef> links, List<String> errors) {
        String where = "ActionType[" + a.code() + "]";
        if (!code(a.code())) {
            errors.add(where + " code 不合法");
            return;
        }
        ObjectTypeDef target = types.get(a.targetTypeCode());
        if (target == null) {
            errors.add(where + " targetTypeCode 不存在: " + a.targetTypeCode());
            return;
        }
        if (a.maxObjects() <= 0) {
            errors.add(where + " limits.maxObjects 必须大于 0");
        }

        // 参数
        Set<String> paramNames = new HashSet<>();
        for (ActionParamDef p : a.paramsOrEmpty()) {
            String pw = where + ".param[" + p.name() + "]";
            if (blank(p.name()) || !CODE.matcher(p.name()).matches()) {
                errors.add(pw + " name 不合法");
                continue;
            }
            if (!paramNames.add(p.name())) {
                errors.add(pw + " name 重复");
            }
            if (blank(p.type())) {
                errors.add(pw + " type 不能为空");
            } else if (p.isObjectRef()) {
                if (blank(p.refType())) {
                    errors.add(pw + " objectRef 必须声明 refType");
                } else if (!types.containsKey(p.refType())) {
                    errors.add(pw + " refType 不存在: " + p.refType());
                }
            } else if (!SCALAR_PARAM_TYPES.contains(p.type().toLowerCase(Locale.ROOT))) {
                errors.add(pw + " 不支持的 type: " + p.type());
            }
        }

        // 前置条件
        for (PreconditionDef c : a.preconditionsOrEmpty()) {
            String cw = where + ".precondition[" + c.type() + "]";
            if (blank(c.errorCode())) {
                errors.add(cw + " errorCode 不能为空（422 必须机器可读）");
            }
            String type = nz(c.type());
            if (PROP_CONDITIONS.contains(type)) {
                PropertyDef prop = blank(c.prop()) ? null : target.find(c.prop());
                if (prop == null) {
                    errors.add(cw + " prop 未在 " + target.code() + " 中声明: " + c.prop());
                } else if (prop.isArray()) {
                    errors.add(cw + " 不支持在多值属性上做前置条件: " + c.prop());
                } else if (Set.of("gt", "gte", "lt", "lte").contains(type)
                        && prop.type() != null && !prop.type().numericColumn()
                        && prop.type() != PropertyType.DATE && prop.type() != PropertyType.TIMESTAMP) {
                    errors.add(cw + " 比较类条件只支持数值/时间属性: " + c.prop());
                }
                if (Set.of("equals", "notEquals", "gt", "gte", "lt", "lte").contains(type) && c.value() == null) {
                    errors.add(cw + " 缺少 value");
                }
                if (Set.of("in", "notIn").contains(type) && !(c.value() instanceof List)) {
                    errors.add(cw + " value 必须是数组");
                }
            } else if (LINK_CONDITIONS.contains(type)) {
                if (blank(c.linkType()) || !links.containsKey(c.linkType())) {
                    errors.add(cw + " linkType 不存在: " + c.linkType());
                }
                if (!"IN".equalsIgnoreCase(nz(c.direction())) && !"OUT".equalsIgnoreCase(nz(c.direction()))) {
                    errors.add(cw + " direction 必须是 IN 或 OUT");
                }
            } else if (COUNT_CONDITIONS.contains(type)) {
                if (blank(c.linkType()) || !links.containsKey(c.linkType())) {
                    errors.add(cw + " linkType 不存在: " + c.linkType());
                }
                if (!(c.value() instanceof Number)) {
                    errors.add(cw + " value 必须是数字");
                }
                String subject = nz(c.subject());
                if (subject.startsWith("param:")) {
                    String pn = subject.substring("param:".length());
                    ActionParamDef p = a.findParam(pn);
                    if (p == null) {
                        errors.add(cw + " subject 引用了未声明参数: " + pn);
                    } else if (!p.isObjectRef()) {
                        errors.add(cw + " subject 必须是 objectRef 参数: " + pn);
                    }
                } else if (!"target".equals(subject)) {
                    errors.add(cw + " subject 必须是 target 或 param:x");
                }
                if (!"IN".equalsIgnoreCase(nz(c.direction())) && !"OUT".equalsIgnoreCase(nz(c.direction()))) {
                    errors.add(cw + " direction 必须是 IN 或 OUT");
                }
            } else {
                errors.add(cw + " 不支持的前置条件类型: " + c.type());
            }
        }

        // 变更：这里是 R7 的启动期强校验
        for (MutationDef m : a.mutationsOrEmpty()) {
            String mw = where + ".mutation[" + m.op() + "]";
            String op = nz(m.op());
            if (!MUTATION_OPS.contains(op)) {
                errors.add(mw + " 不支持的 op: " + m.op());
                continue;
            }
            switch (op) {
                case "update" -> {
                    PropertyDef prop = blank(m.prop()) ? null : target.find(m.prop());
                    if (prop == null) {
                        errors.add(mw + " prop 未在 " + target.code() + " 中声明: " + m.prop());
                        continue;
                    }
                    if (prop.owner() != PropertyOwner.ONTOLOGY) {
                        errors.add(mw + " 违反 R7：Action 不得修改 owner=" + prop.owner().json()
                                + " 的属性 " + target.code() + "." + prop.key()
                                + "（只有 ontology-owned 属性可被 Action 写入）");
                    }
                    if (prop.isArray()) {
                        errors.add(mw + " MVP 不支持整体替换多值属性: " + prop.key());
                    }
                    if (blank(m.valueExpr())) {
                        errors.add(mw + " valueExpr 不能为空");
                    } else {
                        try {
                            ValueExpr.parse(m.valueExpr());
                        } catch (RuntimeException ex) {
                            errors.add(mw + " valueExpr 不合法: " + m.valueExpr());
                        }
                    }
                }
                case "create" -> {
                    if (blank(m.businessKeyExpr())) {
                        errors.add(mw + " create 必须声明 businessKeyExpr（身份规则稳定，R5）");
                    }
                    Map<String, String> values = m.values() == null ? Map.of() : m.values();
                    for (Map.Entry<String, String> e : values.entrySet()) {
                        PropertyDef prop = target.find(e.getKey());
                        if (prop == null) {
                            errors.add(mw + " values 中属性未声明: " + e.getKey());
                            continue;
                        }
                        if (prop.owner() != PropertyOwner.ONTOLOGY) {
                            errors.add(mw + " 违反 R7：create 只能初始化 ontology-owned 属性，"
                                    + e.getKey() + " 的 owner=" + prop.owner().json());
                        }
                        try {
                            ValueExpr.parse(e.getValue());
                        } catch (RuntimeException ex) {
                            errors.add(mw + " values[" + e.getKey() + "] 表达式不合法: " + e.getValue());
                        }
                    }
                    if (blank(m.titleExpr())) {
                        errors.add(mw + " create 必须声明 titleExpr");
                    }
                }
                case "createLink", "deleteLink" -> {
                    LinkTypeDef link = blank(m.linkType()) ? null : links.get(m.linkType());
                    if (link == null) {
                        errors.add(mw + " linkType 不存在: " + m.linkType());
                        continue;
                    }
                    if (!target.code().equals(link.fromTypeCode()) && !target.code().equals(link.toTypeCode())) {
                        errors.add(mw + " linkType " + link.code() + " 与目标类型 " + target.code() + " 无关");
                    }
                    if ("createLink".equals(op)) {
                        if (blank(m.to())) {
                            errors.add(mw + " createLink 必须声明 to（param:x 或 literal:'...'）");
                        } else {
                            try {
                                ValueExpr.parse(m.to());
                            } catch (RuntimeException ex) {
                                errors.add(mw + " to 表达式不合法: " + m.to());
                            }
                        }
                    }
                }
                default -> {
                }
            }
        }
    }

    private static void validateDerivedExprs(ObjectTypeDef t, Map<String, ObjectTypeDef> types,
                                             Map<String, LinkTypeDef> links, List<String> errors) {
        for (PropertyDef p : t.propertiesOrEmpty()) {
            if (p.owner() != PropertyOwner.DERIVED || blank(p.derivedExpr())) {
                continue;
            }
            String expr = p.derivedExpr().trim();
            if (expr.startsWith("src:")) {
                continue;
            }
            String[] parts = expr.split(":");
            String linkCode = parts.length < 2 ? "" : parts[1].split("\\.")[0];
            LinkTypeDef link = links.get(linkCode);
            if (link == null) {
                errors.add("ObjectType[" + t.code() + "]." + p.key() + " derivedExpr 引用了不存在的链接: " + linkCode);
                continue;
            }
            if (!t.code().equals(link.fromTypeCode()) && !t.code().equals(link.toTypeCode())) {
                errors.add("ObjectType[" + t.code() + "]." + p.key() + " 派生链接与对象类型无关: " + linkCode);
                continue;
            }
            if (parts.length == 2 && parts[1].contains(".")) {
                String otherType = t.code().equals(link.fromTypeCode()) ? link.toTypeCode() : link.fromTypeCode();
                String propKey = parts[1].split("\\.")[1];
                ObjectTypeDef other = types.get(otherType);
                if (other == null || other.find(propKey) == null) {
                    errors.add("ObjectType[" + t.code() + "]." + p.key()
                            + " 派生表达式引用了 " + otherType + "." + propKey + "，该属性不存在");
                }
            }
        }
    }

    private static boolean code(String value) {
        return value != null && CODE.matcher(value).matches();
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static String nz(String value) {
        return value == null ? "" : value.trim();
    }
}
