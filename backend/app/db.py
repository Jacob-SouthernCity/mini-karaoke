import sqlite3
from pathlib import Path

DB_PATH = Path(__file__).parent.parent / "karaoke.db"


def get_conn():
    conn = sqlite3.connect(str(DB_PATH))
    conn.row_factory = sqlite3.Row
    return conn


def init_db():
    with get_conn() as conn:
        conn.execute("""
            CREATE TABLE IF NOT EXISTS songs (
                id TEXT PRIMARY KEY,
                title TEXT NOT NULL,
                artist TEXT NOT NULL,
                filename_original TEXT NOT NULL,
                status TEXT NOT NULL DEFAULT 'UPLOADED',
                created_at TEXT NOT NULL,
                error_message TEXT,
                backing_path TEXT,
                vocals_path TEXT,
                progress INTEGER NOT NULL DEFAULT 0
            )
        """)
        # Add progress column to existing DBs that were created before this column existed
        try:
            conn.execute("ALTER TABLE songs ADD COLUMN progress INTEGER NOT NULL DEFAULT 0")
        except Exception:
            pass
        conn.commit()


def insert_song(song: dict):
    with get_conn() as conn:
        conn.execute("""
            INSERT INTO songs (id, title, artist, filename_original, status, created_at)
            VALUES (:id, :title, :artist, :filename_original, :status, :created_at)
        """, song)
        conn.commit()


def get_song(song_id: str):
    with get_conn() as conn:
        row = conn.execute("SELECT * FROM songs WHERE id=?", (song_id,)).fetchone()
        return dict(row) if row else None


def list_songs(query: str = ""):
    with get_conn() as conn:
        if query:
            rows = conn.execute(
                "SELECT * FROM songs WHERE title LIKE ? OR artist LIKE ? ORDER BY created_at DESC",
                (f"%{query}%", f"%{query}%")
            ).fetchall()
        else:
            rows = conn.execute("SELECT * FROM songs ORDER BY created_at DESC").fetchall()
        return [dict(r) for r in rows]


def update_song_status(song_id: str, status: str, error_message: str = None,
                        backing_path: str = None, vocals_path: str = None):
    with get_conn() as conn:
        conn.execute("""
            UPDATE songs SET status=?, error_message=?, backing_path=?, vocals_path=?
            WHERE id=?
        """, (status, error_message, backing_path, vocals_path, song_id))
        conn.commit()


def update_song_progress(song_id: str, progress: int):
    with get_conn() as conn:
        conn.execute("UPDATE songs SET progress=? WHERE id=?", (progress, song_id))
        conn.commit()
