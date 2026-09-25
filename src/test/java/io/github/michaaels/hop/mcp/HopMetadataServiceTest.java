package io.github.michaaels.hop.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.hop.core.exception.HopException;
import org.apache.hop.metadata.api.HopMetadata;
import org.apache.hop.metadata.api.HopMetadataBase;
import org.apache.hop.metadata.api.HopMetadataProperty;
import org.apache.hop.metadata.api.HopMetadataPropertyType;
import org.apache.hop.metadata.api.IHopMetadata;
import org.apache.hop.metadata.serializer.memory.MemoryMetadataProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HopMetadataServiceTest {
  @TempDir Path project;

  @Test
  void listsGetsAndRedactsNativeMetadataWithBounds() throws Exception {
    TestMetadataProvider provider = new TestMetadataProvider();
    provider
        .getSerializer(SecretMetadata.class)
        .save(new SecretMetadata("DWH_PROD", "db.example", "super-secret"));
    HopMetadataService service = new HopMetadataService(new ProjectFiles(project), provider);

    Map<String, Object> types = service.types(0, 1);
    assertEquals(1, types.get("returned"));
    assertEquals("secret-test", ((Map<?, ?>) ((List<?>) types.get("types")).get(0)).get("key"));

    Map<String, Object> listed = service.list("secret-test", "DWH", 0, 1);
    assertEquals(1, listed.get("count"));
    assertEquals("DWH_PROD", ((Map<?, ?>) ((List<?>) listed.get("objects")).get(0)).get("name"));

    Map<String, Object> fetched = service.get("secret-test", "DWH_PROD");
    Map<?, ?> metadata = (Map<?, ?>) fetched.get("metadata");
    assertEquals("db.example", metadata.get("host"));
    assertEquals(SensitiveData.redactedMarker(), metadata.get("password"));
    assertEquals(
        SensitiveData.redactedMarker(), ((Map<?, ?>) metadata.get("attributes")).get("token"));
    assertFalse(JsonUtil.toJson(fetched).contains("super-secret"));

    assertThrows(McpException.class, () -> service.get("secret-test", "missing"));
    assertThrows(IllegalArgumentException.class, () -> service.list("secret-test", "", 0, 201));
  }

  @Test
  void findsDefinitionAndComponentDependenciesWithinTheProject() throws Exception {
    TestMetadataProvider provider = new TestMetadataProvider();
    provider
        .getSerializer(SecretMetadata.class)
        .save(new SecretMetadata("DWH_PROD", "db.example", "super-secret"));
    Files.writeString(
        project.resolve("flow.hpl"),
        "<?xml version=\"1.0\"?><pipeline><info><name>flow</name></info>"
            + "<transform><name>Input</name><type>Dummy</type>"
            + "<connection>DWH_PROD</connection></transform></pipeline>");
    HopMetadataService service = new HopMetadataService(new ProjectFiles(project), provider);

    Map<String, Object> dependencies = service.dependencies("secret-test", "DWH_PROD", 0, 10);
    assertEquals(1, dependencies.get("count"));
    Map<?, ?> dependency = (Map<?, ?>) ((List<?>) dependencies.get("used_by")).get(0);
    assertEquals("flow.hpl", dependency.get("path"));
    assertEquals("Input", dependency.get("component"));
    assertEquals("text_fallback", dependency.get("reference_source"));
  }

  @HopMetadata(
      key = "secret-test",
      name = "Secret Test",
      description = "Test metadata",
      hopMetadataPropertyType = HopMetadataPropertyType.RDBMS_CONNECTION)
  public static final class SecretMetadata extends HopMetadataBase implements IHopMetadata {
    @HopMetadataProperty private String host;

    @HopMetadataProperty(password = true)
    private String password;

    @HopMetadataProperty private Map<String, String> attributes = new LinkedHashMap<>();

    public SecretMetadata() {}

    SecretMetadata(String name, String host, String password) {
      super(name);
      this.host = host;
      this.password = password;
      this.attributes.put("token", password);
      this.attributes.put("region", "us-east-1");
    }
  }

  private static final class TestMetadataProvider extends MemoryMetadataProvider {
    @Override
    @SuppressWarnings({"unchecked", "rawtypes"})
    public <T extends IHopMetadata> List<Class<T>> getMetadataClasses() {
      return (List) List.of(SecretMetadata.class);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T extends IHopMetadata> Class<T> getMetadataClassForKey(String key)
        throws HopException {
      if ("secret-test".equals(key)) return (Class<T>) SecretMetadata.class;
      return super.getMetadataClassForKey(key);
    }
  }
}
