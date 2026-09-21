package io.github.michaaels.hop.mcp;

import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.apache.commons.lang3.StringUtils;
import org.apache.hop.core.Const;
import org.apache.hop.core.config.plugin.ConfigPlugin;
import org.apache.hop.core.config.plugin.IConfigOptions;
import org.apache.hop.core.encryption.Encr;
import org.apache.hop.core.exception.HopException;
import org.apache.hop.core.logging.HopLogStore;
import org.apache.hop.core.logging.ILogChannel;
import org.apache.hop.core.logging.LogChannel;
import org.apache.hop.core.variables.IVariables;
import org.apache.hop.hop.Hop;
import org.apache.hop.hop.plugin.HopCommand;
import org.apache.hop.hop.plugin.IHopCommand;
import org.apache.hop.metadata.api.IHasHopMetadataProvider;
import org.apache.hop.metadata.api.IHopMetadataProvider;
import org.apache.hop.metadata.serializer.json.JsonMetadataProvider;
import org.apache.hop.metadata.serializer.multi.MultiMetadataProvider;
import picocli.CommandLine;

@CommandLine.Command(
    name = "mcp",
    mixinStandardHelpOptions = true,
    description = "Run the Apache Hop MCP server (read-only by default)")
@HopCommand(id = "mcp", description = "Run the Apache Hop Model Context Protocol server")
public class HopMcpCommand implements Runnable, IHopCommand, IHasHopMetadataProvider {
  @CommandLine.Option(
      names = {"--root"},
      description = "Project root. Defaults to PROJECT_HOME, then current directory.")
  private String root;

  @CommandLine.Option(
      names = {"--allow-deep-check"},
      description = "Enable Hop native deep checks. They may access configured external systems.")
  private boolean allowDeepCheck;

  @CommandLine.Option(
      names = {"--allow-execution"},
      description = "Enable bounded local pipeline/workflow execution requested through MCP.")
  private boolean allowExecution;

  @CommandLine.Option(
      names = {"--allow-mutation"},
      description = "Enable native semantic pipeline/workflow mutations requested through MCP.")
  private boolean allowMutation;

  @CommandLine.Option(
      names = {"--allow-web-api"},
      description = "Enable read-only requests to the configured Hop Web REST API.")
  private boolean allowWebApi;

  @CommandLine.Option(
      names = {"--web-url"},
      description = "Hop Web base URL. Defaults to HOP_MCP_WEB_URL.")
  private String webUrl;

  @CommandLine.Option(
      names = {"--web-timeout-seconds"},
      description = "Hop Web request timeout in seconds (1-120).",
      defaultValue = "30")
  private int webTimeoutSeconds;

  private CommandLine commandLine;
  private IVariables variables;
  private MultiMetadataProvider metadataProvider;
  private ILogChannel log;

  @Override
  public void initialize(
      CommandLine commandLine, IVariables variables, MultiMetadataProvider metadataProvider)
      throws HopException {
    this.commandLine = commandLine;
    this.variables = variables;
    this.metadataProvider = metadataProvider;
    this.log = new LogChannel("ApacheHopMCP");
    Hop.addMixinPlugins(commandLine, ConfigPlugin.CATEGORY_RUN);
  }

  @Override
  public void run() {
    PrintStream protocolOut = System.out;
    PrintStream previousOut = System.out;
    PrintStream previousHopOut = HopLogStore.OriginalSystemOut;
    ClassLoader previousContextClassLoader = Thread.currentThread().getContextClassLoader();

    HopLogStore.OriginalSystemOut = HopLogStore.OriginalSystemErr;
    System.setOut(System.err);
    Thread.currentThread().setContextClassLoader(getClass().getClassLoader());

    try {
      System.setProperty(Const.HOP_PLATFORM_RUNTIME, "MCP");
      handleMixinActions();

      Path projectRoot = determineProjectRoot();
      variables.setVariable("PROJECT_HOME", projectRoot.toString());
      ensureProjectMetadataProvider(projectRoot);

      ProjectFiles files = new ProjectFiles(projectRoot);
      HopWebClient webClient = createWebClient();
      if (allowWebApi && webClient == null) {
        throw new IllegalArgumentException("--allow-web-api requires --web-url or HOP_MCP_WEB_URL");
      }

      HopLiveUiEventBroker liveUiEventBroker = new HopLiveUiEventBroker(files.root());
      HopMcpService service =
          new HopMcpService(
              files,
              variables,
              metadataProvider,
              allowDeepCheck,
              allowExecution,
              allowMutation,
              allowWebApi,
              webClient,
              liveUiEventBroker);

      logStartup(files, webClient, liveUiEventBroker);
      try (HopMcpServer server = new HopMcpServer(service, System.in, protocolOut)) {
        server.awaitEof();
      }
    } catch (Exception e) {
      log.logError("Apache Hop MCP failed", e);
      throw new RuntimeException(e);
    } finally {
      Thread.currentThread().setContextClassLoader(previousContextClassLoader);
      System.setOut(previousOut);
      HopLogStore.OriginalSystemOut = previousHopOut;
    }
  }

  private Path determineProjectRoot() {
    String configured =
        StringUtils.isNotBlank(root)
            ? variables.resolve(root)
            : variables.getVariable("PROJECT_HOME");
    if (StringUtils.isBlank(configured)) {
      configured = System.getProperty("user.dir");
    }
    return Path.of(configured).toAbsolutePath().normalize();
  }

  private HopWebClient createWebClient() {
    String configuredWebUrl =
        StringUtils.isNotBlank(webUrl)
            ? variables.resolve(webUrl)
            : variables.getVariable("HOP_MCP_WEB_URL");
    if (StringUtils.isBlank(configuredWebUrl)) {
      return null;
    }
    return new HopWebClient(
        configuredWebUrl,
        variables.getVariable("HOP_MCP_WEB_USERNAME"),
        variables.getVariable("HOP_MCP_WEB_PASSWORD"),
        variables.getVariable("HOP_MCP_WEB_BEARER_TOKEN"),
        webTimeoutSeconds);
  }

  private void logStartup(
      ProjectFiles files, HopWebClient webClient, HopLiveUiEventBroker liveUiEventBroker) {
    log.logBasic(
        "Apache Hop MCP " + HopMcpVersion.current() + " started (stdio) root=" + files.root());
    if (allowDeepCheck) {
      log.logBasic("Native deep check enabled; checks can contact configured external systems.");
    }
    if (allowExecution) {
      log.logBasic("Bounded local pipeline/workflow execution enabled.");
    }
    if (allowMutation) {
      log.logBasic(
          "Native semantic mutation enabled with SHA-256 preconditions, backup, validation and rollback.");
    }
    if (allowWebApi) {
      log.logBasic("Read-only Hop Web REST access enabled for " + webClient.baseUrl());
    }
    if (liveUiEventBroker.isAvailable()) {
      log.logBasic("A live Hop UI session is available for semantic change events.");
    }
  }

  private void ensureProjectMetadataProvider(Path projectRoot) {
    Path metadataFolder = projectRoot.resolve("metadata");
    if (!Files.isDirectory(metadataFolder) || metadataProvider == null) {
      return;
    }
    String baseFolder = metadataFolder.toString();
    List<IHopMetadataProvider> providers = new ArrayList<>(metadataProvider.getProviders());
    boolean alreadyConfigured =
        providers.stream()
            .anyMatch(
                provider ->
                    provider instanceof JsonMetadataProvider
                        && provider.getDescription().contains(baseFolder));
    if (!alreadyConfigured) {
      providers.add(new JsonMetadataProvider(Encr.getEncoder(), baseFolder, variables));
      metadataProvider = new MultiMetadataProvider(Encr.getEncoder(), providers, variables);
    }
  }

  private void handleMixinActions() throws HopException {
    for (Map.Entry<String, Object> entry : commandLine.getMixins().entrySet()) {
      if (entry.getValue() instanceof IConfigOptions options) {
        options.handleOption(log, this, variables);
      }
    }
  }

  @Override
  public MultiMetadataProvider getMetadataProvider() {
    return metadataProvider;
  }

  @Override
  public void setMetadataProvider(MultiMetadataProvider metadataProvider) {
    this.metadataProvider = metadataProvider;
  }
}
