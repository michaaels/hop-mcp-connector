import assert from "node:assert/strict";
import { readFile, access, writeFile } from "node:fs/promises";
import { createHash } from "node:crypto";
import path from "node:path";
import { connect } from "./hop-acceptance-client.mjs";
import { setTimeout as pause } from "node:timers/promises";

const [hopArg, projectArg, configArg, reportArg] = process.argv.slice(2);
if (!reportArg || process.argv.length !== 6) {
  throw new Error(
    "Usage: hop-operational-acceptance.mjs <hop-dir> <new-bootstrapped-project> <config-dir> <report.json>",
  );
}
const hopDir = path.resolve(hopArg),
  project = path.resolve(projectArg);
assert.equal(
  await readFile(path.join(project, ".hop-mcp-acceptance"), "utf8"),
  "disposable-local-fixture-v1\n",
);
await access(
  path.join(project, "metadata", "pipeline-run-configuration", "local.json"),
);
const client = connect(hopDir, project, path.resolve(configArg));
const report = {
  gate: "full_hop_operational_acceptance",
  passed: false,
  cases: [],
};
const record = (name, evidence) => {
  report.cases.push({ name, evidence });
  console.log(`Passed: ${name}`);
};
const hash = async (file) =>
  createHash("sha256")
    .update(await readFile(path.join(project, file)))
    .digest("hex");
const component = (plugin_id, name, properties = {}) => ({
  operation: "add_component",
  plugin_id,
  name,
  properties,
});
const hop = (from, to) => ({ operation: "add_hop", from, to });
async function author(file, operations) {
  try {
    await access(path.join(project, file));
    throw new Error("Fixture must not already exist");
  } catch (error) {
    if (error.code !== "ENOENT") throw error;
  }
  const args = {
    path: file,
    kind: file.endsWith(".hwf") ? "workflow" : "pipeline",
    operations,
  };
  await client.call("hop_mutate_definition", args);
  await assert.rejects(access(path.join(project, file)), { code: "ENOENT" });
  const applied = await client.call("hop_mutate_definition", {
    ...args,
    apply: true,
  });
  assert.equal(applied.applied, true);
  assert.equal(applied.native_reload_valid, true);
  assert.equal(typeof applied.atomic_replace_used, "boolean");
  assert.equal((await client.call("hop_validate", { path: file })).valid, true);
  return applied;
}
try {
  report.server = await client.initialize();
  for (const plugin_id of ["RowGenerator", "Dummy", "Abort", "Delay"]) {
    const schema = await client.call("hop_component_schema", {
      kind: "pipeline",
      plugin_id,
    });
    report.cases.push({ name: `schema-${plugin_id}`, evidence: schema });
  }
  // Native injection property names are part of the tested Hop 2.20 contract.
  await author("success.hpl", [
    component("RowGenerator", "Rows", { limit: "5" }),
    component("Dummy", "Sink"),
    hop("Rows", "Sink"),
  ]);
  record("semantic-authoring-preview-apply-validation", {
    path: "success.hpl",
  });
  const run = await client.call("hop_execute", {
    path: "success.hpl",
    run_configuration: "local",
    timeout_seconds: 15,
  });
  assert.equal(run.ok, true);
  assert.equal(run.timed_out, false);
  assert.equal(run.error_count, 0);
  record("native-pipeline-execution", run);
  record(
    "redacted-native-logs",
    await client.call("hop_logs", { channel_id: run.log_channel_id }),
  );
  const original = await hash("success.hpl");
  const mutation = {
    path: "success.hpl",
    operations: [
      { operation: "set_description", value: "acceptance transaction" },
    ],
  };
  await client.call("hop_mutate_definition", mutation);
  assert.equal(await hash("success.hpl"), original);
  await client.call(
    "hop_mutate_definition",
    { ...mutation, apply: true, expected_sha256: "0".repeat(64) },
    true,
  );
  assert.equal(await hash("success.hpl"), original);
  const changed = await client.call("hop_mutate_definition", {
    ...mutation,
    apply: true,
    expected_sha256: original,
  });
  assert.equal(changed.native_reload_valid, true);
  assert.equal(changed.backup, "protected");
  assert.equal(changed.new_sha256, await hash("success.hpl"));
  await client.call(
    "hop_rollback_mutation",
    { transaction_id: changed.transaction_id, expected_sha256: original },
    true,
  );
  await client.call("hop_rollback_mutation", {
    transaction_id: changed.transaction_id,
    expected_sha256: changed.new_sha256,
  });
  assert.equal(await hash("success.hpl"), original);
  record("hash-preconditions-protected-backup-native-rollback", {
    restored_sha256: original,
    atomic_replace_used: changed.atomic_replace_used,
  });
  await client.call("hop_read_text", { path: "../outside.txt" }, true);
  await client.call(
    "hop_read_text",
    { path: ".hop-mcp/backups/forbidden" },
    true,
  );
  record("path-and-backup-confinement", { rejected: 2 });
  await author("failure.hpl", [
    component("RowGenerator", "Rows", { limit: "5" }),
    component("Abort", "Fail", { abort_option: "ABORT_WITH_ERROR" }),
    hop("Rows", "Fail"),
  ]);
  const failed = await client.call("hop_execute", {
    path: "failure.hpl",
    timeout_seconds: 15,
  });
  assert.equal(failed.ok, false);
  assert.ok(failed.error_count > 0);
  record("native-failure-propagation", failed);
  const history = await client.call("hop_execution_history", {
    location: "acceptance-files",
    limit: 20,
  });
  assert.equal(history.executions.length, 2);
  assert.equal(history.count_complete, true);
  assert.ok(
    history.executions.some(
      (execution) => execution.path === "failure.hpl" && execution.failed,
    ),
  );
  const successful = history.executions.find(
    (execution) => execution.path === "success.hpl" && !execution.failed,
  );
  assert.ok(successful);
  record("native-history", history);
  const selector = {
    location: "acceptance-files",
    execution_id: successful.execution_id,
  };
  const metrics = await client.call("hop_execution_metrics", selector);
  assert.equal(metrics.available, true);
  assert.equal(
    metrics.components.find((row) => row.component === "Rows").metrics.Written,
    5,
  );
  assert.equal(
    metrics.components.find((row) => row.component === "Sink").metrics.Read,
    5,
  );
  record("native-metrics-exact-five-rows", metrics);
  record(
    "native-execution-diagnosis",
    await client.call("hop_diagnose_execution", {
      ...selector,
      channel_id: run.log_channel_id,
    }),
  );
  for (const plugin_id of ["SPECIAL", "SUCCESS"]) {
    record(
      `schema-${plugin_id}`,
      await client.call("hop_component_schema", {
        kind: "workflow",
        plugin_id,
      }),
    );
  }
  await author("success.hwf", [
    component("SPECIAL", "Start"),
    component("SUCCESS", "Success"),
    hop("Start", "Success"),
  ]);
  const workflow = await client.call("hop_execute", {
    path: "success.hwf",
    timeout_seconds: 15,
  });
  assert.equal(workflow.ok, true);
  assert.equal(workflow.error_count, 0);
  record("native-workflow-execution", workflow);
  await author("slow.hpl", [
    component("RowGenerator", "Rows", {
      never_ending: true,
      interval_in_ms: "100",
    }),
    component("Dummy", "Sink"),
    hop("Rows", "Sink"),
  ]);
  const asyncRun = await client.call("hop_start_execution", {
    path: "slow.hpl",
    timeout_seconds: 20,
  });
  let status;
  for (let attempt = 0; attempt < 40; attempt++) {
    status = await client.call("hop_execution_status", {
      operation_id: asyncRun.operation_id,
    });
    if (status.state === "running" && status.log_channel_id) break;
    await pause(100);
  }
  assert.equal(status.state, "running");
  assert.ok(status.log_channel_id);
  await client.call("hop_stop_execution", {
    operation_id: asyncRun.operation_id,
  });
  for (let attempt = 0; attempt < 100; attempt++) {
    status = await client.call("hop_execution_status", {
      operation_id: asyncRun.operation_id,
    });
    if (!["running", "stopping"].includes(status.state)) break;
    await pause(100);
  }
  assert.equal(status.stop_requested, true);
  assert.ok(!["running", "stopping"].includes(status.state));
  assert.equal(status.active_executions, 0);
  assert.equal(status.state, "stopped");
  record("active-asynchronous-cancellation", status);
  const timeout = await client.call("hop_execute", {
    path: "slow.hpl",
    timeout_seconds: 1,
  });
  assert.equal(timeout.timed_out, true);
  assert.equal(timeout.ok, false);
  assert.equal(timeout.error_count, -1);
  record("bounded-native-execution-timeout", timeout);
  const asyncTimeout = await client.call("hop_start_execution", {
    path: "slow.hpl",
    timeout_seconds: 1,
  });
  for (let attempt = 0; attempt < 100; attempt++) {
    status = await client.call("hop_execution_status", {
      operation_id: asyncTimeout.operation_id,
    });
    if (status.state !== "running") break;
    await pause(100);
  }
  assert.equal(status.state, "timed_out");
  assert.equal(status.result.timed_out, true);
  assert.equal(status.result.error_count, -1);
  assert.equal(status.active_executions, 0);
  record("asynchronous-timeout-output-contract", status);
  await client.close();
  record("protocol-only-stdout-clean-eof", { passed: true });
  report.passed = true;
} catch (error) {
  report.error = error.message;
  report.stderr = client.diagnostics();
  try {
    report.logs = await client.call("hop_logs");
  } catch {
    /* Server may already be closed. */
  }
  client.abort();
  throw error;
} finally {
  await writeFile(
    path.resolve(reportArg),
    JSON.stringify(report, null, 2) + "\n",
  );
}
