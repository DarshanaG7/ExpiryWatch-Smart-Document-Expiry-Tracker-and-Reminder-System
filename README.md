# Smart Document Expiry Alert System

Track licences, passports, insurance policies and pollution certificates, and get advance reminders before they expire.

**Stack:** Java 17+ (JDK only, no Maven or libraries) · HTML/CSS/JavaScript · REST API

## Run (VS Code or terminal)

```
cd doc-expiry-alert
javac DocExpiryServer.java
java DocExpiryServer
```

Open http://localhost:8080. Data is saved to `data.txt` next to the server, and sample documents are created on first run.

## Folder layout

```
doc-expiry-alert/
├── DocExpiryServer.java   REST API + static file server
└── public/
    ├── index.html
    ├── style.css
    └── app.js
```

## REST API

| Method | Endpoint | Purpose |
|---|---|---|
| GET | `/api/documents` | List documents, soonest expiry first |
| POST | `/api/documents` | Add `{name, type, expiryDate, reminderDays}` |
| PUT | `/api/documents/{id}` | Update a document |
| DELETE | `/api/documents/{id}` | Delete a document |
| GET | `/api/dashboard` | Counts: expired, within 7 / 15 / 30 days, safe |
| GET / PUT | `/api/settings` | Read or set `defaultReminderDays` |
| GET | `/api/report` | Download the expiry report as CSV |

## Status categories

Expired (past date) · Critical (0–7 days) · Warning (8–15) · Upcoming (16–30) · Safe (30+).
A document appears under "Upcoming expiry alerts" once it is inside its own reminder period.
