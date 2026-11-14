package io.github.michaaels.hop.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.apache.hop.core.HopEnvironment;
import org.apache.hop.core.exception.HopException;
import org.apache.hop.core.extension.ExtensionPoint;
import org.apache.hop.core.extension.ExtensionPointPluginType;
import org.apache.hop.core.extension.IExtensionPoint;
import org.apache.hop.core.logging.ILogChannel;
import org.apache.hop.core.plugins.IPlugin;
import org.apache.hop.core.plugins.PluginRegistry;
import org.apache.hop.core.variables.IVariables;
import org.apache.hop.core.variables.Variables;
import org.apache.hop.metadata.serializer.memory.MemoryMetadataProvider;
import org.apache.hop.pipeline.Pipeline;
import org.apache.hop.pipeline.PipelineMeta;
import org.apache.hop.pipeline.config.PipelineRunConfiguration;
import org.apache.hop.pipeline.engines.local.LocalPipelineRunConfiguration;
import org.apache.hop.pipeline.transform.TransformMeta;
import org.apache.hop.pipeline.transforms.dummy.DummyMeta;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/** Verifies the native completion contract consumed by HopNative, without replacing its engine. */
@Timeout(20)
class HopPipelineCompletionTest {
  private static final String PLUGIN_ID = "McpCompletionCompatibilityFixture";
  private static final String PIPELINE_NAME = "mcp-completion-fixture";
  @TempDir Path project;
  private IPlugin plugin;

  @BeforeEach
  void registerObserver() throws Exception {
    HopEnvironment.init();
    PluginRegistry registry = PluginRegistry.getInstance();
    registry.registerPluginClass(
        CompletionObserver.class.getName(), ExtensionPointPluginType.class, ExtensionPoint.class);
    plugin = registry.getPlugin(ExtensionPointPluginType.class, PLUGIN_ID);
    PipelineMeta pipeline = new PipelineMeta();
    pipeline.setNameSynchronizedWithFilename(false);
    pipeline.setName(PIPELINE_NAME);
    pipeline.addTransform(new TransformMeta("Dummy", "Input", new DummyMeta()));
    Files.writeString(project.resolve("completion.hpl"), pipeline.getXml(new Variables()));
  }

  @AfterEach
  void removeObserver() {
    CompletionObserver.callback = null;
    if (plugin != null)
      PluginRegistry.getInstance().removePlugin(ExtensionPointPluginType.class, plugin);
  }

  @Test
  void waitsForPipelineCompletedBeforeReturningTheMcpResult() throws Exception {
    CountDownLatch entered = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    CompletionObserver.callback =
        (log, variables, object) -> {
          entered.countDown();
          try {
            if (!release.await(10, TimeUnit.SECONDS))
              throw new HopException("Fixture release timed out");
          } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new HopException("Fixture interrupted", failure);
          }
        };
    var executor = Executors.newSingleThreadExecutor();
    var pending =
        executor.submit(
            () -> {
              try (HopMcpService execution = service()) {
                return execution.execute("completion.hpl", "local", Map.of(), 15);
              }
            });
    try {
      assertTrue(entered.await(5, TimeUnit.SECONDS));
      assertThrows(TimeoutException.class, () -> pending.get(200, TimeUnit.MILLISECONDS));
      release.countDown();
      assertEquals(true, pending.get(5, TimeUnit.SECONDS).get("ok"));
    } finally {
      release.countDown();
      pending.cancel(true);
      executor.shutdownNow();
      assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
    }
  }

  @Test
  void includesCompletionCallbackFailuresInTheMcpResult() throws Exception {
    CompletionObserver.callback =
        (log, variables, object) -> {
          throw new HopException("Fixture completion failure");
        };
    try (HopMcpService execution = service()) {
      Map<String, Object> result = execution.execute("completion.hpl", "local", Map.of(), 10);
      assertEquals(false, result.get("ok"));
      assertTrue(((Number) result.get("error_count")).longValue() >= 1);
    }
  }

  private HopMcpService service() throws Exception {
    MemoryMetadataProvider provider = new MemoryMetadataProvider();
    PipelineRunConfiguration local = new PipelineRunConfiguration();
    local.setName("local");
    LocalPipelineRunConfiguration engine = new LocalPipelineRunConfiguration();
    engine.setEnginePluginId("Local");
    local.setEngineRunConfiguration(engine);
    provider.getSerializer(PipelineRunConfiguration.class).save(local);
    return new HopMcpService(
        new ProjectFiles(project.toRealPath()),
        new Variables(),
        provider,
        false,
        true,
        false,
        false,
        null);
  }

  @ExtensionPoint(
      id = PLUGIN_ID,
      extensionPointId = "PipelineCompleted",
      description = "MCP compatibility test completion observer")
  public static final class CompletionObserver implements IExtensionPoint {
    static volatile IExtensionPoint callback;

    @Override
    public void callExtensionPoint(ILogChannel log, IVariables variables, Object object)
        throws HopException {
      IExtensionPoint current = callback;
      if (current != null
          && object instanceof Pipeline pipeline
          && PIPELINE_NAME.equals(pipeline.getPipelineMeta().getName())) {
        current.callExtensionPoint(log, variables, object);
      }
    }
  }
}
