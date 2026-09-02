import gi
gi.require_version('Gtk', '4.0')
from gi.repository import Gtk


class HomeWindow(Gtk.Box):
    """Tela principal do app com os 4 módulos disponíveis."""

    BOTOES = [
        ('calendario', '📅', 'Calendário\nde Provas'),
        ('horario',    '🕐', 'Horário\nde Aula'),
        ('notas',      '📝', 'Notas'),
        ('boletos',    '💳', 'Boletos'),
    ]

    def __init__(self, app, on_logout, on_navigate):
        super().__init__(orientation=Gtk.Orientation.VERTICAL, spacing=0)
        self.app = app
        self.on_logout = on_logout
        self.on_navigate = on_navigate

        # ---- Barra superior ----
        top_bar = Gtk.Box(orientation=Gtk.Orientation.HORIZONTAL)
        top_bar.set_margin_start(12)
        top_bar.set_margin_end(12)
        top_bar.set_margin_top(8)
        top_bar.set_margin_bottom(8)

        title_lbl = Gtk.Label(label="OpenFEI")
        title_lbl.add_css_class('title-2')
        title_lbl.set_hexpand(True)
        title_lbl.set_xalign(0)
        top_bar.append(title_lbl)

        logout_btn = Gtk.Button(label="Sair")
        logout_btn.connect('clicked', lambda _: on_logout())
        top_bar.append(logout_btn)

        self.append(top_bar)

        # Separador visual
        sep = Gtk.Separator(orientation=Gtk.Orientation.HORIZONTAL)
        self.append(sep)

        # ---- Grade de botões centrada ----
        outer = Gtk.Box(orientation=Gtk.Orientation.VERTICAL)
        outer.set_vexpand(True)
        outer.set_valign(Gtk.Align.CENTER)
        outer.set_halign(Gtk.Align.CENTER)
        self.append(outer)

        grid = Gtk.Grid()
        grid.set_row_spacing(16)
        grid.set_column_spacing(16)
        grid.set_margin_top(24)
        grid.set_margin_bottom(24)
        grid.set_margin_start(24)
        grid.set_margin_end(24)
        outer.append(grid)

        for i, (destino, icone, rotulo) in enumerate(self.BOTOES):
            btn = self._criar_botao(icone, rotulo, destino)
            grid.attach(btn, i % 2, i // 2, 1, 1)

    def _criar_botao(self, icone: str, rotulo: str, destino: str) -> Gtk.Button:
        btn = Gtk.Button()
        btn.set_size_request(160, 120)

        conteudo = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=8)
        conteudo.set_halign(Gtk.Align.CENTER)
        conteudo.set_valign(Gtk.Align.CENTER)

        icone_lbl = Gtk.Label(label=icone)
        icone_lbl.add_css_class('home-icon')

        texto_lbl = Gtk.Label(label=rotulo)
        texto_lbl.set_justify(Gtk.Justification.CENTER)

        conteudo.append(icone_lbl)
        conteudo.append(texto_lbl)
        btn.set_child(conteudo)

        btn.connect('clicked', lambda _, d=destino: self.on_navigate(d))
        return btn