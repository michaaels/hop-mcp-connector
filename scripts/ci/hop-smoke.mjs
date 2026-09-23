import { spawn } from "node:child_process";
import { createInterface } from "node:readline";
import { access, copyFile, mkdir, writeFile } from "node:fs/promises";
import path from "node:path";

function optionsFromArgs(argv) {
  const result = {};
  for (let index = 0; index < argv.length; index += 2) {
    const key = argv[index];
    if (!key?.startsWith("--") || !argv[index + 1]) {
      throw new Error("Expected --hop-dir, --project, and --config-dir values");
    }
    result[key.slice(2)] = argv[index + 1];
  }
  for (const key of ["hop-dir", "project", "config-dir"]) {
    if (!result[key]) throw new Error("Missing --" + key);
  }
  return result;
}

const options = optionsFromArgs(process.argv.slice(2));
const hopDir = path.resolve(options["hop-dir"]);
const projectDir = path.resolve(options.project);
const configDir = path.resolve(options["config-dir"]);
await Promise.all([
  mkdir(projectDir, { recursive: true }),
  mkdir(configDir, { recursive: true }),
]);
const configFile = path.join(configDir, "hop-config.json");
if (process.env.HOP_SMOKE_EMPTY_CONFIG !== "true") {
  try {
    await access(configFile);
  } catch (error) {
    if (error.code !== "ENOENT") throw error;
    await copyFile(path.join(hopDir, "config", "hop-config.json"), configFile);
  }
}
await writeFile(
  path.join(projectDir, "valid.hpl"),
  '<?xml version="1.0"?>\n<pipeline><info><name>stdio-contract</name></info><order/></pipeline>\n',
  "utf8",
);

const hopCommand = path.join(hopDir, process.platform === "win32" ? "hop.bat" : "hop");
const command =
  process.platform === "win32" ? process.env.ComSpec || "cmd.exe" : hopCommand;
const commandArgs =
  process.platform === "win32"
    ? ["/d", "/s", "/c", '""' + hopCommand + '" mcp --root "' + projectDir + '""']
    : ["mcp", "--root", projectDir];
const child = spawn(command, commandArgs, {
  cwd: hopDir,
  env: { ...process.env, HOP_CONFIG_FOLDER: configDir },
  stdio: ["pipe", "pipe", "pipe"],
  windowsHide: true,
  windowsVerbatimArguments: process.platform === "win32",
});

const lineReader = createInterface({ input: child.stdout });
const queuedLines = [];
const waitingReaders = [];
let stderr = "";
let stdoutClosed = false;
lineReader.on("line", (line) => {
  const reader = waitingReaders.shift();
  if (reader) reader.resolve(line);
  else queuedLines.push(line);
});
lineReader.on("close", () => {
  stdoutClosed = true;
  while (waitingReaders.length) {
    waitingReaders.shift().reject(new Error("Hop MCP closed stdout early"));
  }
});
child.stderr.setEncoding("utf8");
child.stderr.on("data", (chunk) => {
  stderr += chunk;
  if (stderr.length > 1_000_000) child.kill();
});
const exitResult = new Promise((resolve) => {
  child.once("close", (code, signal) => resolve({ code, signal }));
});

function nextLine(timeoutMs = 30_000) {
  if (queuedLines.length) return Promise.resolve(queuedLines.shift());
  if (stdoutClosed) return Promise.reject(new Error("Hop MCP stdout closed early"));
  return new Promise((resolve, reject) => {
    const reader = {
      resolve: (line) => {
        clearTimeout(timer);
        resolve(line);
      },
      reject: (error) => {
        clearTimeout(timer);
        reject(error);
      },
    };
    const timer = setTimeout(() => {
      const index = waitingReaders.indexOf(reader);
      if (index >= 0) waitingReaders.splice(index, 1);
      child.kill();
      reject(new Error("Timed out waiting for an MCP response"));
    }, timeoutMs);
    waitingReaders.push(reader);
  });
}

let nextId = 1;
async function request(method, params) {
  const id = nextId++;
  const responseLine = nextLine();
  child.stdin.write(
    JSON.stringify({ jsonrpc: "2.0", id, method, params }) + "\n",
    "utf8",
  );
  const rawLine = await responseLine;
  let response;
  try {
    response = JSON.parse(rawLine);
  } catch {
    throw new Error(
      "Non-JSON-RPC content appeared on stdout: " + rawLine.slice(0, 500),
    );
  }
  if (response.id !== id) {
    throw new Error("MCP response ID mismatch: expected " + id);
  }
  if (response.error) {
    throw new Error("MCP request failed: " + JSON.stringify(response.error));
  }
  return response.result;
}

async function listAllTools() {
  const tools = [];
  const seenCursors = new Set();
  let cursor;
  for (let page = 0; page < 32; page++) {
    const result = await request("tools/list", cursor ? { cursor } : {});
    if (!Array.isArray(result.tools)) {
      throw new Error("tools/list returned an invalid tools page");
    }
    tools.push(...result.tools);
    cursor = result.nextCursor;
    if (!cursor) return tools;
    if (typeof cursor !== "string" || seenCursors.has(cursor)) {
      throw new Error("tools/list returned an invalid or repeated cursor");
    }
    seenCursors.add(cursor);
  }
  throw new Error("tools/list exceeded the smoke-test pagination limit");
}

try {
  const initialized = await request("initialize", {
    protocolVersion: "2025-11-25",
    capabilities: {},
    clientInfo: { name: "hop-clean-install-smoke", version: "1" },
  });
  if (initialized.protocolVersion !== "2025-11-25") {
    throw new Error("Unexpected MCP protocol revision");
  }
  if (initialized.serverInfo?.name !== "hop-mcp-connector") {
    throw new Error("Unexpected MCP server name");
  }

  child.stdin.write(
    JSON.stringify({
      jsonrpc: "2.0",
      method: "notifications/initialized",
      params: {},
    }) + "\n",
    "utf8",
  );
  const tools = await listAllTools();
  const toolNames = new Set(tools.map((tool) => tool.name));
  for (const name of ["hop_config", "hop_validate"]) {
    if (!toolNames.has(name)) throw new Error("Missing core tool: " + name);
  }
  for (const name of [
    "hop_deep_check",
    "hop_component_types",
    "hop_execute",
    "hop_mutate_definition",
    "hop_web_request",
  ]) {
    if (toolNames.has(name)) throw new Error("Disabled tool was exposed: " + name);
  }

  const config = await request("tools/call", {
    name: "hop_config",
    arguments: {},
  });
  if (config.isError || !config.structuredContent) {
    throw new Error("hop_config did not return structured success content");
  }
  const validation = await request("tools/call", {
    name: "hop_validate",
    arguments: { path: "valid.hpl" },
  });
  if (validation.isError || validation.structuredContent?.valid !== true) {
    throw new Error("hop_validate did not validate the clean fixture");
  }

  child.stdin.end();
  let shutdownTimer;
  const exit = await Promise.race([
    exitResult,
    new Promise((_, reject) => {
      shutdownTimer = setTimeout(() => {
        child.kill();
        reject(new Error("Hop MCP did not shut down after STDIO EOF"));
      }, 120_000);
    }),
  ]);
  clearTimeout(shutdownTimer);
  if (exit.code !== 0) {
    throw new Error(
      "Hop exited with " + exit.code + " (" + exit.signal + "): " + stderr,
    );
  }
  if (queuedLines.length !== 0) {
    throw new Error("Unexpected non-protocol content appeared on stdout");
  }
  console.log(
    "Clean Hop smoke passed: initialize 2025-11-25, tools/list (" +
      tools.length +
      " tools), hop_config, hop_validate, and clean STDIO EOF.",
  );
  console.log(
    "Enabled default tools: " +
      tools.map((tool) => tool.name).sort().join(", ") +
      ".",
  );
  console.log("Hop startup and application logs were captured from stderr.");
} catch (error) {
  child.kill();
  const exit = await Promise.race([
    exitResult,
    new Promise((resolve) => setTimeout(() => resolve(null), 2_000)),
  ]);
  const exitText = exit ? " (exit " + exit.code + ", signal " + exit.signal + ")" : "";
  throw new Error(error.message + exitText + (stderr ? ": " + stderr : ""));
}
