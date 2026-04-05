# MCAP - Minecraft Account Pooler

A lightweight and simple self-hosted Minecraft account pool manager. Sign in with Microsoft accounts, store tokens, and keep them fresh automatically, all accessible from a web dashboard.

<img width="1220" height="692" alt="screenshot" src="https://github.com/user-attachments/assets/78de3228-4e07-4645-b40a-09d3947df51e" />


> [!NOTE]
> This is designed for single-user, local use. There is no authentication or input sanitization. Do not expose this to the public internet.

## Features

- Microsoft device code login flow
- Import accounts via refresh tokens (single or bulk)
- Automatically refresh expired tokens every 30 minutes
- Copy session and refresh tokens per account
- Export accounts as `sessiontoken:refreshtoken` per line
- 3D skin viewer with live preview
- Skin uploading (classic/slim)
- Cape management (equip, hide, preview on hover)
- Username changing with availability check
- REST API for programmatic access
- Dark-themed web dashboard

## Tech Stack

- **Backend:** Java 25, [Javalin](https://javalin.io/), [MinecraftAuth](https://github.com/RaphiMC/MinecraftAuth)
- **Frontend:** Vanilla HTML/CSS/JS, [skinview3d](https://github.com/bs-community/skinview3d)
- **Database:** SQLite
- **Build:** Gradle

## Installation

### Docker (recommended)

1. Create a `docker-compose.yml`:
   ```yaml
   services:
     mcap:
       image: ghcr.io/aurickk/mcap:latest
       ports:
         - "7070:7070"
       volumes:
         - mcap-data:/app/data
   volumes:
     mcap-data:
   ```

2. Start the container:
   ```
   docker compose up -d
   ```

3. Open http://localhost:7070

To stop: `docker compose down`

### JAR

Requires [Java 25](https://adoptium.net/) or later.

1. Download `mcap-<version>.jar` from the [latest release](https://github.com/Aurickk/mcap/releases/latest)

2. Run:
   ```
   java -jar mcap-<version>.jar
   ```

3. Open http://localhost:7070

### Build from source

1. Clone the repository:
   ```
   git clone https://github.com/Aurickk/mcap.git
   cd mcap
   ```

2. Build and run:
   ```
   ./gradlew run
   ```

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
| `GET (SSE)` | `/api/accounts/login` | Start device code login |
| `POST` | `/api/accounts/login/token` | Import via refresh token |
| `POST` | `/api/accounts/{id}/refresh` | Refresh account tokens |
| `DELETE` | `/api/accounts/{id}` | Remove account |
| `GET` | `/api/accounts/{id}/profile` | Fetch skin/cape data |
| `POST` | `/api/accounts/{id}/skin` | Upload skin |
| `PUT` | `/api/accounts/{id}/cape` | Equip cape |
| `DELETE` | `/api/accounts/{id}/cape` | Hide cape |
| `GET` | `/api/accounts/name/{name}/available` | Check username availability |
| `PUT` | `/api/accounts/{id}/name` | Change username |
