# Repository agent instructions

## Session logs and project documentation

- Store routine session logs and deployment closeout summaries outside this
  repository, in the current deployment directory (`$PA_DEPLOYMENT_DIR` when set).
  Use a file such as `session-log.md` and reference its absolute path in the
  deployment registry.
- For ticket-only or administrative work, the ticket and deployment registry are
  the durable records. Do not add session logs under `docs/` or `docs/sessions/`,
  or change the README solely to record that work.
- Keep repository documentation focused on durable project knowledge: behavior,
  architecture, runbooks, implementation decisions, and relevant validation limits.
  Add an in-repository session record only when explicitly requested by the user.
- Preserve existing historical session documents unless separately asked to move
  or remove them. When relocating a log, update its registry reference as well,
  including any staged foreground completion through the supported PA CLI.
- If no deployment directory is available, use the available ticket/registry
  records, or ask where a requested standalone log should be stored. Do not invent
  a project-docs destination.
