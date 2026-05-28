import gi
import datetime
gi.require_version('Gtk', '4.0')
gi.require_version('Gio', '2.0')
from gi.repository import Gtk, Gio, GLib
from dados import Dados, SessionExpiredException
from models import ProvaCalendario
from cache_manager import CacheManager


class CalendarioWindow(Gtk.Box):
    def __init__(self, app, session, run_async_callback, on_back):
        super().__init__(orientation=Gtk.Orientation.VERTICAL, spacing=0)
        self.app = app
        self.session = session
        self.run_async = run_async_callback
        self.on_back = on_back
        self.cache = app.cache
        self.provas_todas: list[ProvaCalendario] = []
        self.mes_selecionado = datetime.date.today().month
        self.filtro_tipo = 'TODOS'

        # ---- Barra superior ----
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

        # ---- Título ----
        title = Gtk.Label(label="Calendário de Provas")
        title.add_css_class('title-2')
        self.append(title)

        # ---- Filtros ----
        filter_box = Gtk.Box(orientation=Gtk.Orientation.HORIZONTAL, spacing=6)
        filter_box.set_margin_start(12)
        filter_box.set_margin_end(12)
        filter_box.set_margin_top(6)
        filter_box.set_margin_bottom(6)

        meses = ['Janeiro', 'Fevereiro', 'Março', 'Abril', 'Maio', 'Junho',
                 'Julho', 'Agosto', 'Setembro', 'Outubro', 'Novembro', 'Dezembro']
        self.mes_combo = Gtk.ComboBoxText()
        for m in meses:
            self.mes_combo.append_text(m)
        self.mes_combo.set_active(datetime.date.today().month - 1)
        self.mes_combo.connect('changed', self.on_mes_changed)
        filter_box.append(self.mes_combo)

        self.tipo_combo = Gtk.ComboBoxText()
        for label, valor in [("Todas", "TODOS"), ("P1", "P1"), ("P2", "P2"), ("P3", "P3")]:
            self.tipo_combo.append(valor, label)
        self.tipo_combo.set_active_id("TODOS")
        self.tipo_combo.connect('changed', self.on_tipo_changed)
        filter_box.append(self.tipo_combo)

        self.append(filter_box)

        # ---- Lista ----
        self.scroll = Gtk.ScrolledWindow()
        self.scroll.set_policy(Gtk.PolicyType.NEVER, Gtk.PolicyType.AUTOMATIC)
        self.scroll.set_vexpand(True)
        self.append(self.scroll)

        self.listbox = Gtk.ListBox()
        self.listbox.set_selection_mode(Gtk.SelectionMode.NONE)
        self.scroll.set_child(self.listbox)

        # Mensagem lista vazia
        self.label_no_data = Gtk.Label(label="Nenhuma prova encontrada.")
        self.label_no_data.set_visible(False)
        self.label_no_data.set_halign(Gtk.Align.CENTER)
        self.label_no_data.set_valign(Gtk.Align.CENTER)
        self.label_no_data.set_vexpand(True)
        self.append(self.label_no_data)

        self.spinner = Gtk.Spinner()
        self.spinner.set_visible(False)
        self.append(self.spinner)

        self.label_error = Gtk.Label()
        self.label_error.add_css_class('error')
        self.label_error.set_visible(False)
        self.append(self.label_error)

        self.run_async(self._carregar_dados())

    def on_mes_changed(self, combo):
        self.mes_selecionado = combo.get_active() + 1
        self.aplicar_filtros()

    def on_tipo_changed(self, combo):
        tipo = combo.get_active_id()
        if tipo:
            self.filtro_tipo = tipo
            self.aplicar_filtros()

    def aplicar_filtros(self):
        while True:
            row = self.listbox.get_first_child()
            if row is None:
                break
            self.listbox.remove(row)

        provas_filtradas = []
        for prova in self.provas_todas:
            if self.filtro_tipo != 'TODOS' and prova.tipo_prova != self.filtro_tipo:
                continue
            partes = prova.data_prova.split('/')
            if len(partes) == 2:
                try:
                    mes = int(partes[1])
                except ValueError:
                    continue
                if mes != self.mes_selecionado:
                    continue
            else:
                continue
            provas_filtradas.append(prova)

        if not provas_filtradas:
            self.label_no_data.set_visible(True)
            self.listbox.set_visible(False)
        else:
            self.label_no_data.set_visible(False)
            self.listbox.set_visible(True)
            for prova in provas_filtradas:
                self.listbox.append(self._criar_row(prova))

    def _criar_row(self, prova: ProvaCalendario) -> Gtk.Box:
        box = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=2)
        box.set_margin_start(6)
        box.set_margin_end(6)
        box.set_margin_top(4)
        box.set_margin_bottom(4)

        linha1 = Gtk.Box(orientation=Gtk.Orientation.HORIZONTAL, spacing=6)
        nome = Gtk.Label(label=f"{prova.disciplina} - {prova.nome_disciplina}")
        nome.set_xalign(0)
        nome.set_hexpand(True)
        tipo = Gtk.Label(label=prova.tipo_prova)
        tipo.add_css_class('badge')
        tipo.add_css_class('badge-warning' if prova.tipo_prova == 'P3' else 'badge-primary')
        linha1.append(nome)
        linha1.append(tipo)

        linha2 = Gtk.Box(orientation=Gtk.Orientation.HORIZONTAL, spacing=12)
        linha2.append(Gtk.Label(label=f"{prova.data_prova} - {prova.hora}"))
        linha2.append(Gtk.Label(label=f"Sala: {prova.sala}"))
        linha2.append(Gtk.Label(label=f"Coord: {prova.coordenador}"))

        box.append(linha1)
        box.append(linha2)
        return box

    async def _carregar_dados(self):
        self._set_loading(True)
        self.label_error.set_visible(False)
        try:
            provas = await Dados.fetch_calendario_provas(self.session, self.cache)
            self.provas_todas = provas
            self.cache.save('calendario_provas', [p.__dict__ for p in provas])
        except SessionExpiredException:
            cached = self.cache.load('calendario_provas', ProvaCalendario)
            if cached:
                self.provas_todas = cached
                self.label_error.set_label("Sessão expirada. Exibindo dados do cache offline.")
            else:
                self.label_error.set_label("Sessão expirada e sem cache disponível.")
            self.label_error.set_visible(True)
        except Exception as e:
            cached = self.cache.load('calendario_provas', ProvaCalendario)
            if cached:
                self.provas_todas = cached
                self.label_error.set_label("Erro de rede. Modo offline.")
            else:
                self.label_error.set_label(f"Erro: {str(e)}")
            self.label_error.set_visible(True)
        finally:
            self._set_loading(False)
        GLib.idle_add(self.aplicar_filtros)

    def _set_loading(self, loading: bool):
        self.spinner.set_visible(loading)
        if loading:
            self.spinner.start()
        else:
            self.spinner.stop()