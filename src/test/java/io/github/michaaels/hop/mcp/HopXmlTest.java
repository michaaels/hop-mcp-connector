package io.github.michaaels.hop.mcp;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HopXmlTest {
  @TempDir Path temp;
  private static final String PIPELINE =
      """
      <?xml version="1.0"?>
      <pipeline><info><name>demo</name></info>
      <order><hop><from>Input</from><to>Output</to><enabled>Y</enabled></hop></order>
      <transform><name>Input</name><type>TableInput</type><sql>select * from schema.table_a</sql><password>topsecret</password><token>token-123</token><client_secret>client-secret-456</client_secret><api_key>api-key-789</api_key></transform>
      <transform><name>Output</name><type>TextFileOutput</type><filename>child.hwf</filename></transform>
      </pipeline>
      """;

  @Test
  void inspectAndRedact() throws Exception {
    var inspect = HopXml.inspect("demo.hpl", PIPELINE);
    assertEquals("pipeline", inspect.get("type"));
    assertTrue(inspect.toString().contains("schema.table_a"));
    assertFalse(inspect.toString().contains("topsecret"));
    assertFalse(inspect.toString().contains("token-123"));
    assertFalse(inspect.toString().contains("client-secret-456"));
    assertFalse(inspect.toString().contains("api-key-789"));

    var component = HopXml.component("demo.hpl", PIPELINE, "Input");
    assertTrue(component.toString().contains("***REDACTED***"));
    assertTrue(component.toString().contains("schema.table_a"));
    assertFalse(component.toString().contains("topsecret"));
    assertFalse(component.toString().contains("token-123"));
    assertFalse(component.toString().contains("client-secret-456"));
    assertFalse(component.toString().contains("api-key-789"));
  }

  @Test
  void validatesAndTraverses() throws Exception {
    assertEquals(true, HopXml.validate("demo.hpl", PIPELINE).get("valid"));
    var edges = HopXml.lineage(PIPELINE, "Input", "downstream", 10);
    assertEquals(1, edges.size());
    assertEquals("Output", edges.get(0).get("to"));
  }

  @Test
  void blocksXxe() {
    String xxe =
        "<!DOCTYPE x [<!ENTITY e SYSTEM \"file:///etc/passwd\">]><pipeline><info><name>&e;</name></info></pipeline>";
    assertThrows(Exception.class, () -> HopXml.parse(xxe));
  }

  @Test
  void hopDoctypeOptInDoesNotRelaxTheMcpParser() throws Exception {
    String key = org.apache.hop.core.Const.XML_ALLOW_DOCTYPE_DECL;
    String previous = System.getProperty(key);
    try {
      System.setProperty(key, "Y");
      assertThrows(
          Exception.class,
          () ->
              HopXml.parse(
                  "<!DOCTYPE pipeline [<!ENTITY value 'expanded'>]><pipeline>&value;</pipeline>"));
      Path secret = Files.writeString(temp.resolve("external.txt"), "external-secret");
      assertThrows(
          Exception.class,
          () ->
              HopXml.parse(
                  "<!DOCTYPE pipeline [<!ENTITY value SYSTEM '"
                      + secret.toUri()
                      + "'>]><pipeline>&value;</pipeline>"));
      var document =
          HopXml.parse(
              "<pipeline xmlns:xi='http://www.w3.org/2001/XInclude'><xi:include href='"
                  + secret.toUri()
                  + "' parse='text'/></pipeline>");
      assertFalse(document.getDocumentElement().getTextContent().contains("external-secret"));
      assertEquals(1, document.getElementsByTagName("xi:include").getLength());
    } finally {
      if (previous == null) System.clearProperty(key);
      else System.setProperty(key, previous);
    }
  }

  @Test
  void projectRootRejectsEscape() throws Exception {
    Files.writeString(temp.resolve("a.txt"), "ok");
    ProjectFiles files = new ProjectFiles(temp);
    assertEquals("ok", files.readText("a.txt"));
    assertThrows(Exception.class, () -> files.resolve("../outside"));
  }

  @Test
  void catalogPaginatesAndFingerprintsProjectFiles() throws Exception {
    Files.writeString(temp.resolve("a.hpl"), PIPELINE);
    Files.writeString(temp.resolve("b.txt"), "hello");
    ProjectFiles files = new ProjectFiles(temp);
    var page = files.catalog("**", 0, 1);
    assertEquals(2, page.get("count"));
    assertEquals(1, page.get("returned"));
    assertEquals(true, page.get("has_more"));
    assertTrue(page.toString().contains("sha256"));
  }

  @Test
  void contextResolvesDependenciesFromDefinitionDirectory() throws Exception {
    Path flows = Files.createDirectory(temp.resolve("flows"));
    Files.writeString(flows.resolve("parent.hpl"), PIPELINE);
    Files.writeString(flows.resolve("child.hwf"), "<workflow><name>child</name></workflow>");
    HopMcpService service = new HopMcpService(new ProjectFiles(temp), null, null, false);
    var context = service.context("flows/parent.hpl");
    assertTrue(context.toString().contains("flows/child.hwf"));
    assertTrue(context.toString().contains("validation"));
  }

  @Test
  void textRedactionCoversJsonAndAuthorizationHeaders() {
    String redacted = HopXml.redact("{\"token\":\"server-secret\"} Authorization: Bearer abc123");
    assertFalse(redacted.contains("server-secret"));
    assertFalse(redacted.contains("abc123"));
    assertTrue(redacted.contains("***REDACTED***"));
  }
}
