import json
from pathlib import Path
from typing import TypeVar, Type, List, Optional
from datetime import datetime

T = TypeVar('T')

class CacheManager:
    def __init__(self, cache_dir: Path):
        self.cache_dir = cache_dir
        self.cache_dir.mkdir(parents=True, exist_ok=True)

    def _get_file_path(self, key: str) -> Path:
        return self.cache_dir / f"{key}.json"

    def save(self, key: str, data, timestamp: bool = True):
        file_path = self._get_file_path(key)
        payload = {
            'data': data,
            'timestamp': datetime.now().isoformat()
        }
        with open(file_path, 'w') as f:
            json.dump(payload, f, ensure_ascii=False, indent=2)

    def load(self, key: str, model_class: Type[T] = None) -> Optional[List[T]]:
        file_path = self._get_file_path(key)
        if not file_path.exists():
            return None
        try:
            with open(file_path, 'r') as f:
                payload = json.load(f)
            data = payload['data']
            if model_class:
                # Se for uma lista de dataclasses, converter manualmente
                if isinstance(data, list):
                    return [model_class(**item) for item in data]
                else:
                    return [model_class(**data)]  # fallback
            return data
        except (json.JSONDecodeError, KeyError, TypeError):
            return None

    def clear_all(self):
        for file in self.cache_dir.glob('*.json'):
            file.unlink()