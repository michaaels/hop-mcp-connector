# Security policy

## Supported version

The current supported line is 1.0.x on Apache Hop 2.19.x and 2.20.x / Java 21. Apache Hop 2.19.0 remains the release compile baseline until 2.20.0 is published.

## Design assumptions

Apache Hop pipeline/workflow definitions can contain executable behavior. Treat untrusted `.hpl`, `.hwf`, scripts, SQL, metadata, parameters, and plugin configurations as untrusted code/data. Do not expose the STDIO process directly to untrusted remote users.

Inspection is enabled by default. Local execution and applied semantic mutation are disabled unless the administrator starts the server with `--allow-execution` or `--allow-mutation`, respectively.

Execution accepts only local Apache Hop run configurations and is bounded by concurrency and timeout limits. Executing a definition can still access databases, services, files, scripts, and other resources available to the Hop process; only authorize trusted clients and projects.

Mutation is limited to supported operations on native Hop semantic objects. Existing definitions require an expected SHA-256. Applied changes are backed up, atomically replaced, reloaded by Hop, automatically restored if validation fails, and can be rolled back during the same MCP session with a current-hash precondition.

Hop Desktop and Hop Web live synchronization is disabled until a user explicitly starts it from the Tools menu. The bridge stores only relative definition paths, transaction identifiers, timestamps, and SHA-256 fingerprints under `.hop-mcp/`; it does not serialize Hop metadata or operation values. Control files are bounded, atomically written, rejected when symlinked, expire automatically, and are inaccessible through MCP project tools. UI work is dispatched on the owning SWT/RAP session thread, and dirty tabs are never reloaded or closed.

The bridge assumes the MCP process and each subscribed UI session trust the same project filesystem. It does not create a network endpoint. Hop Web state and server push are isolated per browser session. Project events are intentionally broadcast to every explicitly subscribed session for that project, but each session performs its own dirty-tab check and writes a separate acknowledgement without exposing its session identifier through MCP.

Optional Hop Web access remains read-only: only GET and HEAD are accepted. Requests stay under an administrator-configured base URL, redirects are disabled, caller-provided authentication headers are rejected, and sensitive response data is redacted.

The native deep checker is disabled by default because some transforms/actions may inspect fields or contact configured external systems while checking.

## Reporting

For a suspected vulnerability, avoid posting credentials, production configuration, or exploit data in a public issue. Contact the repository owner privately through GitHub first and provide the minimum reproduction needed.
