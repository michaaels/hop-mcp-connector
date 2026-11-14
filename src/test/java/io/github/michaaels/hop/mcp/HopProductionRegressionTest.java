package io.github.michaaels.hop.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapperSupplier;
import io.modelcontextprotocol.json.schema.jackson3.JacksonJsonSchemaValidatorSupplier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.hop.core.variables.Variables;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HopProductionRegressionTest {
  @TempDir Path project;

  @Test
  void componentSchemaIsCompactAndRetainsDepthPropertyAndArrayBounds() throws Exception {
    Map<String, Object> schema = HopMcpServer.outputSchemaFor("hop_component");
    var mapper = new JacksonMcpJsonMapperSupplier().get();
    assertTrue(
        mapper.writeValueAsString(schema).getBytes(StandardCharsets.UTF_8).length < 32 * 1024);
    Map<String, Object> config = Map.of("value", "ok");
    for (int depth = 0; depth < HopXml.MAX_COMPONENT_CONFIG_DEPTH; depth++)
      config = Map.of("nested", config);
    assertConfigConforms(schema, config, true);
    assertConfigConforms(schema, Map.of("nested", config), false);
    assertConfigConforms(schema, Map.of("long", "x".repeat(1025)), false);
    assertConfigConforms(schema, Map.of("items", java.util.Collections.nCopies(101, "x")), false);
    Map<String, Object> tooManyProperties = new LinkedHashMap<>();
    for (int index = 0; index <= 128; index++) tooManyProperties.put("key" + index, "x");
    assertConfigConforms(schema, tooManyProperties, false);
  }

  private static void assertConfigConforms(
      Map<String, Object> schema, Map<String, Object> config, boolean expected) {
    var result =
        new JacksonJsonSchemaValidatorSupplier()
            .get()
            .validate(
                schema,
                Map.of(
                    "path",
                    "test.hpl",
                    "name",
                    "Execute",
                    "kind",
                    "transform",
                    "config",
                    config,
                    "config_truncated",
                    false,
                    "tables",
                    List.of(),
                    "references",
                    List.of()));
    assertEquals(expected, result.valid(), result.errorMessage());
  }

  @Test
  void dependencyExtractionUsesDeclaredPathsAndIgnoresNotesScriptsAndSql() throws Exception {
    String xml =
        """
        <pipeline><info><name>parent</name><description>historical.hwf</description></info>
        <notepads><notepad><filename>note.hwf</filename></notepad></notepads>
        <transform><name>Execute</name><type>WorkflowExecutor</type>
        <filename>${Internal.Pipeline.Filename.Directory}/child with spaces.hwf</filename>
        <description>another.hwf</description><script>load('script.hpl')</script>
        <sql>select 'query.hpl'</sql></transform></pipeline>
        """;
    var refs = HopXml.references(xml);
    assertEquals(
        java.util.Set.of("${Internal.Pipeline.Filename.Directory}/child with spaces.hwf"), refs);
    assertEquals(refs, HopXml.inspect("parent.hpl", xml).get("references"));
    assertEquals(refs, HopXml.component("parent.hpl", xml, "Execute").get("references"));
  }

  @Test
  void resolvesFileLocalVariablesBeforeInheritedContextAndSupportsFileUris() throws Exception {
    // Hosted Windows can expose an 8.3 temp alias; use the same real root as ProjectFiles.
    project = project.toRealPath();
    Path flows = Files.createDirectories(project.resolve("flows with spaces"));
    Files.writeString(flows.resolve("child.hwf"), "<workflow><name>child</name></workflow>");
    Variables inherited = new Variables();
    inherited.setVariable("PROJECT_HOME", "wrong-root");
    inherited.setVariable("Internal.Pipeline.Filename.Directory", "wrong-folder");
    inherited.setVariable("FILE_URI", flows.resolve("child.hwf").toUri().toString());
    HopMcpService service = new HopMcpService(new ProjectFiles(project), inherited, null, false);
    for (String reference :
        List.of(
            "${Internal.Pipeline.Filename.Directory}/child.hwf",
            "${Internal.Workflow.Filename.Folder}/child.hwf",
            "${Internal.Entry.Current.Folder}/child.hwf",
            "${FILE_URI}",
            "${PROJECT_HOME}/flows with spaces/child.hwf")) {
      Files.writeString(flows.resolve("parent.hpl"), definition(reference));
      Map<?, ?> dependency = firstDependency(service.dependencies("flows with spaces/parent.hpl"));
      assertEquals(true, dependency.get("exists"), reference);
      assertEquals("resolved", dependency.get("resolution_status"));
      assertEquals("flows with spaces/child.hwf", dependency.get("resolved"));
    }
  }

  @Test
  void distinguishesUnknownVariablesMissingFilesAndRejectedPaths() throws Exception {
    var service = new HopMcpService(new ProjectFiles(project), new Variables(), null, false);
    for (String reference : List.of("${NOT_CONFIGURED}/child.hwf", "${CHILD_FILE}")) {
      Files.writeString(project.resolve("parent.hpl"), definition(reference));
      var dependency = firstDependency(service.dependencies("parent.hpl"));
      assertNull(dependency.get("exists"));
      assertEquals("unresolved", dependency.get("resolution_status"));
    }
    Files.writeString(project.resolve("parent.hpl"), definition("missing.hwf"));
    var missing = firstDependency(service.dependencies("parent.hpl"));
    assertEquals(false, missing.get("exists"));
    assertEquals("missing", missing.get("resolution_status"));
    for (String reference :
        List.of("../outside.hwf", "${PROJECT_HOME}/.hop-mcp/backups/private.hwf")) {
      Files.writeString(project.resolve("parent.hpl"), definition(reference));
      var rejected = firstDependency(service.dependencies("parent.hpl"));
      assertEquals("rejected", rejected.get("resolution_status"));
      assertNull(rejected.get("exists"));
    }
  }

  @Test
  void workflowInspectionAndLineageRetainSuccessFailureAndUnconditionalEdges() throws Exception {
    String xml =
        """
        <workflow><name>flow</name><actions><action><name>A</name><type>SPECIAL</type></action>
        <action><name>B</name><type>DUMMY</type></action></actions><hops>
        <hop><from>A</from><to>B</to><enabled>Y</enabled><evaluation>N</evaluation><unconditional>N</unconditional></hop>
        </hops></workflow>
        """;
    Map<?, ?> edge = (Map<?, ?>) ((List<?>) HopXml.inspect("flow.hwf", xml).get("hops")).get(0);
    assertEquals(false, edge.get("evaluation"));
    assertEquals(false, edge.get("unconditional"));
    assertEquals(List.of("failure"), edge.get("branches"));
    assertEquals(
        edge.get("branches"), HopXml.lineage(xml, "A", "downstream", 2).get(0).get("branches"));
    String unconditional = xml.replace("<unconditional>N", "<unconditional>Y");
    assertEquals(
        List.of("unconditional"),
        HopXml.lineage(unconditional, "A", "downstream", 2).get(0).get("branches"));
  }

  @Test
  void filterAndSwitchEdgesHaveBranchSemanticsWithoutExposingCaseValues() throws Exception {
    String xml =
        """
        <pipeline><transform><name>F</name><type>FilterRows</type><send_true_to>S</send_true_to></transform>
        <transform><name>S</name><type>SwitchCase</type><fieldname>status</fieldname>
        <default_target_transform>Done</default_target_transform><cases><case><value>secret-case-value</value>
        <target_transform>Done</target_transform></case></cases></transform>
        <transform><name>Done</name><type>Dummy</type></transform><order>
        <hop><from>F</from><to>S</to><enabled>Y</enabled></hop>
        <hop><from>S</from><to>Done</to><enabled>Y</enabled></hop></order></pipeline>
        """;
    var edges = HopXml.lineage(xml, "F", "downstream", 3);
    assertEquals(List.of("true"), edges.get(0).get("branches"));
    assertTrue(((List<?>) edges.get(1).get("branches")).containsAll(List.of("case", "default")));
    assertEquals("status", edges.get(1).get("routing_field"));
    assertFalse(edges.toString().contains("secret-case-value"));
  }

  @Test
  void validationReportsMissingTargetsDisconnectedDefaultsAndLegacyExecutorProperties()
      throws Exception {
    String xml =
        """
        <pipeline><transform><name>Filter</name><type>FilterRows</type><send_true_to>Missing</send_true_to></transform>
        <transform><name>Switch</name><type>SwitchCase</type><default_target_transform>Done</default_target_transform></transform>
        <transform><name>Executor</name><type>WorkflowExecutor</type><execution_result_target_step>Done</execution_result_target_step>
        <filename>${Internal.Pipeline.Filename.Folder}/child.hwf</filename></transform>
        <transform><name>Done</name><type>Dummy</type></transform></pipeline>
        """;
    var validation = HopXml.validate("flow.hpl", xml);
    assertEquals(false, validation.get("valid"));
    assertTrue(validation.get("errors").toString().contains("Missing"));
    assertTrue(validation.get("warnings").toString().contains("legacy"));
    assertTrue(validation.get("warnings").toString().contains("no enabled outgoing hop"));
    assertTrue(validation.get("warnings").toString().contains("Filename.Directory"));
    assertEquals("structural", validation.get("validation_level"));
    assertEquals(false, validation.get("execution_validated"));
  }

  @Test
  void routingDiagnosticsRespectTheExistingBound() throws Exception {
    StringBuilder xml = new StringBuilder("<pipeline>");
    for (int index = 0; index < 250; index++) {
      xml.append("<transform><name>F")
          .append(index)
          .append("</name><type>FilterRows</type><send_true_to>Missing</send_true_to></transform>");
    }
    var result = HopXml.validate("many.hpl", xml.append("</pipeline>").toString());
    assertEquals(ProjectFiles.MAX_RESULTS, ((List<?>) result.get("errors")).size());
    assertEquals(true, result.get("diagnostics_truncated"));
  }

  @Test
  void repeatValidationRejectsIncompleteActionsWithoutExecutingTheirChildren() throws Exception {
    String empty =
        "<workflow><actions><action><name>Repeat</name><type>Repeat</type></action></actions></workflow>";
    var invalid = HopXml.validate("repeat.hwf", empty);
    assertEquals(false, invalid.get("valid"));
    assertTrue(invalid.get("errors").toString().contains("filename"));
    assertTrue(invalid.get("errors").toString().contains("run_configuration"));
    String configured =
        empty.replace(
            "</action>",
            "<filename>${PROJECT_HOME}/child.hwf</filename><run_configuration>local</run_configuration>"
                + "<variable_name>RETRY_DONE</variable_name><delay>${RETRY_DELAY_SECONDS}</delay></action>");
    var valid = HopXml.validate("repeat.hwf", configured);
    assertEquals(true, valid.get("valid"));
    assertEquals(List.of(), valid.get("warnings"));
    assertEquals(false, valid.get("execution_validated"));
    for (String delay : List.of("-1", "0.5", "not-a-number", "9223372036854775807")) {
      var badDelay =
          HopXml.validate("repeat.hwf", configured.replace("${RETRY_DELAY_SECONDS}", delay));
      assertEquals(false, badDelay.get("valid"));
      assertTrue(badDelay.get("errors").toString().contains("delay"));
    }
    var endRepeatChild =
        HopXml.validate(
            "repeat.hwf", configured.replace("<variable_name>RETRY_DONE</variable_name>", ""));
    assertEquals(true, endRepeatChild.get("valid"));
    assertTrue(endRepeatChild.get("warnings").toString().contains("End repeat"));
  }

  private static String definition(String reference) {
    return "<pipeline><transform><name>Execute</name><type>WorkflowExecutor</type><filename>"
        + reference
        + "</filename></transform></pipeline>";
  }

  private static Map<?, ?> firstDependency(Map<String, Object> result) {
    return (Map<?, ?>) ((List<?>) result.get("dependencies")).get(0);
  }
}
