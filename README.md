# MCAP - Minecraft Account Pooler

A lightweight and simple self-hosted Minecraft account pool manager. Sign in with Microsoft accounts, store tokens, and keep them fresh automatically, all accessible from a web dashboard.

<img width="1220" height="692" alt="screenshot" src="https://github.com/user-attachments/assets/78de3228-4e07-4645-b40a-09d3947df51e" />


> [!NOTE]
> This is designed for single-user, local use. There is no authentication or input sanitization. Do not expose this to the public internet.

## Features

- Microsoft device code login flow
- Automatically detect expired tokens every 30 minutes and refresh them
- Copy session and refresh tokens per account
- Export accounts as `sessiontoken:refreshtoken` per line
- REST API for programmatic access
- Dark-themed web dashboard

## Tech Stack

- **Backend:** Java 21, [Javalin](https://javalin.io/), [MinecraftAuth](https://github.com/RaphiMC/MinecraftAuth)
- **Frontend:** Vanilla HTML/CSS/JS
- **Database:** SQLite
- **Build:** Gradle

## Installation

### Prerequisites

- [Docker](https://docs.docker.com/get-docker/) and [Docker Compose](https://docs.docker.com/compose/install/)

### Steps

1. Clone the repository:
   ```
   git clone https://github.com/your-username/MCAP.git
   cd MCAP
   ```

2. Build and start the container:
   ```
   docker compose up -d --build
   ```

3. Open http://localhost:7070

To stop: `docker compose down`

To rebuild after changes: `docker compose up -d --build`

## Configuration

Set in `docker-compose.yml`:

- **Port**: Change the left side of `ports` (e.g. `"8080:7070"` to expose on port 8080)
- **Environment variables**:

| Variable | Default | Description |
|----------|---------|-------------|
| `MCAP_DB` | `/app/data/mcap.db` | SQLite database path |
| `MCAP_REFRESH_MINUTES` | `30` | Token auto-refresh interval |

## API

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/api/accounts` | List all accounts |
| `POST` | `/api/accounts/{id}/refresh` | Refresh account tokens |
| `DELETE` | `/api/accounts/{id}` | Remove account |
| `GET (SSE)` | `/api/accounts/login` | Start device code login |
