import { spawn, execFileSync, spawnSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { createInterface } from 'node:readline';
import { access, copyFile, mkdir, readFile, readdir } from 'node:fs/promises';
import path from 'node:path';

const [runtimeArg, fixtureArg, configArg, version, trialArg, profileArg] = process.argv.slice(2);
if (!runtimeArg || !fixtureArg || !configArg || !version || !trialArg) throw new Error('Expected runtime, fixture, config, version, trial, and optional JFR directory');
const runtime = path.resolve(runtimeArg);
const fixture = path.resolve(fixtureArg);
const config = path.resolve(configArg);
const profileDir = profileArg ? path.resolve(profileArg) : null;
const trial = Number(trialArg);
const hopVersion = '2.20.0';
const javaCheck = spawnSync(path.join(process.env.JAVA_HOME, 'bin', process.platform === 'win32' ? 'java.exe' : 'java'), ['-version'], { encoding: 'utf8', windowsHide: true });
if (javaCheck.status !== 0 || !/version "21[."]/i.test(javaCheck.stdout + javaCheck.stderr)) throw new Error('Benchmark requires Java 21');
const warmCount = 5;
const pluginDir = path.join(runtime, 'plugins', 'misc', 'hop-mcp-connector');
await access(path.join(runtime, 'lib', 'core', `hop-core-${hopVersion}.jar`));
await access(path.join(runtime, 'lib', 'core', `hop-engine-${hopVersion}.jar`));
await access(path.join(fixture, 'target.hpl'));
const definitionNames = (await readdir(fixture)).filter((name) => /\.(hpl|hwf)$/i.test(name));
if (definitionNames.length !== 5000) throw new Error(`Expected 5000 definitions; got ${definitionNames.length}`);
const versionXml = await readFile(path.join(pluginDir, 'version.xml'), 'utf8');
if (versionXml.match(/<version>\s*([^<]+)\s*<\/version>/)?.[1]?.trim() !== version) throw new Error('Plugin version.xml mismatch');
await mkdir(config, { recursive: true });
try { await access(path.join(config, 'hop-config.json')); }
catch { await copyFile(path.join(runtime, 'config', 'hop-config.json'), path.join(config, 'hop-config.json')); }
if (profileDir) await mkdir(profileDir, { recursive: true });

const hopCommand = path.join(runtime, 'hop.bat');
const child = spawn(process.env.ComSpec || 'cmd.exe', ['/d', '/s', '/c', '""' + hopCommand + '" mcp --root "' + fixture + '""'], {
  cwd: runtime,
  env: { ...process.env, HOP_CONFIG_FOLDER: config },
  stdio: ['pipe', 'pipe', 'pipe'],
  windowsHide: true,
  windowsVerbatimArguments: true,
});
const queue = [];
const waiters = [];
let protocolError = null;
let stderr = '';
let closed = false;
createInterface({ input: child.stdout }).on('line', (line) => {
  try {
    const message = JSON.parse(line);
    const waiter = waiters.shift();
    if (waiter) waiter(message);
    else queue.push(message);
  } catch {
    protocolError = new Error(`Non-JSON-RPC stdout: ${line.slice(0, 250)}`);
    child.kill();
  }
});
child.stdout.on('close', () => { closed = true; while (waiters.length) waiters.shift()(null); });
child.stderr.setEncoding('utf8');
child.stderr.on('data', (chunk) => { stderr = (stderr + chunk).slice(-500_000); });
const exit = new Promise((resolve) => child.once('close', resolve));
function nextMessage() {
  if (protocolError) return Promise.reject(protocolError);
  if (queue.length) return Promise.resolve(queue.shift());
  if (closed) return Promise.reject(new Error('Hop closed MCP stdout'));
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => { child.kill(); reject(new Error('MCP response timeout')); }, 120_000);
    waiters.push((message) => { clearTimeout(timer); message ? resolve(message) : reject(new Error('Hop closed MCP stdout')); });
  });
}
let requestId = 0;
async function request(method, params = {}) {
  const id = ++requestId;
  const next = nextMessage();
  child.stdin.write(JSON.stringify({ jsonrpc: '2.0', id, method, params }) + '\n');
  const response = await next;
  if (response.id !== id) throw new Error(`MCP response ID mismatch for ${method}`);
  if (response.error) throw new Error(`${method} error: ${JSON.stringify(response.error)}`);
  return response.result;
}
async function tool(name, args = {}) {
  const result = await request('tools/call', { name, arguments: args });
  if (result.isError) throw new Error(`${name}: ${JSON.stringify(result)}`);
  return result.structuredContent || JSON.parse(result.content.find((part) => part.type === 'text').text);
}
function canonical(value) {
  if (Array.isArray(value)) return value.map(canonical);
  if (value && typeof value === 'object') return Object.fromEntries(Object.keys(value).sort().map((key) => [key, canonical(value[key])]));
  return value;
}
function hash(value) { return createHash('sha256').update(JSON.stringify(canonical(value))).digest('hex'); }
function assertImpact(result, label) {
  if (result.node_count !== 64 || result.returned_nodes !== 64 || result.edge_count !== 63 || result.returned_edges !== 63 ||
      new Set((result.nodes || []).map((node) => node.path)).size !== 64 || result.results_truncated !== false ||
      result.count_complete !== true || result.has_more !== false) {
    throw new Error(`${label} impact result failed count, uniqueness, or completeness checks`);
  }
}
function javaCommand(args) {
  return execFileSync(path.join(process.env.JAVA_HOME, 'bin', process.platform === 'win32' ? 'jcmd.exe' : 'jcmd'), args, { encoding: 'utf8', timeout: 30_000 });
}
function hopPid() {
  const listing = javaCommand(['-l']);
  const line = listing.split(/\r?\n/).find((value) => /\borg\.apache\.hop\.hop\.Hop\b/.test(value));
  if (!line) throw new Error(`Hop JVM missing from jcmd -l: ${listing}`);
  return line.trim().split(/\s+/, 1)[0];
}
function startProfile(pid, name, file) {
  if (!profileDir) return;
  const output = javaCommand([pid, 'JFR.start', `name=${name}`, 'settings=profile', `filename=${file}`]);
  if (!/Started recording/i.test(output)) throw new Error(`JFR start failed: ${output}`);
}
function stopProfile(pid, name) {
  if (!profileDir) return;
  const output = javaCommand([pid, 'JFR.stop', `name=${name}`]);
  if (!/stopped|dumped|written/i.test(output)) throw new Error(`JFR stop failed: ${output}`);
}

const started = performance.now();
try {
  const init = await request('initialize', { protocolVersion: '2025-11-25', capabilities: {}, clientInfo: { name: 'full-hop-benchmark', version: '1' } });
  const startupMs = performance.now() - started;
  if (init.protocolVersion !== '2025-11-25' || init.serverInfo?.version !== version) throw new Error(`Unexpected initialize: ${JSON.stringify(init)}`);
  child.stdin.write(JSON.stringify({ jsonrpc: '2.0', method: 'notifications/initialized', params: {} }) + '\n');
  const plugins = [];
  for (const id of ['TableInput', 'FilterRows']) {
    const result = await tool('hop_plugins', { type: 'TransformPluginType', query: id, limit: 50 });
    const matches = (result.plugins || []).filter((item) => (item.ids || []).includes(id));
    if (matches.length === 0) throw new Error(`Required plugin ${id} is missing`);
    plugins.push({ id, matches });
  }
  const pid = profileDir ? hopPid() : null;
  const selector = { table: 'DWH.DIM_SITE', max_depth: 64, max_edges: 500, max_results: 200 };
  startProfile(pid, 'impact-cold', path.join(profileDir || '', `impact-cold-${version}-${trial}.jfr`));
  const coldStarted = performance.now();
  const cold = await tool('hop_impact_analysis', selector);
  const coldMs = performance.now() - coldStarted;
  stopProfile(pid, 'impact-cold');
  assertImpact(cold, 'Cold');
  const coldHash = hash(cold);
  const supportedMetrics = version !== '2.2.1';
  const coldMetrics = supportedMetrics ? await tool('hop_runtime_metrics') : null;
  startProfile(pid, 'impact-warm', path.join(profileDir || '', `impact-warm-${version}-${trial}.jfr`));
  const warmMs = [];
  const warmHashes = [];
  for (let index = 0; index < warmCount; index++) {
    const tick = performance.now();
    const result = await tool('hop_impact_analysis', selector);
    warmMs.push(performance.now() - tick);
    assertImpact(result, `Warm ${index + 1}`);
    const currentHash = hash(result);
    if (currentHash !== coldHash) throw new Error(`Warm result ${index + 1} differs from cold result`);
    warmHashes.push(currentHash);
  }
  stopProfile(pid, 'impact-warm');
  const warmMetrics = supportedMetrics ? await tool('hop_runtime_metrics') : null;
  const validation = await tool('hop_validate', { path: 'target.hpl' });
  if (validation.valid !== true || validation.type !== 'pipeline' || validation.errors?.length !== 0 || validation.diagnostics_truncated !== false) {
    throw new Error(`hop_validate failed: ${JSON.stringify(validation)}`);
  }
  console.log(JSON.stringify({ version, hop_version: hopVersion, trial, startup_ms: startupMs, cold_ms: coldMs, warm_ms: warmMs,
    node_count: cold.node_count, results_truncated: cold.results_truncated, count_complete: cold.count_complete,
    impact_sha256: coldHash, impact_results: { cold, warm_hashes: warmHashes }, cold_metrics: coldMetrics,
    warm_metrics: warmMetrics, plugin_checks: plugins, validation }));
} catch (error) {
  child.kill();
  await Promise.race([exit, new Promise((resolve) => setTimeout(resolve, 5000))]);
  throw new Error(`${error.message}; stderr: ${stderr}`);
} finally {
  child.stdin.end();
  const stopped = await Promise.race([exit.then(() => true), new Promise((resolve) => setTimeout(() => resolve(false), 2000))]);
  if (!stopped) child.kill();
  await Promise.race([exit, new Promise((resolve) => setTimeout(resolve, 5000))]);
}
