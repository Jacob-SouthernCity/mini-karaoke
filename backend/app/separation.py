import subprocess
import shutil
import logging
import re
from pathlib import Path
from .storage import get_output_dir, get_backing_path, get_vocals_path
from .db import update_song_status, update_song_progress

logger = logging.getLogger(__name__)

# Matches tqdm lines like: "Separating track foo:  45%|████  | 45/100 [...]"
# Also matches bare percentage lines demucs sometimes emits to stderr
_PERCENT_RE = re.compile(r'(\d+)%')


def run_separation(song_id: str, input_path: str):
    try:
        output_dir = get_output_dir(song_id)
        input_path = Path(input_path)

        logger.info(f"[{song_id}] Starting Demucs on {input_path}")
        update_song_progress(song_id, 0)

        demucs_out = output_dir / "demucs_raw"
        demucs_out.mkdir(parents=True, exist_ok=True)

        cmd = [
            "python", "-m", "demucs",
            "--two-stems", "vocals",
            "-o", str(demucs_out),
            str(input_path)
        ]

        process = subprocess.Popen(
            cmd,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,   # merge stderr into stdout so we read one stream
            text=True,
            bufsize=1                   # line-buffered
        )

        last_progress = 0
        for line in process.stdout:
            line = line.rstrip()
            if line:
                logger.info(f"[demucs] {line}")

            # tqdm writes \r-separated updates on one line; split on \r to get last update
            for chunk in line.split('\r'):
                m = _PERCENT_RE.search(chunk)
                if m:
                    pct = int(m.group(1))
                    # Demucs reports 0-100 for the separation; we map to 5-95 so the
                    # bar never sits at 0% (loading) or jumps straight to 100% before
                    # we've confirmed the files are in place.
                    mapped = 5 + int(pct * 0.90)
                    if mapped > last_progress:
                        last_progress = mapped
                        update_song_progress(song_id, mapped)

        process.wait()

        if process.returncode != 0:
            raise RuntimeError(f"Demucs exited with code {process.returncode}")

        # Locate output files
        song_stem = input_path.stem
        candidates = list(demucs_out.rglob("vocals.wav"))
        if not candidates:
            raise RuntimeError(f"No vocals.wav found under {demucs_out}")

        stem_dir = candidates[0].parent
        vocals_src = stem_dir / "vocals.wav"
        backing_src = stem_dir / "no_vocals.wav"

        if not vocals_src.exists() or not backing_src.exists():
            raise RuntimeError(
                f"Expected vocals.wav and no_vocals.wav in {stem_dir}, "
                f"found: {list(stem_dir.iterdir())}"
            )

        backing_dest = get_backing_path(song_id)
        vocals_dest = get_vocals_path(song_id)
        shutil.copy2(str(backing_src), str(backing_dest))
        shutil.copy2(str(vocals_src), str(vocals_dest))

        update_song_progress(song_id, 100)
        logger.info(f"[{song_id}] Done. Backing={backing_dest}")
        update_song_status(
            song_id,
            status="READY",
            backing_path=str(backing_dest),
            vocals_path=str(vocals_dest)
        )

    except Exception as e:
        logger.error(f"[{song_id}] Separation failed: {e}")
        update_song_status(song_id, status="FAILED", error_message=str(e))
