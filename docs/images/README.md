# README illustration

`mcp-demo-en.gif` illustrates local MCP, Desktop Live Sync and Web Live Sync
in the default English README. `mcp-demo.gif` is the Spanish version used by
`README.es.md`. These are conceptual diagrams, not recordings of Apache Hop.
They contain no project data or real server addresses. Each has a `.png`
static alternative, used when the viewer prefers reduced motion.

Regenerate with Python 3 and Pillow:

```sh
python scripts/docs/render-readme-demo.py
```

Use `--language en` or `--language es` to regenerate just one language.

The renderer uses the installed Segoe UI or DejaVu Sans font; fonts are not
distributed. The animation loops over three scenes in 11.7 seconds without
flashing. Source and generated illustrations use the repository's Apache 2.0
license.
