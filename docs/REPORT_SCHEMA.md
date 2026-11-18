# Report storage reservation (V5)

V5 belongs to Team 2 and stores comparison consent, revision/month-scoped report jobs, generation attempts
and persisted strategy candidates. Every root record references the anonymous user; candidates reference their
report. Consent requires valid age/income groups. Report uniqueness is user + scope + revision + basis month,
and the stored month must be its first day. Worker state transitions, request limits and owner-scoped queries
belong to later services; table constraints alone do not implement those APIs.

This migration must remain Draft until Team 1 confirms the V2/V4 and Team 2 V3 application order. Tests can
apply V1/V5 to an isolated PostgreSQL Testcontainer to check DDL; that is not permission to apply V5 ahead of
the shared sequence in a development/production database. No placeholder V2/V4 or global out-of-order option
is introduced. `loan_id` remains an internal reference until the Team 1 schema is integrated; services must
validate ownership before creating a loan-scoped job. Do not edit this migration after it has been shared/applied.

No report worker, AI request, external connection or cloud resource is started by this schema change.
