"""Private, atomic, per-authenticated-user appearance preferences."""
import hashlib
import os
from pathlib import Path
import tempfile
import tomllib

THEMES = {'classic', 'system_regis'}

class AppearanceStore:
    def __init__(self, config_dir: Path):
        self.root = config_dir / 'state' / 'user-preferences'

    def path(self, user: str) -> Path:
        if not user.strip():
            raise ValueError('Authenticated user required')
        return self.root / (hashlib.sha256(user.strip().casefold().encode()).hexdigest() + '.toml')

    def read(self, user: str) -> str:
        path = self.path(user)
        if not path.exists():
            return 'classic'
        with path.open('rb') as f:
            theme = tomllib.load(f).get('appearance', {}).get('theme', 'classic')
        return theme if isinstance(theme, str) and theme in THEMES else 'classic'

    def write(self, user: str, theme: str) -> None:
        if theme not in THEMES:
            raise ValueError('Unknown appearance theme')
        path = self.path(user)
        self.root.mkdir(parents=True, exist_ok=True, mode=0o700)
        fd, temporary = tempfile.mkstemp(dir=self.root)
        try:
            with os.fdopen(fd, 'w') as f:
                f.write('[appearance]\ntheme = "' + theme + '"\n')
                f.flush()
                os.fsync(f.fileno())
            os.replace(temporary, path)
        finally:
            if os.path.exists(temporary):
                os.unlink(temporary)
