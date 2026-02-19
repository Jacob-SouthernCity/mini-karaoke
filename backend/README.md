# Karaoke Backend

FastAPI + SQLite backend for the Karaoke application.

## Setup

```bash
cd backend
python -m venv venv
source venv/bin/activate   # Windows: venv\Scripts\activate
pip install -r requirements.txt
```

## Run

```bash
uvicorn app.main:app --reload --host 0.0.0.0 --port 8000
```

- Web portal: http://localhost:8000
- API docs:   http://localhost:8000/docs

## API Reference

| Method | Path | Description |
|--------|------|-------------|
| GET | `/` | Web portal index |
| GET | `/songs/{id}` | Song detail page |
| GET | `/health` | Health check |
| POST | `/api/songs/upload` | Upload song (form: file, title, artist) |
| POST | `/api/songs/{id}/separate` | Trigger Demucs separation |
| GET | `/api/songs?query=` | List/search songs |
| GET | `/api/songs/{id}` | Song detail JSON |
| GET | `/api/songs/{id}/backing` | Stream backing.wav |
| GET | `/api/songs/{id}/vocals` | Stream vocals.wav (debug) |

## Demucs Note

Demucs will be downloaded on first run (~1GB model). Separation takes 1-5 minutes per song depending on hardware.

## Storage Layout

```
backend/
  storage/
    uploads/{id}.mp3       # Original uploaded files
    outputs/{id}/
      backing.wav          # Instrumental track (no vocals)
      vocals.wav           # Isolated vocals
  karaoke.db               # SQLite database
```
