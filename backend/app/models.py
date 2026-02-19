from pydantic import BaseModel
from typing import Optional


class SongBase(BaseModel):
    title: str
    artist: str


class SongCreate(SongBase):
    pass


class SongResponse(BaseModel):
    id: str
    title: str
    artist: str
    status: str
    filename_original: str
    created_at: str
    error_message: Optional[str] = None
    backing_path: Optional[str] = None
    vocals_path: Optional[str] = None

    class Config:
        from_attributes = True


class SongListItem(BaseModel):
    id: str
    title: str
    artist: str
    status: str

    class Config:
        from_attributes = True
