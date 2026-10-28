# API contract

All application routes except GET /api/csrf require a session. POST requests also require CSRF. Domain errors return a message field; security errors may return a generic 401/403 response.

## Session

1. GET /api/csrf, retaining its session cookie. The response contains token and header.
2. POST /api/login with URL-encoded username/password and the CSRF header/token. Success: 204.
3. Fetch /api/csrf again after login because authentication rotates the token.
4. Send JSON mutations with the refreshed CSRF header and session cookie. GET /api/me returns the identity/roles.
5. POST /api/logout with CSRF to invalidate the session.

## Booking

POST /api/orders (planner only), with Idempotency-Key containing a new lowercase UUID:

```json
{"assetId":1,"technician":"technician","title":"Inspect network switch","startsAt":"2030-01-15T02:00:00Z","durationMinutes":60,"occurrences":3,"intervalDays":7}
```

Replace the example time with a future instant. Duration: 15–480 minutes; occurrences: 1–12; interval: 1–365 days. Every occurrence must start within the next 366 days. Response: array of orders. Matching retries return the same IDs; changed content with the same key returns 409.

## Transitions

POST /api/orders/{id}/start, /complete or /cancel:

```json
{"expectedVersion":0,"note":"Checked power indicators and recorded baseline."}
```

Use the version from the latest read. Stale version/invalid transition: 409; wrong role/owner: 403; missing order: 404.

| Route | Behavior |
| --- | --- |
| GET /api/orders?page=0&status=SCHEDULED | Optional status filter, 100 rows/page ordered by start and ID |
| GET /api/orders/{id}/history | Newest 100 audit events |
| GET /api/assets | Inventory |
| GET /api/technicians | Configured technicians |
| POST /api/assets | Planner adds code, name and location; duplicate code returns 409 |

There are no external work-execution or notification endpoints.
