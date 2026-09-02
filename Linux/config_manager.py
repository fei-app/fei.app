import json
from pathlib import Path

class ConfigManager:
    def __init__(self, config_dir: Path):
        self.config_dir = config_dir
        self.config_file = config_dir / 'config.json'

    def load(self):
        if self.config_file.exists():
            with open(self.config_file) as f:
                return json.load(f)
        return {}

    def save(self, data):
        self.config_dir.mkdir(parents=True, exist_ok=True)
        with open(self.config_file, 'w') as f:
            json.dump(data, f, indent=2)