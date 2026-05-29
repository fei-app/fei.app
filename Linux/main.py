import sys
import threading
import asyncio
import gi
import os
gi.require_version('Gtk', '4.0')
from gi.repository import Gtk, Gio, GLib
from pathlib import Path
from session_manager import SessionManager
from config_manager import ConfigManager
from cache_manager import CacheManager
from ui.login_window import LoginWindow
from ui.home_window import HomeWindow
from ui.calendario_window import CalendarioWindow
from ui.horario_window import HorarioWindow
from ui.notas_window import NotasWindow
from ui.boletos_window import BoletosWindow


class OpenFEIApp(Gtk.Application):
    def __init__(self):
        # Mudamos para NON_UNIQUE para blindar o AppImage contra conflitos de DBus/Instâncias
        super().__init__(application_id='com.marinov.openfei',
                         flags=Gio.ApplicationFlags.NON_UNIQUE)
        
        # Inicialização limpa de todas as variáveis
        self.cache = CacheManager(Path.home() / '.cache' / 'openfei')
        self.config = ConfigManager(Path.home() / '.config' / 'openfei')
        self.session_mgr = SessionManager(Path.home() / '.cache' / 'openfei' / 'cookies.json')
        self.loop = asyncio.new_event_loop()
        asyncio.set_event_loop(self.loop)
        self.session = None
        self.window = None
        self.stack = None
        
        threading.Thread(target=self.loop.run_forever, daemon=True).start()

        # Conectamos via sinais para garantir a ordem de execução perfeita do GTK
        self.connect('activate', self.on_activate)
        self.connect('shutdown', self.on_shutdown)

    def run_async(self, coro, callback=None):
        future = asyncio.run_coroutine_threadsafe(coro, self.loop)
        if callback:
            def _on_done(fut):
                try:
                    result = fut.result()
                except Exception as e:
                    result = e
                GLib.idle_add(callback, result)
            future.add_done_callback(_on_done)

    async def _init_session(self):
        self.session = await self.session_mgr.get_session()
        return True

    def _on_session_ready(self, result):
        if isinstance(result, Exception):
            self.show_login_screen()
            return

        cfg = self.config.load()
        if cfg.get('auto_login') and cfg.get('user') and cfg.get('password'):
            self.run_async(
                self._try_auto_login(cfg['user'], cfg['password']),
                self._on_auto_login_result
            )
        else:
            self.show_login_screen()

    async def _try_auto_login(self, user, password):
        from login_logic import LoginLogic
        return await LoginLogic.perform_login(user, password, self.session)

    def _on_auto_login_result(self, result):
        if isinstance(result, Exception) or not result.success:
            self.show_login_screen()
        else:
            self.show_home_screen()

    def on_activate(self, app):
        # Criamos a janela vinculada ao app recebido pelo sinal com total segurança
        self.window = Gtk.ApplicationWindow(application=app)
        self.window.set_title("OpenFEI")
        self.window.set_default_size(800, 600)

        Gtk.Window.set_default_icon_name('com.marinov.openfei')

        css_provider = Gtk.CssProvider()
        css_path = os.path.join(os.path.dirname(os.path.abspath(__file__)), 'style.css')
        if os.path.exists(css_path):
            css_provider.load_from_path(css_path)
            Gtk.StyleContext.add_provider_for_display(
                self.window.get_display(),
                css_provider,
                Gtk.STYLE_PROVIDER_PRIORITY_APPLICATION
            )

        self.stack = Gtk.Stack()
        self.window.set_child(self.stack)

        self.run_async(self._init_session(), self._on_session_ready)
        self.window.present()

    def show_login_screen(self):
        self._replace_screen('login', LoginWindow(self, self.run_async, self.on_login_success))

    def show_home_screen(self):
        self._replace_screen('home', HomeWindow(self, self.on_logout, self._nav_to))

    def show_calendario_screen(self):
        self._replace_screen('calendario', CalendarioWindow(self, self.session, self.run_async, self.show_home_screen))

    def show_horario_screen(self):
        self._replace_screen('horario', HorarioWindow(self, self.session, self.run_async, self.show_home_screen))

    def show_notas_screen(self):
        self._replace_screen('notas', NotasWindow(self, self.session, self.run_async, self.show_home_screen))

    def show_boletos_screen(self):
        self._replace_screen('boletos', BoletosWindow(self, self.session, self.run_async, self.show_home_screen))

    def _nav_to(self, destino: str):
        rotas = {
            'calendario': self.show_calendario_screen,
            'horario':    self.show_horario_screen,
            'notas':      self.show_notas_screen,
            'boletos':    self.show_boletos_screen,
        }
        if destino in rotas:
            rotas[destino]()

    def _replace_screen(self, name: str, widget: Gtk.Widget):
        existing = self.stack.get_child_by_name(name)
        if existing:
            self.stack.remove(existing)
        self.stack.add_named(widget, name)
        self.stack.set_visible_child_name(name)

    def on_login_success(self, user, password, remember):
        cfg = {'auto_login': remember}
        if remember:
            cfg['user'] = user
            cfg['password'] = password
        self.config.save(cfg)
        self.show_home_screen()

    def on_logout(self):
        cfg = self.config.load()
        cfg['auto_login'] = False
        cfg.pop('user', None)
        cfg.pop('password', None)
        self.config.save(cfg)
        self.show_login_screen()

    def on_shutdown(self, app):
        if self.session is not None:
            self.loop.call_soon_threadsafe(
                lambda: asyncio.ensure_future(self.session_mgr.close(), loop=self.loop)
            )
        self.loop.call_soon_threadsafe(self.loop.stop)


if __name__ == '__main__':
    from gi.repository import GLib
    GLib.set_prgname('com.marinov.openfei')
    app = OpenFEIApp()
    app.run(sys.argv)