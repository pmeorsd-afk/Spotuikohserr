# Instructions for Antigravity

Add this context package to the root of the SpotUI / SpotUI Kosher repository.

Files:

- `AGENTS.md`
- `docs/PROJECT_CONTEXT.md`
- `docs/ARCHITECTURE.md`
- `docs/PODCAST_ARCHITECTURE.md`
- `docs/MEDIASESSION_WAZE.md`
- `docs/UI_UX_CONSTRAINTS.md`
- `docs/TELEMETRY.md`
- `docs/STATUS.md`
- `docs/CURRENT_WORK.md`
- `docs/TESTING_CHECKLIST.md`

## Important

1. Do not modify app source code as part of installing these context files.
2. If the repository already contains an `AGENTS.md`, do not overwrite it blindly:
   - merge the rules
   - preserve existing repo-specific instructions
   - report conflicts
3. Verify file/class names against the repository and make only documentation corrections if names changed.
4. Do not invent implementation status. If repository state contradicts `STATUS.md`, update the status document to match reality.
5. Keep this documentation change separate from functional code changes if possible.

After installation, Codex should read `AGENTS.md` first and use the linked documents as the persistent project context.
