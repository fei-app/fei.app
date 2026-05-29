import asyncio
import gi
gi.require_version('Gtk', '4.0')
from gi.repository import Gtk, GLib

from dados import Dados, SessionExpiredException
from models import Nota, Disciplina


# Colunas fixas, sempre exibidas (mesma lógica do Android: tiposFixos)
TIPOS_FIXOS = ['P1', 'P2', 'P3', 'PJ']


class NotasWindow(Gtk.Box):
    def __init__(self, app, session, run_async_callback, on_back):
        super().__init__(orientation=Gtk.Orientation.VERTICAL, spacing=0)
        self.app = app
        self.session = session
        self.run_async = run_async_callback
        self.on_back = on_back
        self.cache = app.cache

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
        title = Gtk.Label(label="Notas")
        title.add_css_class('title-2')
        self.append(title)

        # ---- Barra offline ----
        self.bar_offline = Gtk.Label(label="⚠ Sem conexão — exibindo dados em cache")
        self.bar_offline.add_css_class('offline-bar')
        self.bar_offline.set_xalign(0)
        self.bar_offline.set_margin_start(12)
        self.bar_offline.set_margin_end(12)
        self.bar_offline.set_margin_bottom(4)
        self.bar_offline.set_visible(False)
        self.append(self.bar_offline)

        # ---- Spinner ----
        self.spinner = Gtk.Spinner()
        self.spinner.set_visible(False)
        self.spinner.set_halign(Gtk.Align.CENTER)
        self.spinner.set_margin_top(32)
        self.spinner.set_margin_bottom(32)
        self.append(self.spinner)

        # ---- Área de conteúdo rolável ----
        self.scroll = Gtk.ScrolledWindow()
        self.scroll.set_policy(Gtk.PolicyType.AUTOMATIC, Gtk.PolicyType.AUTOMATIC)
        self.scroll.set_vexpand(True)
        self.scroll.set_visible(False)
        self.append(self.scroll)

        # Container interno do scroll: tabela + legenda
        self.content_box = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=0)
        self.content_box.set_margin_start(8)
        self.content_box.set_margin_end(8)
        self.content_box.set_margin_top(4)
        self.content_box.set_margin_bottom(12)
        self.scroll.set_child(self.content_box)

        # Placeholder da tabela (substituído ao renderizar)
        self.table_box = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=0)
        self.content_box.append(self.table_box)

        # Separador entre tabela e legenda
        self.sep = Gtk.Separator(orientation=Gtk.Orientation.HORIZONTAL)
        self.sep.add_css_class('notas-separator')
        self.sep.set_visible(False)
        self.content_box.append(self.sep)

        # Legenda
        self.legend_box = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=2)
        self.legend_box.set_margin_start(4)
        self.legend_box.set_margin_top(4)
        self.legend_box.set_visible(False)
        self.content_box.append(self.legend_box)

        # ---- Estado vazio ----
        self.label_no_data = Gtk.Label(label="Nenhuma nota encontrada.")
        self.label_no_data.set_visible(False)
        self.label_no_data.set_halign(Gtk.Align.CENTER)
        self.label_no_data.set_valign(Gtk.Align.CENTER)
        self.label_no_data.set_vexpand(True)
        self.append(self.label_no_data)

        # ---- Erro + retry ----
        error_box = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=8)
        error_box.set_halign(Gtk.Align.CENTER)
        error_box.set_margin_top(12)
        error_box.set_margin_bottom(12)

        self.label_error = Gtk.Label()
        self.label_error.add_css_class('error')
        self.label_error.set_visible(False)
        self.label_error.set_wrap(True)
        error_box.append(self.label_error)

        self.btn_tentar = Gtk.Button(label="Tentar novamente")
        self.btn_tentar.set_visible(False)
        self.btn_tentar.set_halign(Gtk.Align.CENTER)
        self.btn_tentar.connect('clicked', lambda _: self.run_async(self._carregar_dados()))
        error_box.append(self.btn_tentar)

        self.append(error_box)

        self.run_async(self._carregar_dados())

    # ===================== CARREGAMENTO =====================

    async def _carregar_dados(self):
        GLib.idle_add(self._set_loading, True)
        GLib.idle_add(self._hide_error)
        GLib.idle_add(self.bar_offline.set_visible, False)

        try:
            # Busca notas e disciplinas em paralelo (igual ao Android com async/await)
            notas, disciplinas = await asyncio.gather(
                Dados.fetch_notas(self.session, self.cache),
                self._fetch_disciplinas_safe(),
            )

            # Persiste cache
            self.cache.save('notas', [n.__dict__ for n in notas])
            self.cache.save('disciplinas', [d.__dict__ for d in disciplinas])

            GLib.idle_add(self._renderizar, notas, disciplinas, False)

        except SessionExpiredException:
            notas = self.cache.load('notas', Nota) or []
            disciplinas = self.cache.load('disciplinas', Disciplina) or []
            GLib.idle_add(self.bar_offline.set_visible, True)
            GLib.idle_add(self._renderizar, notas, disciplinas, True)

        except Exception as e:
            notas = self.cache.load('notas', Nota) or []
            disciplinas = self.cache.load('disciplinas', Disciplina) or []
            if notas:
                GLib.idle_add(self.bar_offline.set_visible, True)
                GLib.idle_add(self._renderizar, notas, disciplinas, True)
            else:
                GLib.idle_add(self._show_error, f"Erro ao carregar notas: {e}")

        finally:
            GLib.idle_add(self._set_loading, False)

    async def _fetch_disciplinas_safe(self) -> list[Disciplina]:
        try:
            return await Dados.fetch_disciplinas(self.session)
        except Exception:
            return self.cache.load('disciplinas', Disciplina) or []

    # ===================== RENDERIZAÇÃO =====================

    def _renderizar(self, notas: list[Nota], disciplinas: list[Disciplina], offline: bool):
        self._limpar_conteudo()

        if not notas:
            self._show_empty()
            return

        tipos_visiveis = self._calcular_tipos_visiveis(notas)
        disciplinas_map = self._agrupar_notas(notas)

        self._build_table(disciplinas_map, tipos_visiveis)
        self._build_legend(disciplinas)

        self.scroll.set_visible(True)
        self.label_no_data.set_visible(False)

    @staticmethod
    def _agrupar_notas(notas: list[Nota]) -> dict[str, dict[str, str]]:
        """codigo_disciplina → {tipo_prova → valor}  (ordem de inserção preservada)."""
        resultado: dict[str, dict[str, str]] = {}
        for nota in notas:
            resultado.setdefault(nota.codigo_disciplina, {})[nota.tipo_prova] = nota.valor
        return resultado

    @staticmethod
    def _calcular_tipos_visiveis(notas: list[Nota]) -> list[str]:
        """
        Tipos fixos + extras que tenham ao menos um valor não-vazio,
        ordenados alfabeticamente — igual ao Android.
        """
        todos_tipos = {n.tipo_prova for n in notas}
        extras = [
            t for t in todos_tipos
            if t not in TIPOS_FIXOS and any(
                n.tipo_prova == t and n.valor and n.valor not in ('-', '--')
                for n in notas
            )
        ]
        return sorted(set(TIPOS_FIXOS) | set(extras))

    # ---- Tabela ----

    def _build_table(self, disciplinas_map: dict[str, dict[str, str]], tipos: list[str]):
        # Limpa tabela anterior
        while (c := self.table_box.get_first_child()):
            self.table_box.remove(c)

        # Cabeçalho
        header = self._criar_linha_header(['Código'] + tipos)
        self.table_box.append(header)

        # Linhas de dados
        for i, (codigo, notas_map) in enumerate(disciplinas_map.items()):
            valores = [notas_map.get(t, '') or '--' for t in tipos]
            row = self._criar_linha_dado(codigo, valores, alternada=(i % 2 == 1))
            self.table_box.append(row)

    def _criar_linha_header(self, colunas: list[str]) -> Gtk.Box:
        row = Gtk.Box(orientation=Gtk.Orientation.HORIZONTAL, spacing=0)
        row.add_css_class('notas-header')
        for texto in colunas:
            lbl = Gtk.Label(label=texto)
            lbl.add_css_class('notas-cell-header')
            lbl.set_hexpand(True)
            lbl.set_xalign(0.5)
            row.append(lbl)
        return row

    def _criar_linha_dado(self, codigo: str, valores: list[str], alternada: bool) -> Gtk.Box:
        row = Gtk.Box(orientation=Gtk.Orientation.HORIZONTAL, spacing=0)
        if alternada:
            row.add_css_class('notas-row-alt')

        # Célula do código — alinhada à esquerda
        cod_lbl = Gtk.Label(label=codigo)
        cod_lbl.add_css_class('notas-cell')
        cod_lbl.set_hexpand(True)
        cod_lbl.set_xalign(0.0)
        row.append(cod_lbl)

        # Células de valor — centralizadas
        for valor in valores:
            val_lbl = Gtk.Label(label=valor)
            val_lbl.add_css_class('notas-cell')
            val_lbl.set_hexpand(True)
            val_lbl.set_xalign(0.5)
            row.append(val_lbl)

        return row

    # ---- Legenda ----

    def _build_legend(self, disciplinas: list[Disciplina]):
        # Limpa legenda anterior
        while (c := self.legend_box.get_first_child()):
            self.legend_box.remove(c)

        if not disciplinas:
            self.sep.set_visible(False)
            self.legend_box.set_visible(False)
            return

        self.sep.set_visible(True)
        self.legend_box.set_visible(True)

        titulo = Gtk.Label(label="Legenda de Disciplinas")
        titulo.add_css_class('legend-title')
        titulo.set_xalign(0)
        self.legend_box.append(titulo)

        for d in disciplinas:
            item = Gtk.Label(label=f"{d.codigo} — {d.nome}")
            item.add_css_class('legend-item')
            item.set_xalign(0)
            item.set_wrap(True)
            self.legend_box.append(item)

    # ===================== HELPERS DE UI =====================

    def _limpar_conteudo(self):
        while (c := self.table_box.get_first_child()):
            self.table_box.remove(c)
        while (c := self.legend_box.get_first_child()):
            self.legend_box.remove(c)
        self.sep.set_visible(False)
        self.legend_box.set_visible(False)

    def _show_empty(self):
        self.scroll.set_visible(False)
        self.label_no_data.set_visible(True)

    def _set_loading(self, loading: bool):
        self.spinner.set_visible(loading)
        if loading:
            self.spinner.start()
            self.scroll.set_visible(False)
            self.label_no_data.set_visible(False)
        else:
            self.spinner.stop()

    def _show_error(self, msg: str):
        self.label_error.set_label(msg)
        self.label_error.set_visible(True)
        self.btn_tentar.set_visible(True)

    def _hide_error(self):
        self.label_error.set_visible(False)
        self.btn_tentar.set_visible(False)