# Self-host Overrides

This image runs after `init-appdata` and applies fork-owned, idempotent database
overrides. It is intentionally separate from `init-db` because upstream
`init-appdata` can reload product metadata after Liquibase has finished.

Add only self-host fork owned metadata here. Do not update user business data.
