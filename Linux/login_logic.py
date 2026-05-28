import aiohttp
from bs4 import BeautifulSoup
from dataclasses import dataclass
import sys

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
        print("[DEBUG] Iniciando login...", file=sys.stderr)
        try:
            # 1. GET – obter token e cookies iniciais
            print(f"[DEBUG] GET {LoginLogic.LOGIN_URL}", file=sys.stderr)
            async with session.get(LoginLogic.LOGIN_URL, headers=headers) as resp:
                print(f"[DEBUG] GET status: {resp.status}", file=sys.stderr)
                print(f"[DEBUG] URL final após redirecionamentos: {resp.url}", file=sys.stderr)
                if resp.status != 200:
                    return LoginResult(False, f"Erro ao acessar página de login (HTTP {resp.status})")
                html = await resp.text()
                print(f"[DEBUG] Tamanho do HTML recebido: {len(html)} caracteres", file=sys.stderr)
                # Salvar HTML para inspeção (opcional, descomente se quiser)
                # with open('/tmp/login_page.html', 'w') as f:
                #     f.write(html)

            soup = BeautifulSoup(html, 'html.parser')
            token_input = soup.select_one('input[name="__RequestVerificationToken"]')
            if not token_input or not token_input.get('value'):
                print("[DEBUG] Token de segurança NÃO encontrado!", file=sys.stderr)
                # Mostrar os primeiros 1000 caracteres do HTML para ajudar a depurar
                print("[DEBUG] Início do HTML:", html[:1000], file=sys.stderr)
                return LoginResult(False, "Token de segurança não encontrado na página.")
            token = token_input['value']
            print(f"[DEBUG] Token obtido: {token[:20]}...", file=sys.stderr)

            # 2. POST – efetuar login
            data = {
                '__RequestVerificationToken': token,
                'Usuario': user,
                'Senha': password
            }
            print(f"[DEBUG] POST {LoginLogic.LOGIN_URL}/ com dados (exceto senha): {{'__RequestVerificationToken': '{token[:20]}...', 'Usuario': '{user}'}}", file=sys.stderr)
            async with session.post(LoginLogic.LOGIN_URL + '/', data=data, headers=headers) as resp:
                print(f"[DEBUG] POST status: {resp.status}", file=sys.stderr)
                print(f"[DEBUG] URL final após POST: {resp.url}", file=sys.stderr)
                if resp.status != 200:
                    return LoginResult(False, f"Erro no envio do login (HTTP {resp.status})")
                html = await resp.text()
                print(f"[DEBUG] Tamanho do HTML pós-login: {len(html)} caracteres", file=sys.stderr)
                # Salvar HTML pós-login para inspeção
                # with open('/tmp/login_result.html', 'w') as f:
                #     f.write(html)

            soup = BeautifulSoup(html, 'html.parser')
            login_btn = soup.select_one('#btn-login')
            print(f"[DEBUG] Elemento #btn-login encontrado? {login_btn is not None}", file=sys.stderr)

            if login_btn is None:
                print("[DEBUG] Login bem-sucedido (botão ausente).", file=sys.stderr)
                return LoginResult(success=True)
            else:
                error_elem = soup.select_one('.field-validation-error')
                error_msg = error_elem.text.strip() if error_elem else "Usuário ou senha incorretos."
                print(f"[DEBUG] Login falhou. Mensagem de erro: '{error_msg}'", file=sys.stderr)
                return LoginResult(success=False, error_message=error_msg)

        except aiohttp.ClientError as e:
            print(f"[DEBUG] Erro de conexão: {e}", file=sys.stderr)
            return LoginResult(False, f"Erro de conexão: {str(e)}")
        except Exception as e:
            print(f"[DEBUG] Erro inesperado: {e}", file=sys.stderr)
            return LoginResult(False, f"Erro inesperado: {str(e)}")