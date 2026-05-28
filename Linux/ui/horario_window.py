import gi
gi.require_version('Gtk', '4.0')
from gi.repository import Gtk


class HorarioWindow(Gtk.Box):
    """Tela de Horário de Aula — em construção."""

    def __init__(self, app, session, run_async_callback, on_back):
        super().__init__(orientation=Gtk.Orientation.VERTICAL, spacing=0)
        self.app = app
        self.session = session
        self.run_async = run_async_callback
        self.on_back = on_back

        # Barra superior
        top_bar = Gtk.Box(orientation=Gtk.Orientation.HORIZONTAL)
        top_bar.set_margin_start(6)
        top_bar.set_margin_end(6)
        top_bar.set_margin_top(6)
        top_bar.set_margin_bottom(6)

        back_btn = Gtk.Button(label="← Voltar")
        back_btn.connect('clicked', lambda _: on_back())
        top_bar.append(back_btn)

        spacer = Gtk.Label()
        spacer.set_hexpand(True)
        top_bar.append(spacer)

        self.append(top_bar)

        # Título
        title = Gtk.Label(label="Horário de Aula")
        title.add_css_class('title-2')
        self.append(title)

        # Placeholder
        placeholder = Gtk.Label(label="Em construção…")
        placeholder.set_halign(Gtk.Align.CENTER)
        placeholder.set_valign(Gtk.Align.CENTER)
        placeholder.set_vexpand(True)
        self.append(placeholder)