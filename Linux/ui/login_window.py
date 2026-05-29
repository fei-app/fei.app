import gi
gi.require_version('Gtk', '4.0')
from gi.repository import Gtk, GLib
from login_logic import LoginLogic, LoginResult


class LoginWindow(Gtk.Box):
    def __init__(self, app, run_async_callback, on_login_success):
        super().__init__(orientation=Gtk.Orientation.VERTICAL, spacing=12)
        self.app = app
        self.run_async = run_async_callback
        self.on_login_success = on_login_success

        self.set_margin_top(50)
        self.set_margin_bottom(50)
        self.set_margin_start(50)
        self.set_margin_end(50)
        self.set_halign(Gtk.Align.CENTER)
        self.set_valign(Gtk.Align.CENTER)

        title = Gtk.Label(label="Login OpenFEI")
        title.add_css_class('title-1')
        self.append(title)

        self.user_entry = Gtk.Entry()
        self.user_entry.set_placeholder_text("Usuário")
        self.append(self.user_entry)

        self.pass_entry = Gtk.Entry()
        self.pass_entry.set_visibility(False)
        self.pass_entry.set_input_purpose(Gtk.InputPurpose.PASSWORD)
        self.pass_entry.set_placeholder_text("Senha")
        self.append(self.pass_entry)

        self.remember_check = Gtk.CheckButton(label="Lembrar credenciais")
        self.append(self.remember_check)

        self.login_button = Gtk.Button(label="Entrar")
        self.login_button.connect('clicked', self.on_login_clicked)
        self.append(self.login_button)

        self.spinner = Gtk.Spinner()
        self.spinner.set_visible(False)
        self.append(self.spinner)

        self.error_label = Gtk.Label()
        self.error_label.add_css_class('error')
        self.error_label.set_visible(False)
        self.append(self.error_label)

    def on_login_clicked(self, button):
        if not hasattr(self.app, 'session') or self.app.session is None:
            self.show_error("Sessão ainda não inicializada. Aguarde um instante.")
            return

        user = self.user_entry.get_text().strip()
        password = self.pass_entry.get_text().strip()

        if not user or not password:
            self.show_error("Preencha usuário e senha.")
            return

        self.set_loading(True)
        self.run_async(self._perform_login(user, password), self._on_login_done)

    async def _perform_login(self, user, password):
        return await LoginLogic.perform_login(user, password, self.app.session)

    def _on_login_done(self, result: LoginResult):
        self.set_loading(False)
        if result.success:
            user = self.user_entry.get_text().strip()
            password = self.pass_entry.get_text().strip()
            self.on_login_success(user, password, self.remember_check.get_active())
        else:
            self.show_error(result.error_message)

    def set_loading(self, loading: bool):
        self.login_button.set_sensitive(not loading)
        self.spinner.set_visible(loading)
        if loading:
            self.spinner.start()
        else:
            self.spinner.stop()

    def show_error(self, message: str):
        self.error_label.set_label(message)
        self.error_label.set_visible(bool(message))