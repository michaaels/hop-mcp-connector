package io.github.michaaels.hop.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.hop.core.database.DatabaseMeta;
import org.apache.hop.core.HopEnvironment;
import org.apache.hop.core.variables.Variables;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

@SuppressWarnings("unchecked")
class HopSchemaCompareServiceTest {
  @BeforeAll
  static void initializeHop() throws Exception {
    HopEnvironment.init();
  }

  @Test
  void reportsAllSupportedSchemaDifferenceKinds() throws Exception {
    HopSchemaCompareService service =
        service(
            new HopSchemaCompareService.SchemaReadResult(
                List.of(
                    new HopSchemaCompareService.SchemaColumn(
                        "ID", "String", "VARCHAR", 20, 0, 0, false),
                    new HopSchemaCompareService.SchemaColumn(
                        "NAME", "String", "VARCHAR", 100, 2, 2, false),
                    new HopSchemaCompareService.SchemaColumn(
                        "EXTRA", "Integer", "INTEGER", 10, 10, 0, true)),
                3,
                false));

    List<Map<String, Object>> expected =
        List.of(
            field("id", "Integer", 20, 0, 0, false),
            field("name", "Number", 80, 3, 1, true),
            field("missing", "String", 10, null, null, null));

    Map<String, Object> result = service.compare("DWH", "public", "customers", expected, 3);

    assertFalse((Boolean) result.get("matches"));
    assertEquals(3, result.get("expected_count"));
    assertEquals(3, result.get("actual_count"));
    assertEquals(
        Set.of(
            "COLUMN_ADDED",
            "COLUMN_REMOVED",
            "TYPE_CHANGED",
            "LENGTH_CHANGED",
            "PRECISION_CHANGED",
            "SCALE_CHANGED",
            "NULLABILITY_CHANGED"),
        codes((List<Map<String, Object>>) result.get("differences")));
  }

  @Test
  void comparesNamesCaseInsensitivelyAndOmitsUnspecifiedAttributes() throws Exception {
    HopSchemaCompareService service =
        service(
            new HopSchemaCompareService.SchemaReadResult(
                List.of(
                    new HopSchemaCompareService.SchemaColumn(
                        "CUSTOMER_ID", "String", "VARCHAR", 100, 0, 0, true)),
                1,
                false));

    Map<String, Object> result =
        service.compare(
            "DWH", "public", "customers", List.of(field("customer_id", "string", null, null, null, null)), 3);

    assertTrue((Boolean) result.get("matches"));
    assertEquals(0, result.get("difference_count"));
    assertTrue((Boolean) result.get("comparison_complete"));
  }

  @Test
  void requiresDeepCheckAndValidatesBoundsBeforeContactingMetadata() {
    HopSchemaCompareService disabled =
        new HopSchemaCompareService(null, new Variables(), false, (c, v, s, t) -> null, n -> null);
    assertThrows(
        SecurityException.class,
        () -> disabled.compare("DWH", "public", "customers", List.of(), 3));

    HopSchemaCompareService enabled =
        new HopSchemaCompareService(null, new Variables(), true, (c, v, s, t) -> null, n -> null);
    assertThrows(
        IllegalArgumentException.class,
        () -> enabled.compare("DWH", "public", "customers", List.of(), 0));

    List<Map<String, Object>> oversized = new ArrayList<>();
    for (int i = 0; i < HopSchemaCompareService.MAX_FIELDS + 1; i++) {
      oversized.add(field("c" + i, "String", null, null, null, null));
    }
    assertThrows(
        IllegalArgumentException.class,
        () -> enabled.compare("DWH", "public", "customers", oversized, 3));
  }

  private static HopSchemaCompareService service(
      HopSchemaCompareService.SchemaReadResult schema) {
    return new HopSchemaCompareService(
        null,
        new Variables(),
        true,
        (connection, variables, schemaName, tableName) -> schema,
        name -> new DatabaseMeta("DWH", "None", "", "", "", "", "", ""));
  }

  private static Map<String, Object> field(
      String name,
      String type,
      Integer length,
      Integer precision,
      Integer scale,
      Boolean nullable) {
    Map<String, Object> field = new LinkedHashMap<>();
    field.put("name", name);
    field.put("type", type);
    if (length != null) field.put("length", length);
    if (precision != null) field.put("precision", precision);
    if (scale != null) field.put("scale", scale);
    if (nullable != null) field.put("nullable", nullable);
    return field;
  }

  private static Set<String> codes(List<Map<String, Object>> differences) {
    Set<String> codes = new HashSet<>();
    for (Map<String, Object> difference : differences) {
      codes.add(String.valueOf(difference.get("code")));
    }
    return codes;
  }
}
