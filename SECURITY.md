# Security policy

## Supported version

The stable Maven compile baseline is Apache Hop 2.19.0 with Java 21. The separate `hop-2.20` profile targets the 2.20.0-SNAPSHOT line as a compatibility check, not a stable-support promise. Consult the repository's release tags and Marketplace catalog for the connector versions currently distributed.

## Design assumptions

Apache Hop pipeline and workflow definitions can contain executable behavior. Treat untrusted `.hpl`, `.hwf`, scripts, SQL, metadata, parameters, and plugin configurations as untrusted code or data. Do not expose the STDIO process directly to untrusted remote users.

Project inspection and structural validation are enabled by default. Native deep checking, local execution, semantic authoring/mutation, and Hop Web access require their separate command-line authorization flags. Tools for disabled groups are omitted from `tools/list`; service handlers retain server-side authorization checks.

In `hop_config`, `read_only` is true only when every opt-in group is disabled. It becomes false when deep checks, execution, or Hop Web access is enabled as well as when writes are enabled; use `definition_write_enabled` and the individual `allow_*` fields to determine the actual authorization state.

Execution accepts local Apache Hop run configurations and enforces concurrency, timeout, operation-retention, and output limits. A definition can still access databases, services, files, scripts, and other resources available to the Hop process. Only authorize trusted clients and projects.

Mutation uses native Hop semantic objects and never accepts caller-provided XML replacement. Applying a change to an existing definition requires its expected SHA-256. An existing definition is backed up under `.hop-mcp/backups/<transaction-id>/definition.backup`; responses expose only `backup: "protected"`, not that path. The session retains at most 100 rollback transactions for one hour and the backup store is capped at 32 MiB. Expired backups are pruned during a later mutation or expiry check. Writes use a temporary file and report `atomic_replace_used` so clients can distinguish an atomic filesystem move from the fallback used on filesystems that do not support it. Hop reload validation follows a write; recovery is attempted on failure. Explicit rollback is limited to the same MCP session and requires the current definition hash.

Project reads are limited to 4 MiB per file. Scans are bounded to 50,000 visited entries, 5,000 files, depth 64, and 32 MiB for content scanning or catalog hashing. Pages return at most 200 results and a complete tool response is limited to 512 KiB. The text reader redacts before chunking; its offsets count UTF-8 bytes in the redacted view. Search also redacts before matching. The filesystem boundary, secure XML parsing, and secret redaction apply to MCP outputs and diagnostics. Do not add a generic filesystem or arbitrary HTTP proxy surface.

Hop Desktop and Hop Web live synchronization is disabled until a user starts it from the Tools menu. The bridge stores bounded project-local control events under `.hop-mcp/`, protects dirty tabs, and opens no network listener. Subscribed UI sessions trust the same project filesystem.

Optional Hop Web access is read-only: only GET and HEAD are accepted. Requests stay under an administrator-configured base URL, redirects are disabled, caller-provided authorization headers are rejected, response reads are limited to 4 MiB, returned bodies to 64 KiB, and sensitive response data is redacted before truncation.

The native deep checker is disabled by default because some plugins may resolve fields or contact configured external systems during checking.

The MCP SDK and server declare protocol revision `2025-11-25` over STDIO. This server advertises tools only; it does not implement prompts, resources, completion, sampling, elicitation, or SSE. The official Conformance Suite is not wired into CI, and no conformance pass is claimed. The 2.20.0-SNAPSHOT compatibility profile is separate from the 2.19.0 stable compile baseline.

## Reporting

For a suspected vulnerability, avoid posting credentials, production configuration, or exploit data in a public issue. Contact the repository owner privately through GitHub first and provide the minimum reproduction needed.
