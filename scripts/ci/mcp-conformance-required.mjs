import { mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { join } from "node:path";
import { spawnSync } from "node:child_process";

const runnerVersion = process.env.MCP_CONFORMANCE_RUNNER ?? "0.2.0-alpha.11";
const revision = process.env.MCP_CONFORMANCE_REVISION ?? "2025-11-25";
const serverUrl = process.argv[2];
const outputDir = process.argv[3] ?? "target/mcp-conformance-2025-11-25";

if (!serverUrl) {
  console.error("Usage: node scripts/ci/mcp-conformance-required.mjs <server-url> [output-dir]");
  process.exit(2);
}

mkdirSync(outputDir, { recursive: true });
const npx = process.platform === "win32" ? "npx.cmd" : "npx";
const packageName = `@modelcontextprotocol/conformance@${runnerVersion}`;

function run(args, timeout) {
  return spawnSync(npx, ["--yes", packageName, ...args], {
    encoding: "utf8",
    timeout,
    windowsHide: true,
    shell: process.platform === "win32",
  });
}

const list = run(["list", "--requirements", revision], 60_000);
const listOutput = `${list.stdout ?? ""}${list.stderr ?? ""}`;
writeFileSync(join(outputDir, "requirements.txt"), listOutput);
if (list.status !== 0) {
  console.error("Unable to list the MCP requirement set", list.error?.message ?? "", list.stderr ?? "");
  process.exit(list.status ?? 1);
}

const serverSection = listOutput.match(/Server scenarios \(test against a server\):([\s\S]*?)\n\nClient scenarios/);
const requiredScenarios = serverSection
  ? [...serverSection[1].matchAll(/^\s+-\s+([^\s].*)$/gm)].map((match) => match[1].trim())
  : [];
if (requiredScenarios.length === 0) {
  console.error("The requirement set did not expose required server scenarios");
  process.exit(1);
}

const summary = [];
for (const scenario of requiredScenarios) {
  const result = run(["server", "--url", serverUrl, "--scenario", scenario, "--verbose"], 60_000);
  const raw = `${result.stdout ?? ""}${result.stderr ?? ""}`;
  const safeName = scenario.replaceAll(/[^A-Za-z0-9_.-]/g, "_");
  writeFileSync(join(outputDir, `${safeName}.raw.txt`), raw);
  const clean = raw.replaceAll(/\u001b\[[0-?]*[ -/]*[@-~]/g, "");
  let checks = [];
  const start = clean.indexOf("[");
  const end = clean.lastIndexOf("]");
  if (start >= 0 && end > start) {
    try {
      const parsed = JSON.parse(clean.slice(start, end + 1));
      checks = Array.isArray(parsed) ? parsed : [];
    } catch {
      checks = [];
    }
  }
  const failedChecks = checks.filter((check) => check.status !== "SUCCESS");
  summary.push({
    scenario,
    checks: checks.length,
    failed: failedChecks.length,
    runnerExitCode: result.status,
    timedOut: result.error?.code === "ETIMEDOUT",
    result: failedChecks.length === 0 && result.status === 0 ? "passed" : "failed",
  });
  console.log(
    `${scenario}: ${checks.length - failedChecks.length}/${checks.length} checks passed (exit ${result.status ?? "signal"})`,
  );
}

const failedScenarios = summary.filter((item) => item.result !== "passed");
const markdown = [
  `# MCP conformance required server scenarios (${revision})`,
  "",
  `Runner: @modelcontextprotocol/conformance ${runnerVersion}`,
  `URL: ${serverUrl}`,
  "",
  "| Scenario | Checks passed | Checks | Runner exit | Result |",
  "| --- | ---: | ---: | ---: | --- |",
  ...summary.map(
    (item) =>
      `| ${item.scenario} | ${item.checks - item.failed} | ${item.checks} | ${item.runnerExitCode ?? "signal"} | ${item.result} |`,
  ),
  "",
  `Scenarios passed: ${summary.length - failedScenarios.length}/${summary.length}`,
  `Scenarios failed: ${failedScenarios.length}`,
].join("\n");
writeFileSync(join(outputDir, "summary.json"), JSON.stringify({ revision, runnerVersion, serverUrl, summary }, null, 2));
writeFileSync(join(outputDir, "summary.md"), `${markdown}\n`);

process.exit(failedScenarios.length === 0 ? 0 : 1);
