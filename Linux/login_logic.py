import aiohttp
from bs4 import BeautifulSoup
from dataclasses import dataclass


@dataclass
class LoginResult:
    success: bool
    error_message: str = ""


class LoginLogic:
    LOGIN_URL = "https://interage.fei.org.br/secureserver/portal"
    USER_AGENT = (
        "Mozilla/5.0 (Linux; Android 16; sdk_gphone64_x86_64 Build/BE2A.250530.026.D1; wv) "
        "AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/133.0.6943.137 Mobile Safari/537.36"
    )

    @staticmethod
    async def perform_login(user: str, password: str, session: aiohttp.ClientSession) -> LoginResult:
        headers = {'User-Agent': LoginLogic.USER_AGENT}
        try:
            async with session.get(LoginLogic.LOGIN_URL, headers=headers) as resp:
                if resp.status != 200:
                    return LoginResult(False, f"Erro ao acessar página de login (HTTP {resp.status})")
                html = await resp.text()

            soup = BeautifulSoup(html, 'html.parser')
            token_input = soup.select_one('input[name="__RequestVerificationToken"]')
            if not token_input or not token_input.get('value'):
                return LoginResult(False, "Token de segurança não encontrado na página.")
            token = token_input['value']

            data = {
                '__RequestVerificationToken': token,
                'Usuario': user,
                'Senha': password
            }
            async with session.post(LoginLogic.LOGIN_URL + '/', data=data, headers=headers) as resp:
                if resp.status != 200:
                    return LoginResult(False, f"Erro no envio do login (HTTP {resp.status})")
                html = await resp.text()

            soup = BeautifulSoup(html, 'html.parser')
            if soup.select_one('#btn-login') is None:
                return LoginResult(success=True)
            else:
                error_elem = soup.select_one('.field-validation-error')
                error_msg = error_elem.text.strip() if error_elem else "Usuário ou senha incorretos."
                return LoginResult(success=False, error_message=error_msg)

        except aiohttp.ClientError as e:
            return LoginResult(False, f"Erro de conexão: {str(e)}")
        except Exception as e:
            return LoginResult(False, f"Erro inesperado: {str(e)}")