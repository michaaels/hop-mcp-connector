package io.github.michaaels.hop.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.apache.hop.core.HopEnvironment;
import org.apache.hop.core.injection.bean.BeanInjectionInfo;
import org.apache.hop.core.plugins.PluginRegistry;
import org.apache.hop.core.row.value.ValueMetaBase;
import org.apache.hop.metadata.api.HopMetadataProperty;
import org.apache.hop.metadata.serializer.memory.MemoryMetadataProvider;
import org.junit.jupiter.api.Test;

class HopComponentAuthoringCodeTest {
  public enum Algorithm {
    MD5,
    SHA256
  }

  public static class AlgorithmFixture {
    @HopMetadataProperty(key = "checksumtype", injectionKey = "TYPE")
    private Algorithm algorithm = Algorithm.MD5;

    public Algorithm getAlgorithm() {
      return algorithm;
    }

    public void setAlgorithm(Algorithm value) {
      algorithm = value;
    }
  }

  public static class StructuralTypeFixture {
    @HopMetadataProperty(key = "type", injectionKey = "TYPE")
    private String type;

    public String getType() {
      return type;
    }

    public void setType(String value) {
      type = value;
    }
  }

  @Test
  void enumConfigurationTypeDoesNotPermitStructuralTypeMutation() throws Exception {
    HopEnvironment.init();
    HopComponentAuthoring authoring =
        new HopComponentAuthoring(PluginRegistry.getInstance(), new MemoryMetadataProvider());
    AlgorithmFixture fixture = new AlgorithmFixture();
    authoring.updateComponent(fixture, Map.of("TYPE", "SHA256"), null);
    assertEquals(Algorithm.SHA256, fixture.algorithm);
    assertThrows(
        IllegalArgumentException.class,
        () ->
            authoring.updateComponent(
                new StructuralTypeFixture(), Map.of("TYPE", "replacement"), null));
  }

  @Test
  void dataGridInjectionRetainsInteriorAndTrailingEmptyCells() {
    assertEquals(
        List.of("GSM", "", ""),
        HopComponentAuthoring.dataGridCells(
            Map.of("injectionDatalines", "GSM\t\t", "splitter", "\t"), 3));
    assertEquals(
        List.of("", ""), HopComponentAuthoring.dataGridCells(Map.of("injectionDatalines", ","), 2));
    assertThrows(
        IllegalArgumentException.class,
        () -> HopComponentAuthoring.dataGridCells(Map.of("injectionDatalines", "GSM"), 3));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            HopComponentAuthoring.dataGridCells(
                Map.of("injectionDatalines", "GSM", "splitter", ""), 1));
  }

  public static class Field {
    @HopMetadataProperty(
        injectionKey = "TYPE",
        intCodeConverter = ValueMetaBase.ValueTypeCodeConverter.class)
    public int type;

    public int getType() {
      return type;
    }

    public void setType(int type) {
      this.type = type;
    }
  }

  public static class Fixture {
    @HopMetadataProperty(groupKey = "fields", injectionGroupKey = "FIELDS")
    public List<Field> fields = new ArrayList<>();

    @HopMetadataProperty(
        injectionKey = "SCALAR_TYPE",
        intCodeConverter = ValueMetaBase.ValueTypeCodeConverter.class)
    public int scalarType;

    public List<Field> getFields() {
      return fields;
    }

    public void setFields(List<Field> fields) {
      this.fields = fields;
    }

    public int getScalarType() {
      return scalarType;
    }

    public void setScalarType(int scalarType) {
      this.scalarType = scalarType;
    }
  }

  @Test
  void advertisesAndInjectsNativeTypeCodesWithoutSilentlyProducingNone() throws Exception {
    HopEnvironment.init();
    HopComponentAuthoring authoring =
        new HopComponentAuthoring(PluginRegistry.getInstance(), new MemoryMetadataProvider());
    var info = new BeanInjectionInfo<>(Fixture.class);
    var property = info.getProperties().get("TYPE");
    assertEquals("string", authoring.propertyRow(property).get("value_type"));
    assertTrue(
        ((List<?>) authoring.propertyRow(property).get("allowed_values"))
            .containsAll(List.of("String", "Integer")));
    Fixture fixture = new Fixture();
    authoring.updateComponent(
        fixture,
        Map.of("SCALAR_TYPE", 2),
        Map.of("FIELDS", List.of(Map.of("TYPE", 5), Map.of("TYPE", "String"))));
    assertEquals(2, fixture.scalarType);
    assertEquals(List.of(5, 2), fixture.fields.stream().map(field -> field.type).toList());
    for (Object invalid : List.of(0, 999, 2.5, "5", "NotAType")) {
      assertThrows(
          IllegalArgumentException.class,
          () -> authoring.updateComponent(new Fixture(), Map.of("SCALAR_TYPE", invalid), null));
      assertThrows(
          IllegalArgumentException.class,
          () ->
              authoring.updateComponent(
                  new Fixture(), null, Map.of("FIELDS", List.of(Map.of("TYPE", invalid)))));
    }
  }
}
