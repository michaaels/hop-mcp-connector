package io.github.michaaels.hop.mcp;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.hop.core.HopEnvironment;
import org.apache.hop.core.variables.Variables;
import org.apache.hop.metadata.serializer.memory.MemoryMetadataProvider;
import org.apache.hop.pipeline.PipelineHopMeta;
import org.apache.hop.pipeline.PipelineMeta;
import org.apache.hop.pipeline.transform.TransformMeta;
import org.apache.hop.pipeline.transforms.dummy.DummyMeta;
import org.apache.hop.pipeline.transforms.injector.InjectorField;
import org.apache.hop.pipeline.transforms.injector.InjectorMeta;
import org.apache.hop.workflow.WorkflowMeta;
import org.apache.hop.workflow.action.ActionMeta;
import org.apache.hop.workflow.actions.dummy.ActionDummy;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HopDefinitionMutatorTest {
  @TempDir Path project;

  @BeforeAll
  static void initializeHopPlugins() throws Exception {
    HopEnvironment.init();
  }

  @Test
  void createsNativePipelineOnlyAfterApplyAndCanRollbackCreation() throws Exception {
    Variables variables = new Variables();
    MemoryMetadataProvider metadataProvider = new MemoryMetadataProvider();
    ProjectFiles files = new ProjectFiles(project);
    List<Path> invalidated = new ArrayList<>();
    HopDefinitionMutator mutator =
        new HopDefinitionMutator(
            files, variables, metadataProvider, HopSemanticEventSink.NONE, invalidated::add);
    List<Map<String, Object>> operations =
        List.of(
            Map.of("operation", "set_name", "value", "Generated pipeline"),
            Map.of("operation", "set_description", "value", "Created semantically"));

    Map<String, Object> preview =
        mutator.mutate("generated.hpl", "pipeline", operations, null, false);

    assertEquals(true, preview.get("preview"));
    assertEquals(true, preview.get("native_reload_valid"));
    assertFalse(Files.exists(project.resolve("generated.hpl")));

    Map<String, Object> applied =
        mutator.mutate("generated.hpl", "pipeline", operations, null, true);
    assertEquals(true, applied.get("applied"));
    assertEquals(true, applied.get("rollback_available"));
    assertEquals(List.of(project.resolve("generated.hpl")), invalidated);

    PipelineMeta pipeline =
        new PipelineMeta(
            Files.newInputStream(project.resolve("generated.hpl")), metadataProvider, variables);
    assertEquals("Generated pipeline", pipeline.getName());
    assertEquals("Created semantically", pipeline.getDescription());

    Map<String, Object> rolledBack =
        mutator.rollback(
            String.valueOf(applied.get("transaction_id")),
            String.valueOf(applied.get("new_sha256")));
    assertEquals(true, rolledBack.get("rolled_back"));
    assertFalse(Files.exists(project.resolve("generated.hpl")));
    assertEquals(
        List.of(project.resolve("generated.hpl"), project.resolve("generated.hpl")), invalidated);
  }

  @Test
  void mutatesExistingWorkflowWithHashBackupReloadAndRollback() throws Exception {
    Variables variables = new Variables();
    MemoryMetadataProvider metadataProvider = new MemoryMetadataProvider();
    WorkflowMeta original = new WorkflowMeta();
    original.setFilename(project.resolve("flow.hwf").toString());
    original.setNameSynchronizedWithFilename(false);
    original.setName("Original workflow");
    byte[] originalXml = original.getXml(variables).getBytes(StandardCharsets.UTF_8);
    Files.write(project.resolve("flow.hwf"), originalXml);

    ProjectFiles files = new ProjectFiles(project);
    List<Path> invalidated = new ArrayList<>();
    HopDefinitionMutator mutator =
        new HopDefinitionMutator(
            files, variables, metadataProvider, HopSemanticEventSink.NONE, invalidated::add);
    String oldHash = ProjectFiles.sha256(originalXml);
    List<Map<String, Object>> operations =
        List.of(Map.of("operation", "set_name", "value", "Corrected workflow"));

    assertThrows(
        Exception.class, () -> mutator.mutate("flow.hwf", "workflow", operations, "wrong", true));

    Map<String, Object> preview = mutator.mutate("flow.hwf", "workflow", operations, null, false);
    assertEquals(false, preview.get("applied"));
    assertEquals(oldHash, ProjectFiles.sha256(Files.readAllBytes(project.resolve("flow.hwf"))));

    Map<String, Object> applied = mutator.mutate("flow.hwf", "workflow", operations, oldHash, true);
    assertEquals(List.of(project.resolve("flow.hwf")), invalidated);
    String transactionId = String.valueOf(applied.get("transaction_id"));
    assertEquals("protected", applied.get("backup"));
    assertFalse(String.valueOf(applied.get("backup")).contains(".hop-mcp"));
    Path backup = project.resolve(".hop-mcp/backups/" + transactionId + "/definition.backup");
    assertTrue(Files.isRegularFile(backup));
    assertArrayEquals(originalXml, Files.readAllBytes(backup));
    assertEquals(1, files.catalog("**", 0, 200).get("count"));
    assertTrue(((List<?>) files.search("Original workflow", "**", 0, 20).get("results")).isEmpty());
    assertThrows(
        IOException.class,
        () -> files.readText(".hop-mcp/backups/" + transactionId + "/definition.backup"));
    WorkflowMeta corrected =
        new WorkflowMeta(
            Files.newInputStream(project.resolve("flow.hwf")), metadataProvider, variables);
    assertEquals("Corrected workflow", corrected.getName());

    assertThrows(
        Exception.class,
        () -> mutator.rollback(String.valueOf(applied.get("transaction_id")), oldHash));
    mutator.rollback(
        String.valueOf(applied.get("transaction_id")), String.valueOf(applied.get("new_sha256")));
    WorkflowMeta restored =
        new WorkflowMeta(
            Files.newInputStream(project.resolve("flow.hwf")), metadataProvider, variables);
    assertEquals("Original workflow", restored.getName());
    assertEquals(oldHash, ProjectFiles.sha256(Files.readAllBytes(project.resolve("flow.hwf"))));
    assertEquals(List.of(project.resolve("flow.hwf"), project.resolve("flow.hwf")), invalidated);
  }

  @Test
  void refusesToEvictUnexpiredRollbackBackupsAtTransactionLimit() throws Exception {
    Variables variables = new Variables();
    MemoryMetadataProvider metadataProvider = new MemoryMetadataProvider();
    WorkflowMeta original = new WorkflowMeta();
    original.setFilename(project.resolve("bounded.hwf").toString());
    original.setNameSynchronizedWithFilename(false);
    original.setName("Original");
    byte[] originalXml = original.getXml(variables).getBytes(StandardCharsets.UTF_8);
    Files.write(project.resolve("bounded.hwf"), originalXml);

    HopDefinitionMutator mutator =
        new HopDefinitionMutator(new ProjectFiles(project), variables, metadataProvider);
    String oldestTransaction = null;
    String oldestPath = null;
    String oldestSha256 = null;
    for (int i = 0; i < HopDefinitionMutator.MAX_TRANSACTIONS; i++) {
      String relative = "bounded-" + i + ".hwf";
      Files.write(project.resolve(relative), originalXml);
      Map<String, Object> applied =
          mutator.mutate(
              relative,
              "workflow",
              List.of(Map.of("operation", "set_name", "value", "Version " + i)),
              ProjectFiles.sha256(originalXml),
              true);
      if (i == 0) {
        oldestPath = relative;
        oldestTransaction = String.valueOf(applied.get("transaction_id"));
        oldestSha256 = String.valueOf(applied.get("new_sha256"));
      }
    }
    Files.write(project.resolve("bounded-over-limit.hwf"), originalXml);
    assertThrows(
        IOException.class,
        () ->
            mutator.mutate(
                "bounded-over-limit.hwf",
                "workflow",
                List.of(Map.of("operation", "set_name", "value", "Over limit")),
                ProjectFiles.sha256(originalXml),
                true));

    mutator.rollback(oldestTransaction, oldestSha256);
    assertArrayEquals(originalXml, Files.readAllBytes(project.resolve(oldestPath)));
  }

  @Test
  void reclaimsExpiredOrphanedBackupDirectories() throws Exception {
    Path directory = project.resolve(".hop-mcp/backups/" + UUID.randomUUID());
    Files.createDirectories(directory);
    Path backup = directory.resolve("definition.backup");
    Files.writeString(backup, "expired backup");
    FileTime expired =
        FileTime.from(Instant.now().minus(HopDefinitionMutator.TRANSACTION_TTL).minusSeconds(10));
    Files.setLastModifiedTime(directory, expired);

    WorkflowMeta original = new WorkflowMeta();
    original.setFilename(project.resolve("expire.hwf").toString());
    original.setNameSynchronizedWithFilename(false);
    original.setName("Before");
    byte[] originalXml = original.getXml(new Variables()).getBytes(StandardCharsets.UTF_8);
    Files.write(project.resolve("expire.hwf"), originalXml);
    HopDefinitionMutator mutator =
        new HopDefinitionMutator(
            new ProjectFiles(project), new Variables(), new MemoryMetadataProvider());

    mutator.mutate(
        "expire.hwf",
        "workflow",
        List.of(Map.of("operation", "set_name", "value", "After")),
        ProjectFiles.sha256(originalXml),
        true);

    assertFalse(Files.exists(directory, LinkOption.NOFOLLOW_LINKS));
  }

  @Test
  void rejectsMutationWhenBackupStorageExceedsByteLimit() throws Exception {
    Path root = project.resolve(".hop-mcp/backups");
    long bytesPerBackup = ProjectFiles.MAX_READ_BYTES - 1;
    int backupCount = (int) (HopDefinitionMutator.MAX_BACKUP_BYTES / bytesPerBackup);
    for (int i = 0; i < backupCount; i++) {
      Path directory = Files.createDirectories(root.resolve(UUID.randomUUID().toString()));
      Path backup = directory.resolve("definition.backup");
      try (RandomAccessFile backupFile = new RandomAccessFile(backup.toFile(), "rw")) {
        backupFile.setLength(bytesPerBackup);
      }
    }

    WorkflowMeta original = new WorkflowMeta();
    original.setFilename(project.resolve("byte-limit.hwf").toString());
    original.setNameSynchronizedWithFilename(false);
    original.setName("Before");
    byte[] originalXml = original.getXml(new Variables()).getBytes(StandardCharsets.UTF_8);
    Path target = project.resolve("byte-limit.hwf");
    Files.write(target, originalXml);
    HopDefinitionMutator mutator =
        new HopDefinitionMutator(
            new ProjectFiles(project), new Variables(), new MemoryMetadataProvider());

    IOException failure =
        assertThrows(
            IOException.class,
            () ->
                mutator.mutate(
                    "byte-limit.hwf",
                    "workflow",
                    List.of(Map.of("operation", "set_name", "value", "After")),
                    ProjectFiles.sha256(originalXml),
                    true));

    assertTrue(failure.getMessage().contains("byte limit"));
    assertArrayEquals(originalXml, Files.readAllBytes(target));
  }

  @Test
  void rejectsMalformedBackupStateWithoutChangingDefinition() throws Exception {
    Files.createDirectories(project.resolve(".hop-mcp/backups/not-a-transaction"));
    WorkflowMeta original = new WorkflowMeta();
    original.setFilename(project.resolve("malformed.hwf").toString());
    original.setNameSynchronizedWithFilename(false);
    original.setName("Before");
    byte[] originalXml = original.getXml(new Variables()).getBytes(StandardCharsets.UTF_8);
    Path target = project.resolve("malformed.hwf");
    Files.write(target, originalXml);
    HopDefinitionMutator mutator =
        new HopDefinitionMutator(
            new ProjectFiles(project), new Variables(), new MemoryMetadataProvider());

    IOException failure =
        assertThrows(
            IOException.class,
            () ->
                mutator.mutate(
                    "malformed.hwf",
                    "workflow",
                    List.of(Map.of("operation", "set_name", "value", "After")),
                    ProjectFiles.sha256(originalXml),
                    true));

    assertTrue(failure.getMessage().contains("malformed state"));
    assertArrayEquals(originalXml, Files.readAllBytes(target));
  }

  @Test
  void refusesToRestoreCorruptedBackupContents() throws Exception {
    Variables variables = new Variables();
    MemoryMetadataProvider metadataProvider = new MemoryMetadataProvider();
    WorkflowMeta original = new WorkflowMeta();
    original.setFilename(project.resolve("corrupted-backup.hwf").toString());
    original.setNameSynchronizedWithFilename(false);
    original.setName("Before");
    byte[] originalXml = original.getXml(variables).getBytes(StandardCharsets.UTF_8);
    Path target = project.resolve("corrupted-backup.hwf");
    Files.write(target, originalXml);
    HopDefinitionMutator mutator =
        new HopDefinitionMutator(new ProjectFiles(project), variables, metadataProvider);
    Map<String, Object> applied =
        mutator.mutate(
            "corrupted-backup.hwf",
            "workflow",
            List.of(Map.of("operation", "set_name", "value", "After")),
            ProjectFiles.sha256(originalXml),
            true);
    Path backup =
        project.resolve(
            ".hop-mcp/backups/"
                + String.valueOf(applied.get("transaction_id"))
                + "/definition.backup");
    Files.writeString(backup, "tampered backup");
    byte[] mutatedXml = Files.readAllBytes(target);

    IOException failure =
        assertThrows(
            IOException.class,
            () ->
                mutator.rollback(
                    String.valueOf(applied.get("transaction_id")),
                    String.valueOf(applied.get("new_sha256"))));

    assertTrue(failure.getMessage().contains("integrity"));
    assertArrayEquals(mutatedXml, Files.readAllBytes(target));
  }

  @Test
  void rejectsSymbolicLinkBackupRoot() throws Exception {
    Path control = Files.createDirectories(project.resolve(".hop-mcp"));
    Path outside = Files.createDirectory(project.resolve("outside"));
    Path backupRoot = control.resolve("backups");
    try {
      Files.createSymbolicLink(backupRoot, outside);
    } catch (IOException | UnsupportedOperationException | SecurityException e) {
      Assumptions.assumeTrue(false, "Symbolic links are unavailable: " + e.getMessage());
      return;
    }

    WorkflowMeta original = new WorkflowMeta();
    original.setFilename(project.resolve("symlink.hwf").toString());
    original.setNameSynchronizedWithFilename(false);
    original.setName("Before");
    byte[] originalXml = original.getXml(new Variables()).getBytes(StandardCharsets.UTF_8);
    Path target = project.resolve("symlink.hwf");
    Files.write(target, originalXml);
    HopDefinitionMutator mutator =
        new HopDefinitionMutator(
            new ProjectFiles(project), new Variables(), new MemoryMetadataProvider());

    assertThrows(
        IOException.class,
        () ->
            mutator.mutate(
                "symlink.hwf",
                "workflow",
                List.of(Map.of("operation", "set_name", "value", "After")),
                ProjectFiles.sha256(originalXml),
                true));
    assertArrayEquals(originalXml, Files.readAllBytes(target));
    try (var files = Files.list(outside)) {
      assertTrue(files.findAny().isEmpty());
    }
  }

  @Test
  void serviceRequiresExplicitOptInForExecutionAndAuthoring() throws Exception {
    Files.writeString(project.resolve("flow.hpl"), "<pipeline/>");
    HopMcpService service = new HopMcpService(new ProjectFiles(project), null, null, false);
    try {
      assertThrows(
          SecurityException.class, () -> service.execute("flow.hpl", "local", Map.of(), 30));
      assertThrows(
          SecurityException.class,
          () -> service.testDefinition("flow.hpl", false, false, "local", Map.of(), 30));
      assertThrows(
          SecurityException.class,
          () -> service.testDefinition("flow.hpl", false, true, "local", Map.of(), 30));
      assertThrows(
          SecurityException.class,
          () -> service.testDefinition("flow.hpl", true, false, "local", Map.of(), 30));
      assertThrows(SecurityException.class, () -> service.logs(null, true, -1, 0));
      assertThrows(SecurityException.class, () -> service.componentTypes("pipeline", "", 0, 10));
      assertThrows(SecurityException.class, () -> service.componentSchema("pipeline", "Dummy"));
      assertThrows(
          SecurityException.class,
          () ->
              service.mutateDefinition(
                  "flow.hpl",
                  "pipeline",
                  List.of(Map.of("operation", "set_name", "value", "Changed")),
                  ProjectFiles.sha256(Files.readAllBytes(project.resolve("flow.hpl"))),
                  true));
      assertThrows(
          SecurityException.class,
          () ->
              service.mutateDefinition(
                  "flow.hpl",
                  "pipeline",
                  List.of(Map.of("operation", "set_name", "value", "Changed")),
                  ProjectFiles.sha256(Files.readAllBytes(project.resolve("flow.hpl"))),
                  false));
      assertThrows(
          SecurityException.class,
          () ->
              service.prepareCorrectionPlan(
                  "flow.hpl",
                  "pipeline",
                  List.of(Map.of("operation", "set_name", "value", "Changed"))));
      assertThrows(SecurityException.class, () -> service.correctionPlanStatus("missing-plan"));
    } finally {
      service.close();
    }
  }

  @Test
  void rejectsTraversalKindMismatchAndTooManyOperations() throws Exception {
    Variables variables = new Variables();
    HopDefinitionMutator mutator =
        new HopDefinitionMutator(
            new ProjectFiles(project), variables, new MemoryMetadataProvider());
    List<Map<String, Object>> tooMany =
        java.util.stream.IntStream.rangeClosed(0, HopDefinitionMutator.MAX_OPERATIONS)
            .mapToObj(i -> Map.<String, Object>of("operation", "set_name", "value", "n" + i))
            .toList();

    assertThrows(
        Exception.class, () -> mutator.mutate("../escape.hpl", "pipeline", List.of(), null, false));
    assertThrows(
        Exception.class, () -> mutator.mutate("flow.hpl", "workflow", List.of(), null, false));
    assertThrows(
        Exception.class, () -> mutator.mutate("flow.hpl", "pipeline", tooMany, null, false));
  }

  @Test
  void appliesStructuralPipelineOperationsThroughNativeHopObjectsAndPublishesEvent()
      throws Exception {
    Variables variables = new Variables();
    MemoryMetadataProvider metadataProvider = new MemoryMetadataProvider();
    PipelineMeta original = new PipelineMeta();
    original.setFilename(project.resolve("structural.hpl").toString());
    original.setNameSynchronizedWithFilename(false);
    original.setName("Structural pipeline");
    original.addTransform(new TransformMeta("Dummy", "Input", new DummyMeta()));
    original.addTransform(new TransformMeta("Dummy", "Output", new DummyMeta()));
    Files.writeString(project.resolve("structural.hpl"), original.getXml(variables));

    List<HopSemanticEventSink.Event> events = new ArrayList<>();
    HopDefinitionMutator mutator =
        new HopDefinitionMutator(
            new ProjectFiles(project),
            variables,
            metadataProvider,
            event -> {
              events.add(event);
              return true;
            });
    String oldHash = ProjectFiles.sha256(Files.readAllBytes(project.resolve("structural.hpl")));

    Map<String, Object> applied =
        mutator.mutate(
            "structural.hpl",
            "pipeline",
            List.of(
                Map.of("operation", "move_component", "component", "Input", "x", 120, "y", 240),
                Map.of(
                    "operation", "add_hop",
                    "from", "Input",
                    "to", "Output",
                    "enabled", false)),
            oldHash,
            true);

    PipelineMeta changed =
        new PipelineMeta(
            Files.newInputStream(project.resolve("structural.hpl")), metadataProvider, variables);
    TransformMeta input = changed.findTransform("Input");
    assertEquals(120, input.getLocation().x);
    assertEquals(240, input.getLocation().y);
    assertEquals(1, changed.nrPipelineHops());
    PipelineHopMeta hop = changed.getPipelineHop(0);
    assertEquals("Input", hop.getFromTransform().getName());
    assertEquals("Output", hop.getToTransform().getName());
    assertFalse(hop.isEnabled());
    assertEquals(true, applied.get("semantic_event_published"));
    assertEquals(1, events.size());
    assertEquals("mutation_applied", events.get(0).type());

    Map<String, Object> removed =
        mutator.mutate(
            "structural.hpl",
            "pipeline",
            List.of(
                Map.of(
                    "operation", "remove_hop",
                    "from", "Input",
                    "to", "Output"),
                Map.of("operation", "remove_component", "component", "Output")),
            String.valueOf(applied.get("new_sha256")),
            true);
    PipelineMeta withoutOutput =
        new PipelineMeta(
            Files.newInputStream(project.resolve("structural.hpl")), metadataProvider, variables);
    assertEquals(1, withoutOutput.nrTransforms());
    assertEquals(0, withoutOutput.nrPipelineHops());

    Map<String, Object> rolledBack =
        mutator.rollback(
            String.valueOf(removed.get("transaction_id")),
            String.valueOf(removed.get("new_sha256")));
    assertEquals(true, rolledBack.get("semantic_event_published"));
    assertEquals("mutation_rolled_back", events.get(2).type());
  }

  @Test
  void reportsSemanticCapabilitiesAndHopCompatibility() {
    Map<String, Object> capabilities = HopSemanticCapabilities.describe(true, false);

    assertEquals("MCP Connector for Apache Hop", capabilities.get("product"));
    assertEquals(List.of("pipeline", "workflow"), capabilities.get("definition_kinds"));
    assertTrue(((List<?>) capabilities.get("semantic_operations")).size() >= 8);
    assertEquals(List.of("2.19.0", "2.20.0-SNAPSHOT"), capabilities.get("tested_hop_versions"));
    Map<?, ?> testCycle = (Map<?, ?>) capabilities.get("test_cycle");
    assertEquals("advisory_only", testCycle.get("correction_mode"));
    assertEquals(false, testCycle.get("auto_apply"));
    Map<?, ?> correctionPlans = (Map<?, ?>) capabilities.get("correction_plans");
    assertEquals(true, correctionPlans.get("single_use"));
    assertEquals(true, correctionPlans.get("sha256_bound"));
    assertEquals(false, correctionPlans.get("auto_apply"));
    assertEquals(false, capabilities.get("live_ui_available"));
    assertEquals("headless_not_connected", capabilities.get("live_ui_status"));
  }

  @Test
  void appliesStructuralWorkflowOperationsThroughNativeHopObjects() throws Exception {
    Variables variables = new Variables();
    MemoryMetadataProvider metadataProvider = new MemoryMetadataProvider();
    WorkflowMeta original = new WorkflowMeta();
    original.setFilename(project.resolve("structural.hwf").toString());
    original.setNameSynchronizedWithFilename(false);
    original.setName("Structural workflow");
    ActionDummy startAction = new ActionDummy();
    startAction.setName("Start");
    ActionDummy finishAction = new ActionDummy();
    finishAction.setName("Finish");
    original.addAction(new ActionMeta(startAction));
    original.addAction(new ActionMeta(finishAction));
    Files.writeString(project.resolve("structural.hwf"), original.getXml(variables));

    HopDefinitionMutator mutator =
        new HopDefinitionMutator(new ProjectFiles(project), variables, metadataProvider);
    String oldHash = ProjectFiles.sha256(Files.readAllBytes(project.resolve("structural.hwf")));
    Map<String, Object> applied =
        mutator.mutate(
            "structural.hwf",
            "workflow",
            List.of(
                Map.of("operation", "move_component", "component", "Start", "x", 75, "y", 125),
                Map.of(
                    "operation", "add_hop",
                    "from", "Start",
                    "to", "Finish",
                    "enabled", true,
                    "unconditional", true)),
            oldHash,
            true);

    WorkflowMeta changed =
        new WorkflowMeta(
            Files.newInputStream(project.resolve("structural.hwf")), metadataProvider, variables);
    assertEquals(75, changed.findAction("Start").getLocation().x);
    assertEquals(125, changed.findAction("Start").getLocation().y);
    assertEquals(1, changed.nrWorkflowHops());
    assertTrue(changed.getWorkflowHop(0).isEnabled());
    assertTrue(changed.getWorkflowHop(0).isUnconditional());

    mutator.rollback(
        String.valueOf(applied.get("transaction_id")), String.valueOf(applied.get("new_sha256")));
    WorkflowMeta restored =
        new WorkflowMeta(
            Files.newInputStream(project.resolve("structural.hwf")), metadataProvider, variables);
    assertEquals(0, restored.nrWorkflowHops());
  }

  @Test
  void discoversNativeComponentsAndTheirSafeSemanticSchema() throws Exception {
    HopComponentAuthoring authoring = new HopComponentAuthoring(new MemoryMetadataProvider());

    Map<String, Object> pipelineTypes = authoring.types("pipeline", "dummy", 0, 10);
    assertTrue(((Number) pipelineTypes.get("matched_component_count")).intValue() >= 1);
    assertTrue(
        ((List<?>) pipelineTypes.get("components"))
            .stream()
                .map(Map.class::cast)
                .anyMatch(component -> "Dummy".equals(component.get("id"))));

    Map<String, Object> startSchema = authoring.schema("workflow", "SPECIAL");
    assertEquals(true, startSchema.get("native_injection_supported"));
    assertEquals(true, startSchema.get("scalar_injection_supported"));
    assertTrue(
        ((List<?>) startSchema.get("properties"))
            .stream()
                .map(Map.class::cast)
                .anyMatch(property -> "repeat".equals(property.get("key"))));
    assertFalse(
        ((List<?>) startSchema.get("properties"))
            .stream()
                .map(Map.class::cast)
                .anyMatch(
                    property ->
                        List.of("name", "type", "pluginId", "plugin_id")
                            .contains(property.get("key"))));

    Map<String, Object> injectorSchema = authoring.schema("pipeline", "Injector");
    assertEquals(true, injectorSchema.get("tabular_injection_supported"));
    assertEquals(false, injectorSchema.get("collection_properties_excluded"));
    Map<?, ?> fieldsGroup =
        ((List<?>) injectorSchema.get("property_groups"))
            .stream()
                .map(Map.class::cast)
                .filter(group -> "fields".equals(group.get("key")))
                .findFirst()
                .orElseThrow();
    List<?> fieldProperties = (List<?>) fieldsGroup.get("properties");
    assertTrue(
        fieldProperties.stream()
            .map(Map.class::cast)
            .anyMatch(property -> "name".equals(property.get("key"))));
    assertTrue(
        fieldProperties.stream()
            .map(Map.class::cast)
            .anyMatch(property -> "type".equals(property.get("key"))));
  }

  @Test
  void createsNativeTransformWithTabularInjectedFields() throws Exception {
    Variables variables = new Variables();
    MemoryMetadataProvider metadataProvider = new MemoryMetadataProvider();
    HopDefinitionMutator mutator =
        new HopDefinitionMutator(new ProjectFiles(project), variables, metadataProvider);

    Map<String, Object> applied =
        mutator.mutate(
            "tabular.hpl",
            "pipeline",
            List.of(
                Map.of(
                    "operation",
                    "add_component",
                    "plugin_id",
                    "Injector",
                    "name",
                    "Input",
                    "property_groups",
                    Map.of(
                        "fields",
                        List.of(
                            Map.of("name", "id", "type", "Integer", "length", 9, "precision", 0),
                            Map.of(
                                "name",
                                "label",
                                "type",
                                "String",
                                "length",
                                100,
                                "precision",
                                0))))),
            null,
            true);

    PipelineMeta pipeline =
        new PipelineMeta(
            Files.newInputStream(project.resolve("tabular.hpl")), metadataProvider, variables);
    InjectorMeta injector = (InjectorMeta) pipeline.findTransform("Input").getTransform();
    assertEquals(2, injector.getInjectorFields().size());
    assertEquals("id", injector.getInjectorFields().get(0).getName());
    assertEquals("Integer", injector.getInjectorFields().get(0).getType());
    assertEquals("9", injector.getInjectorFields().get(0).getLength());
    assertEquals("label", injector.getInjectorFields().get(1).getName());
    assertEquals(
        1,
        ((Number)
                ((Map<?, ?>) ((List<?>) applied.get("changes")).get(0)).get("property_group_count"))
            .intValue());
  }

  @Test
  void previewsNewDefinitionWithinBoundedNativeMutation() throws Exception {
    Variables variables = new Variables();
    MemoryMetadataProvider metadataProvider = new MemoryMetadataProvider();
    HopDefinitionMutator mutator =
        new HopDefinitionMutator(new ProjectFiles(project), variables, metadataProvider);

    Map<String, Object> preview =
        mutator.mutate(
            "native-preview.hpl",
            "pipeline",
            List.of(
                Map.of(
                    "operation",
                    "add_component",
                    "plugin_id",
                    "Dummy",
                    "name",
                    "Fixture input",
                    "x",
                    160,
                    "y",
                    120)),
            null,
            false);

    assertEquals(false, preview.get("target_exists"));
    assertEquals(true, preview.get("preview"));
    assertEquals(false, preview.get("applied"));
    assertEquals(
        1, preview.get("after") instanceof Map<?, ?> after ? after.get("component_count") : -1);
    assertFalse(Files.exists(project.resolve("native-preview.hpl")));
  }

  @Test
  void replacesExistingTabularFieldsWhilePreservingPipelineStructureAndSupportsRollback()
      throws Exception {
    Variables variables = new Variables();
    MemoryMetadataProvider metadataProvider = new MemoryMetadataProvider();
    InjectorMeta injectorMeta = new InjectorMeta();
    injectorMeta.setInjectorFields(
        List.of(
            new InjectorField("old_id", "Integer", "9", "0"),
            new InjectorField("old_label", "String", "50", "0")));
    PipelineMeta original = new PipelineMeta();
    original.setFilename(project.resolve("update-tabular.hpl").toString());
    original.setNameSynchronizedWithFilename(false);
    original.setName("Update tabular");
    TransformMeta input = new TransformMeta("Injector", "Input", injectorMeta);
    input.setLocation(125, 225);
    TransformMeta output = new TransformMeta("Dummy", "Output", new DummyMeta());
    original.addTransform(input);
    original.addTransform(output);
    original.addPipelineHop(new PipelineHopMeta(input, output));
    Files.writeString(project.resolve("update-tabular.hpl"), original.getXml(variables));

    HopDefinitionMutator mutator =
        new HopDefinitionMutator(new ProjectFiles(project), variables, metadataProvider);
    String oldHash = ProjectFiles.sha256(Files.readAllBytes(project.resolve("update-tabular.hpl")));
    List<Map<String, Object>> operations =
        List.of(
            Map.of(
                "operation",
                "update_component",
                "component",
                "Input",
                "property_groups",
                Map.of(
                    "fields",
                    List.of(
                        Map.of(
                            "name",
                            "new_value",
                            "type",
                            "Number",
                            "length",
                            12,
                            "precision",
                            3)))));

    Map<String, Object> preview =
        mutator.mutate("update-tabular.hpl", "pipeline", operations, oldHash, false);
    assertEquals(true, preview.get("preview"));
    assertEquals(
        oldHash, ProjectFiles.sha256(Files.readAllBytes(project.resolve("update-tabular.hpl"))));

    Map<String, Object> applied =
        mutator.mutate("update-tabular.hpl", "pipeline", operations, oldHash, true);
    PipelineMeta changed =
        new PipelineMeta(
            Files.newInputStream(project.resolve("update-tabular.hpl")),
            metadataProvider,
            variables);
    TransformMeta changedInput = changed.findTransform("Input");
    InjectorMeta changedInjector = (InjectorMeta) changedInput.getTransform();
    assertEquals(1, changedInjector.getInjectorFields().size());
    assertEquals("new_value", changedInjector.getInjectorFields().get(0).getName());
    assertEquals("Number", changedInjector.getInjectorFields().get(0).getType());
    assertEquals(125, changedInput.getLocation().x);
    assertEquals(225, changedInput.getLocation().y);
    assertEquals(1, changed.nrPipelineHops());
    assertEquals("Output", changed.getPipelineHop(0).getToTransform().getName());

    mutator.rollback(
        String.valueOf(applied.get("transaction_id")), String.valueOf(applied.get("new_sha256")));
    PipelineMeta restored =
        new PipelineMeta(
            Files.newInputStream(project.resolve("update-tabular.hpl")),
            metadataProvider,
            variables);
    assertEquals(
        2,
        ((InjectorMeta) restored.findTransform("Input").getTransform()).getInjectorFields().size());
  }

  @Test
  void updatesExistingWorkflowScalarProperties() throws Exception {
    Variables variables = new Variables();
    MemoryMetadataProvider metadataProvider = new MemoryMetadataProvider();
    HopDefinitionMutator mutator =
        new HopDefinitionMutator(new ProjectFiles(project), variables, metadataProvider);
    Map<String, Object> created =
        mutator.mutate(
            "update-scalar.hwf",
            "workflow",
            List.of(
                Map.of(
                    "operation", "add_component",
                    "plugin_id", "SPECIAL",
                    "name", "Start",
                    "properties", Map.of("repeat", false))),
            null,
            true);

    Map<String, Object> updated =
        mutator.mutate(
            "update-scalar.hwf",
            "workflow",
            List.of(
                Map.of(
                    "operation", "update_component",
                    "component", "Start",
                    "properties", Map.of("repeat", true))),
            String.valueOf(created.get("new_sha256")),
            true);

    WorkflowMeta workflow =
        new WorkflowMeta(
            Files.newInputStream(project.resolve("update-scalar.hwf")),
            metadataProvider,
            variables);
    assertTrue(workflow.findAction("Start").getAction().getXml().contains("<repeat>Y</repeat>"));
    assertEquals(true, updated.get("native_reload_valid"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            mutator.mutate(
                "update-scalar.hwf",
                "workflow",
                List.of(Map.of("operation", "update_component", "component", "Start")),
                String.valueOf(updated.get("new_sha256")),
                false));
  }

  @Test
  void rejectsUnsafeOrInvalidTabularProperties() throws Exception {
    Variables variables = new Variables();
    HopDefinitionMutator mutator =
        new HopDefinitionMutator(
            new ProjectFiles(project), variables, new MemoryMetadataProvider());
    List<Map<String, Object>> tooManyRows =
        java.util.stream.IntStream.rangeClosed(0, HopComponentAuthoring.MAX_ROWS_PER_GROUP)
            .mapToObj(i -> Map.<String, Object>of("name", "field_" + i))
            .toList();

    assertThrows(
        SecurityException.class,
        () ->
            mutator.mutate(
                "secret-fields.hpl",
                "pipeline",
                List.of(
                    Map.of(
                        "operation", "add_component",
                        "plugin_id", "Injector",
                        "name", "Input",
                        "property_groups",
                            Map.of("fields", List.of(Map.of("client_secret", "hidden"))))),
                null,
                false));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            mutator.mutate(
                "unknown-fields.hpl",
                "pipeline",
                List.of(
                    Map.of(
                        "operation", "add_component",
                        "plugin_id", "Injector",
                        "name", "Input",
                        "property_groups",
                            Map.of("fields", List.of(Map.of("not_a_field", "value"))))),
                null,
                false));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            mutator.mutate(
                "nested-fields.hpl",
                "pipeline",
                List.of(
                    Map.of(
                        "operation", "add_component",
                        "plugin_id", "Injector",
                        "name", "Input",
                        "property_groups",
                            Map.of("fields", List.of(Map.of("name", Map.of("nested", true)))))),
                null,
                false));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            mutator.mutate(
                "too-many-fields.hpl",
                "pipeline",
                List.of(
                    Map.of(
                        "operation", "add_component",
                        "plugin_id", "Injector",
                        "name", "Input",
                        "property_groups", Map.of("fields", tooManyRows))),
                null,
                false));
  }

  @Test
  void createsAndConnectsNativePipelineComponentsTransactionally() throws Exception {
    Variables variables = new Variables();
    MemoryMetadataProvider metadataProvider = new MemoryMetadataProvider();
    HopDefinitionMutator mutator =
        new HopDefinitionMutator(new ProjectFiles(project), variables, metadataProvider);

    Map<String, Object> applied =
        mutator.mutate(
            "authored.hpl",
            "pipeline",
            List.of(
                Map.of(
                    "operation", "add_component",
                    "plugin_id", "Dummy",
                    "name", "Input",
                    "x", 100,
                    "y", 200),
                Map.of(
                    "operation", "add_component",
                    "plugin_id", "Dummy",
                    "name", "Output",
                    "x", 300,
                    "y", 200),
                Map.of("operation", "add_hop", "from", "Input", "to", "Output")),
            null,
            true);

    PipelineMeta pipeline =
        new PipelineMeta(
            Files.newInputStream(project.resolve("authored.hpl")), metadataProvider, variables);
    assertEquals(2, pipeline.nrTransforms());
    assertEquals("Dummy", pipeline.findTransform("Input").getPluginId());
    assertEquals(100, pipeline.findTransform("Input").getLocation().x);
    assertEquals(1, pipeline.nrPipelineHops());
    assertEquals(true, applied.get("native_reload_valid"));
  }

  @Test
  void createsWorkflowActionWithInjectedScalarAndRejectsUnsafeProperties() throws Exception {
    Variables variables = new Variables();
    MemoryMetadataProvider metadataProvider = new MemoryMetadataProvider();
    HopDefinitionMutator mutator =
        new HopDefinitionMutator(new ProjectFiles(project), variables, metadataProvider);

    Map<String, Object> applied =
        mutator.mutate(
            "authored.hwf",
            "workflow",
            List.of(
                Map.of(
                    "operation", "add_component",
                    "plugin_id", "SPECIAL",
                    "name", "Start",
                    "properties", Map.of("repeat", true)),
                Map.of(
                    "operation", "add_component",
                    "plugin_id", "DUMMY",
                    "name", "Finish"),
                Map.of(
                    "operation", "add_hop",
                    "from", "Start",
                    "to", "Finish",
                    "unconditional", true)),
            null,
            true);

    WorkflowMeta workflow =
        new WorkflowMeta(
            Files.newInputStream(project.resolve("authored.hwf")), metadataProvider, variables);
    assertEquals(2, workflow.nrActions());
    assertEquals("SPECIAL", workflow.findAction("Start").getAction().getPluginId());
    assertTrue(workflow.findAction("Start").getAction().getXml().contains("<repeat>Y</repeat>"));
    assertEquals(1, workflow.nrWorkflowHops());
    assertEquals(true, applied.get("native_reload_valid"));

    assertThrows(
        SecurityException.class,
        () ->
            mutator.mutate(
                "unsafe.hpl",
                "pipeline",
                List.of(
                    Map.of(
                        "operation", "add_component",
                        "plugin_id", "Dummy",
                        "name", "Unsafe",
                        "properties", Map.of("client_secret", "must-not-be-accepted"))),
                null,
                false));
  }
}
