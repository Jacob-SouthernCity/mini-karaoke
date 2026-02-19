from pathlib import Path
import shutil

STORAGE_ROOT = Path(__file__).parent.parent / "storage"
UPLOADS_DIR = STORAGE_ROOT / "uploads"
OUTPUTS_DIR = STORAGE_ROOT / "outputs"


def ensure_dirs():
    UPLOADS_DIR.mkdir(parents=True, exist_ok=True)
    OUTPUTS_DIR.mkdir(parents=True, exist_ok=True)


def save_upload(song_id: str, filename: str, file_obj) -> str:
    """Save uploaded file, return path string."""
    suffix = Path(filename).suffix or ".mp3"
    dest = UPLOADS_DIR / f"{song_id}{suffix}"
    with open(dest, "wb") as f:
        shutil.copyfileobj(file_obj, f)
    return str(dest)


def get_upload_path(song_id: str, filename_original: str) -> Path:
    suffix = Path(filename_original).suffix or ".mp3"
    return UPLOADS_DIR / f"{song_id}{suffix}"


def get_output_dir(song_id: str) -> Path:
    d = OUTPUTS_DIR / song_id
    d.mkdir(parents=True, exist_ok=True)
    return d


def get_backing_path(song_id: str) -> Path:
    return OUTPUTS_DIR / song_id / "backing.wav"


def get_vocals_path(song_id: str) -> Path:
    return OUTPUTS_DIR / song_id / "vocals.wav"
