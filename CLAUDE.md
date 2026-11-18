# F-150 CarPlay

Follow [AGENTS.md](AGENTS.md) for this dedicated USB-only fork. The current entry points are
`CarLinkApplication`, `HomeActivity`, `UsbRouterActivity` and `WiredWakeReceiver`; `SessionManager`
owns one wired CarPlay session. Android Auto and the generic wireless/application scaffolding
have been removed. Original DiPlay agent files and legacy architecture notes are reference
material; they do not override this fork's scope, package identity or device authorization.

Use the portable `.claude/skills/carlink-dev/scripts/dev.sh` helper for builds/tests. The older
`carlink-dev` skill text describes the upstream dual-engine architecture; current commands and
requirements are in AGENTS.md and README.md.
