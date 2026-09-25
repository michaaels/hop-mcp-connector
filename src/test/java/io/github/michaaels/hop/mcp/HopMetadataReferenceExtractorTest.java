package io.github.michaaels.hop.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.hop.metadata.api.HopMetadata;
import org.apache.hop.metadata.api.HopMetadataBase;
import org.apache.hop.metadata.api.HopMetadataProperty;
import org.apache.hop.metadata.api.HopMetadataPropertyType;
import org.apache.hop.metadata.api.IHopMetadata;
import org.apache.hop.metadata.serializer.memory.MemoryMetadataProvider;
import org.junit.jupiter.api.Test;

class HopMetadataReferenceExtractorTest {
  @Test
  void readsAnnotatedMetadataPropertiesAsTypedReferences() {
    Map<HopMetadataReferenceExtractor.ReferenceKey, HopProjectDefinitionIndex.MetadataReference>
        references = new LinkedHashMap<>();
    HopMetadataReferenceExtractor.addAnnotatedProperties(
        new AnnotatedConnection("DWH_PROD"),
        "Table Input",
        Map.of(HopMetadataPropertyType.RDBMS_CONNECTION, List.of("rdbms")),
        references,
        new boolean[1]);

    HopProjectDefinitionIndex.MetadataReference reference = references.values().iterator().next();
    assertEquals("rdbms", reference.type());
    assertEquals("DWH_PROD", reference.name());
    assertEquals("Table Input", reference.component());
    assertEquals(HopProjectDefinitionIndex.ReferenceSource.METADATA_PROPERTY, reference.source());
  }

  @Test
  void nativeDependenciesKeepTheirExactMetadataType() {
    Map<HopMetadataReferenceExtractor.ReferenceKey, HopProjectDefinitionIndex.MetadataReference>
        references = new LinkedHashMap<>();
    HopMetadataReferenceExtractor.addNative(
        Map.of(RdbmsMetadata.class, List.of("DWH_PROD")),
        "Table Input",
        references,
        new boolean[1]);

    HopProjectDefinitionIndex.MetadataReference reference = references.values().iterator().next();
    assertEquals("rdbms", reference.type());
    assertEquals("DWH_PROD", reference.name());
    assertEquals(HopProjectDefinitionIndex.ReferenceSource.NATIVE, reference.source());
  }

  @Test
  void metadataReferenceFieldsStayBoundedAndSignalTruncation() {
    Map<HopMetadataReferenceExtractor.ReferenceKey, HopProjectDefinitionIndex.MetadataReference>
        references = new LinkedHashMap<>();
    boolean[] truncated = new boolean[1];

    HopMetadataReferenceExtractor.addAnnotatedProperties(
        new AnnotatedConnection("DWH_PROD"),
        "c".repeat(257),
        Map.of(HopMetadataPropertyType.RDBMS_CONNECTION, List.of("rdbms")),
        references,
        truncated);

    assertTrue(truncated[0]);
    assertTrue(references.isEmpty());
  }

  @Test
  void pipelineAndWorkflowFallbackOnlyReadsExactConnectionLeafProperties() throws Exception {
    HopMetadataReferenceExtractor extractor =
        new HopMetadataReferenceExtractor(new TestMetadataProvider(), null);
    String pipeline =
        "<pipeline><transform><name>Input</name><type>UnknownPlugin</type>"
            + "<connection>DWH_PROD</connection><sql>DWH_PROD</sql>"
            + "<description>DWH_PROD</description></transform><note>DWH_PROD</note></pipeline>";
    String workflow =
        "<workflow><action><name>Run</name><type>UnknownAction</type>"
            + "<connection>DWH_PROD</connection><description>DWH_PROD</description>"
            + "</action><note>DWH_PROD</note></workflow>";

    HopMetadataReferenceExtractor.Result pipelineResult =
        extractor.extract("uses.hpl", HopXml.parse(pipeline));
    HopMetadataReferenceExtractor.Result workflowResult =
        extractor.extract("uses.hwf", HopXml.parse(workflow));

    assertTrue(pipelineResult.references().stream().anyMatch(ref -> ref.name().equals("DWH_PROD")));
    assertTrue(workflowResult.references().stream().anyMatch(ref -> ref.name().equals("DWH_PROD")));
    assertTrue(
        pipelineResult.references().stream()
            .allMatch(
                ref -> ref.source() == HopProjectDefinitionIndex.ReferenceSource.TEXT_FALLBACK));
    assertTrue(
        workflowResult.references().stream()
            .allMatch(
                ref -> ref.source() == HopProjectDefinitionIndex.ReferenceSource.TEXT_FALLBACK));

    String textOnlyPipeline =
        "<pipeline><transform><name>DWH_PROD</name><type>UnknownPlugin</type>"
            + "<sql>DWH_PROD</sql><description>rdbms:DWH_PROD</description></transform>"
            + "<note>DWH_PROD</note></pipeline>";
    String textOnlyWorkflow =
        "<workflow><action><name>DWH_PROD</name><type>UnknownAction</type>"
            + "<description>DWH_PROD</description></action><note>rdbms:DWH_PROD</note></workflow>";
    assertFalse(
        extractor.extract("text.hpl", HopXml.parse(textOnlyPipeline)).references().stream()
            .anyMatch(ref -> ref.name().equalsIgnoreCase("DWH_PROD")));
    assertFalse(
        extractor.extract("text.hwf", HopXml.parse(textOnlyWorkflow)).references().stream()
            .anyMatch(ref -> ref.name().equalsIgnoreCase("DWH_PROD")));
  }

  private static final class AnnotatedConnection {
    @HopMetadataProperty(hopMetadataPropertyType = HopMetadataPropertyType.RDBMS_CONNECTION)
    private final String connection;

    private AnnotatedConnection(String connection) {
      this.connection = connection;
    }
  }

  @HopMetadata(
      key = "rdbms",
      name = "RDBMS Connection",
      description = "Test metadata type",
      hopMetadataPropertyType = HopMetadataPropertyType.RDBMS_CONNECTION)
  public static final class RdbmsMetadata extends HopMetadataBase implements IHopMetadata {
    public RdbmsMetadata() {}
  }

  private static final class TestMetadataProvider extends MemoryMetadataProvider {
    @Override
    @SuppressWarnings({"unchecked", "rawtypes"})
    public <T extends IHopMetadata> List<Class<T>> getMetadataClasses() {
      return (List) List.of(RdbmsMetadata.class);
    }
  }
}
