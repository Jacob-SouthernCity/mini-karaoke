"""
Karaoke Backend - FastAPI application.
Handles song upload, metadata, source separation (Demucs), and streaming.
"""

import uuid
import threading
from contextlib import asynccontextmanager
from datetime import datetime, timezone
from pathlib import Path
from typing import Optional

from fastapi import FastAPI, File, Form, UploadFile, BackgroundTasks, HTTPException, Request
from fastapi.responses import FileResponse, JSONResponse, HTMLResponse
from fastapi.staticfiles import StaticFiles
from fastapi.templating import Jinja2Templates

from .db import init_db, insert_song, get_song, list_songs, update_song_status, update_song_progress
from .storage import ensure_dirs, save_upload, get_upload_path, get_backing_path, get_vocals_path
from .separation import run_separation


@asynccontextmanager
async def lifespan(app: FastAPI):
    ensure_dirs()
    init_db()
    yield


app = FastAPI(title="Karaoke API", lifespan=lifespan)

# Setup templates
TEMPLATES_DIR = Path(__file__).parent / "templates"
templates = Jinja2Templates(directory=str(TEMPLATES_DIR))


# ─────────────────────────────────────────────────────────
# Web Portal Routes
# ─────────────────────────────────────────────────────────

@app.get("/", response_class=HTMLResponse)
async def index(request: Request, query: str = ""):
    songs = list_songs(query)
    return templates.TemplateResponse("index.html", {
        "request": request,
        "songs": songs,
        "query": query
    })


@app.get("/songs/{song_id}", response_class=HTMLResponse)
async def song_detail_page(request: Request, song_id: str):
    song = get_song(song_id)
    if not song:
        raise HTTPException(status_code=404, detail="Song not found")
    return templates.TemplateResponse("song_detail.html", {
        "request": request,
        "song": song
    })


# ─────────────────────────────────────────────────────────
# API Routes
# ─────────────────────────────────────────────────────────

@app.get("/health")
def health():
    return {"status": "ok"}


@app.post("/api/songs/upload")
async def upload_song(
    file: UploadFile = File(...),
    title: str = Form(...),
    artist: str = Form(...)
):
    """Upload a song file and its metadata. Returns song id and initial status."""
    song_id = str(uuid.uuid4())
    filename_original = file.filename or "audio.mp3"

    # Save the file
    saved_path = save_upload(song_id, filename_original, file.file)

    # Insert into DB
    now = datetime.now(timezone.utc).isoformat()
    insert_song({
        "id": song_id,
        "title": title,
        "artist": artist,
        "filename_original": filename_original,
        "status": "UPLOADED",
        "created_at": now,
    })

    return {"id": song_id, "status": "UPLOADED"}


@app.post("/api/songs/{song_id}/separate")
async def trigger_separation(song_id: str, background_tasks: BackgroundTasks):
    """Trigger Demucs source separation in background. Returns immediately."""
    song = get_song(song_id)
    if not song:
        raise HTTPException(status_code=404, detail="Song not found")

    if song["status"] == "READY":
        return {"id": song_id, "status": "READY", "message": "Already processed"}

    if song["status"] == "PROCESSING":
        return {"id": song_id, "status": "PROCESSING", "message": "Already processing"}

    # Mark as processing
    update_song_status(song_id, status="PROCESSING")

    # Get the upload path
    upload_path = get_upload_path(song_id, song["filename_original"])

    # Run separation in background thread
    background_tasks.add_task(run_separation, song_id, str(upload_path))

    return {"id": song_id, "status": "PROCESSING"}


@app.get("/api/songs")
def get_songs(query: str = ""):
    """List all songs, optionally filtered by title/artist."""
    songs = list_songs(query)
    return [
        {
            "id": s["id"],
            "title": s["title"],
            "artist": s["artist"],
            "status": s["status"],
        }
        for s in songs
    ]


@app.get("/api/songs/{song_id}")
def get_song_detail(song_id: str):
    """Get full song detail including status."""
    song = get_song(song_id)
    if not song:
        raise HTTPException(status_code=404, detail="Song not found")
    return song


@app.get("/api/songs/{song_id}/progress")
def get_progress(song_id: str):
    """Lightweight polling endpoint — returns status and progress (0-100)."""
    song = get_song(song_id)
    if not song:
        raise HTTPException(status_code=404, detail="Song not found")
    return {"id": song_id, "status": song["status"], "progress": song.get("progress", 0)}


@app.get("/api/songs/{song_id}/backing")
def stream_backing(song_id: str):
    """Stream the backing (instrumental) track if separation is complete."""
    song = get_song(song_id)
    if not song:
        raise HTTPException(status_code=404, detail="Song not found")
    if song["status"] != "READY":
        raise HTTPException(
            status_code=409,
            detail=f"Song is not ready (status={song['status']})"
        )
    backing_path = get_backing_path(song_id)
    if not backing_path.exists():
        raise HTTPException(status_code=404, detail="Backing file missing")
    return FileResponse(
        str(backing_path),
        media_type="audio/wav",
        filename="backing.wav"
    )


@app.get("/api/songs/{song_id}/vocals")
def stream_vocals(song_id: str):
    """Stream the vocals track (debug endpoint)."""
    song = get_song(song_id)
    if not song:
        raise HTTPException(status_code=404, detail="Song not found")
    if song["status"] != "READY":
        raise HTTPException(status_code=409, detail=f"Song not ready (status={song['status']})")
    vocals_path = get_vocals_path(song_id)
    if not vocals_path.exists():
        raise HTTPException(status_code=404, detail="Vocals file missing")
    return FileResponse(str(vocals_path), media_type="audio/wav", filename="vocals.wav")
