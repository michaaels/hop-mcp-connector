# Security policy

## Supported version

The 2.0.0 line is under development and has not been released. The Maven compile baseline is Apache Hop 2.19.0 with Java 21. The separate 2.20.0-SNAPSHOT profile is a compatibility check, not a stable-support promise.

## Design assumptions

Apache Hop pipeline and workflow definitions can contain executable behavior. Treat untrusted `.hpl`, `.hwf`, scripts, SQL, metadata, parameters, and plugin configurations as untrusted code or data. Do not expose the STDIO process directly to untrusted remote users.

Project inspection and structural validation are enabled by default. Native deep checking, local execution, semantic authoring/mutation, and Hop Web access require their separate command-line authorization flags. Tools for disabled groups are omitted from `tools/list`; service handlers retain server-side authorization checks.

Execution accepts local Apache Hop run configurations and enforces concurrency, timeout, operation-retention, and output limits. A definition can still access databases, services, files, scripts, and other resources available to the Hop process. Only authorize trusted clients and projects.

Mutation uses native Hop semantic objects. Existing definitions require an expected SHA-256. Applied changes are backed up, atomically replaced, reloaded through Hop, restored if reload validation fails, and eligible for explicit rollback during the same MCP session with a current-hash precondition.

The filesystem boundary, bounded reads/scans/results, secure XML parsing, and secret redaction apply to MCP outputs and diagnostics. Do not add a generic filesystem or arbitrary HTTP proxy surface.

Hop Desktop and Hop Web live synchronization is disabled until a user starts it from the Tools menu. The bridge stores bounded project-local control events under `.hop-mcp/`, protects dirty tabs, and opens no network listener. Subscribed UI sessions trust the same project filesystem.

Optional Hop Web access is read-only: only GET and HEAD are accepted. Requests stay under an administrator-configured base URL, redirects are disabled, caller-provided authorization headers are rejected, and sensitive response data is redacted.

The native deep checker is disabled by default because some plugins may resolve fields or contact configured external systems during checking.

## Reporting

For a suspected vulnerability, avoid posting credentials, production configuration, or exploit data in a public issue. Contact the repository owner privately through GitHub first and provide the minimum reproduction needed.
