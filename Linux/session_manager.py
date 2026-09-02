import aiohttp
import json
from pathlib import Path

class SessionManager:
    def __init__(self, cookie_file: Path):
        self.cookie_file = cookie_file
        self.session: aiohttp.ClientSession = None

    async def get_session(self) -> aiohttp.ClientSession:
        if self.session is None or self.session.closed:
            timeout = aiohttp.ClientTimeout(total=30)  # 30 segundos
            jar = aiohttp.CookieJar()
            if self.cookie_file.exists():
                with open(self.cookie_file, 'r') as f:
                    cookies_data = json.load(f)
                for cookie in cookies_data:
                    jar.update_cookies({cookie['name']: cookie['value']},
                                    aiohttp.URL(cookie.get('url', 'https://interage.fei.org.br')))
            self.session = aiohttp.ClientSession(cookie_jar=jar, timeout=timeout)
        return self.session

    async def save_cookies(self):
        if self.session and not self.session.closed:
            # extrair cookies e salvar
            cookies = []
            for cookie in self.session.cookie_jar:
                for morsel in cookie.values():
                    cookies.append({
                        'name': morsel.key,
                        'value': morsel.value,
                        'url': f"{morsel['domain']}{morsel['path']}"
                    })
            with open(self.cookie_file, 'w') as f:
                json.dump(cookies, f)

    async def close(self):
        if self.session and not self.session.closed:
            await self.save_cookies()
            await self.session.close()