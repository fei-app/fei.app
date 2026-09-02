import asyncio
import gi
gi.require_version('Gtk', '4.0')
from gi.repository import Gtk, GLib

from dados import Dados, SessionExpiredException
from models import Nota, Disciplina


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

        # Container interno do scroll
        self.content_box = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=0)
        self.content_box.set_margin_start(12)
        self.content_box.set_margin_end(12)
        self.content_box.set_margin_top(8)
        self.content_box.set_margin_bottom(12)
        self.scroll.set_child(self.content_box)

        # Gtk.FlowBox para organizar os cartões dinamicamente em 1 ou 2 colunas
        self.flow_box = Gtk.FlowBox()
        self.flow_box.set_valign(Gtk.Align.START)
        self.flow_box.set_selection_mode(Gtk.SelectionMode.NONE)
        self.flow_box.set_max_children_per_line(2)
        self.flow_box.set_min_children_per_line(1)
        self.flow_box.set_homogeneous(True)
        self.flow_box.set_column_spacing(12)
        self.flow_box.set_row_spacing(12)
        self.content_box.append(self.flow_box)

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
            # Busca notas, disciplinas e médias em paralelo
            notas, disciplinas, medias = await asyncio.gather(
                Dados.fetch_notas(self.session, self.cache),
                self._fetch_disciplinas_safe(),
                self._fetch_medias_safe()
            )

            # Persiste cache
            self.cache.save('notas', [n.__dict__ for n in notas])
            self.cache.save('disciplinas', [d.__dict__ for d in disciplinas])
            try:
                self.cache.save('medias', medias)
            except Exception:
                pass

            GLib.idle_add(self._renderizar, notas, disciplinas, medias, False)

        except SessionExpiredException:
            notas = self.cache.load('notas', Nota) or []
            disciplinas = self.cache.load('disciplinas', Disciplina) or []
            medias = self._load_medias_cache()
            
            GLib.idle_add(self.bar_offline.set_visible, True)
            GLib.idle_add(self._renderizar, notas, disciplinas, medias, True)

        except Exception as e:
            notas = self.cache.load('notas', Nota) or []
            disciplinas = self.cache.load('disciplinas', Disciplina) or []
            medias = self._load_medias_cache()
            
            if notas:
                GLib.idle_add(self.bar_offline.set_visible, True)
                GLib.idle_add(self._renderizar, notas, disciplinas, medias, True)
            else:
                GLib.idle_add(self._show_error, f"Erro ao carregar notas: {e}")

        finally:
            GLib.idle_add(self._set_loading, False)

    async def _fetch_disciplinas_safe(self) -> list[Disciplina]:
        try:
            return await Dados.fetch_disciplinas(self.session)
        except Exception:
            return self.cache.load('disciplinas', Disciplina) or []

    async def _fetch_medias_safe(self) -> dict:
        try:
            return await Dados.fetch_medias(self.session)
        except Exception:
            return self._load_medias_cache()

    def _load_medias_cache(self) -> dict:
        try:
            res = self.cache.load('medias', dict)
            return res if res else {}
        except Exception:
            try:
                res = self.cache.load('medias')
                return res if isinstance(res, dict) else {}
            except Exception:
                return {}

    # ===================== RENDERIZAÇÃO =====================

    def _renderizar(self, notas: list[Nota], disciplinas: list[Disciplina], medias: dict, offline: bool):
        self._limpar_conteudo()

        if not notas:
            self._show_empty()
            return

        disciplinas_map = {d.codigo: d.nome for d in disciplinas}
        notas_por_disc = {}
        
        for n in notas:
            notas_por_disc.setdefault(n.codigo_disciplina, []).append(n)

        # Ordenar os cartões alfabeticamente pelo nome da disciplina
        chaves_ordenadas = sorted(notas_por_disc.keys(), key=lambda cod: disciplinas_map.get(cod, cod))

        for cod in chaves_ordenadas:
            nome = disciplinas_map.get(cod, cod)
            lista_notas = notas_por_disc[cod]
            media_val = medias.get(cod, "")

            card = self._criar_cartao(cod, nome, lista_notas, media_val)
            self.flow_box.append(card)

        self.scroll.set_visible(True)
        self.label_no_data.set_visible(False)

    def _criar_cartao(self, codigo: str, nome: str, notas: list[Nota], media: str) -> Gtk.Box:
            # Cartão propriamente dito
            card = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=8)
            card.add_css_class('nota-card')

            # Título
            title = Gtk.Label(label=f"{codigo} - {nome}")
            title.add_css_class('nota-card-title')
            title.set_xalign(0)
            title.set_wrap(True)
            card.append(title)

            # Lista de provas (P1, P2, PJ...)
            notas_box = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=4)
            for nota in notas:
                val = nota.valor if nota.valor.strip() else "--"
                lbl = Gtk.Label(label=f"{nota.tipo_prova}: {val}")
                lbl.add_css_class('nota-card-item')
                lbl.set_xalign(0)
                notas_box.append(lbl)
            card.append(notas_box)

            # Lógica de exibição da Média (sem divisor, conforme solicitado)
            if media.strip():
                lbl_media = Gtk.Label()
                lbl_media.set_xalign(0)
                lbl_media.set_margin_top(4) # Um pequeno respiro visual

                try:
                    val_float = float(media.replace(',', '.'))
                    if val_float >= 5.0:
                        lbl_media.set_text(f"Média: {media} (APROVADO)")
                        lbl_media.add_css_class('nota-media-aprovado')
                    else:
                        lbl_media.set_text(f"Média: {media} (REPROVADO)")
                        lbl_media.add_css_class('nota-media-reprovado')
                except ValueError:
                    lbl_media.set_text(f"Média: {media}")
                    lbl_media.add_css_class('nota-card-item')

                card.append(lbl_media)

            return card

    # ===================== HELPERS DE UI =====================

    def _limpar_conteudo(self):
        while (c := self.flow_box.get_first_child()):
            self.flow_box.remove(c)

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