# Verification record

Local verification on Windows, 2026-10-27: Microsoft OpenJDK 21.0.13.0.2, Maven 3.10.0, Spring Boot 3.5.16 and H2.

- 42 tests: zero failures, errors or skipped tests. Real Spring context, security filters, JDBC transactions and Flyway schema; no mocked database or scheduler.
- Concurrent overlapping bookings: exactly one winner. Concurrent identical keys: one batch. Concurrent transitions: one success and one stale-version rejection.
- Later recurrence conflict rolls back earlier occurrences, batch key and audit events.
- Covers adjacency, collisions by asset/technician, UTC offsets, overdue calculation, invalid inputs, references, role/ownership checks, CSRF, real login, session reads and logout.
- Maven verify passes tests, executable JAR packaging and Spotless checks. Node syntax check passes for frontend JavaScript.
- Browser checks cover planner login, three recurring reservations and rejected conflicting reservations. Responsive layout and restart persistence are checked before release.

Tests found missing BCrypt encoder prefixes, which were fixed. Unused Mockito dependencies were excluded because inline instrumentation could not attach inside the Windows sandbox; all integration tests remain real. Stop the server before rebuilding the JAR on Windows to avoid its file lock.

CI repeats verification on Ubuntu and Windows. Consult its live status; these tests are not a throughput benchmark or proof of production readiness.

A file-backed restart integration test closes and reopens the application, then updates the persisted order and verifies its audit history. Startup rejects read-only database connections. On this Windows sandbox, an existing database still appeared read-only to H2 after explicit write permission was granted, although .NET could open the file read/write. The startup guard correctly rejected that process; its data was preserved. Final browser verification uses a separate newly created preview database. The automated file-backed close/reopen/update test passed inside its test process; cross-process restart writing in this sandbox remains an environment limitation.
