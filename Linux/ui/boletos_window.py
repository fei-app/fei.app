import gi
import subprocess
import sys
gi.require_version('Gtk', '4.0')
from gi.repository import Gtk, GLib

from dados import Dados, SessionExpiredException
from models import Boleto


class BoletosWindow(Gtk.Box):
    def __init__(self, app, session, run_async_callback, on_back):
        super().__init__(orientation=Gtk.Orientation.VERTICAL, spacing=0)
        self.app = app
        self.session = session
        self.run_async = run_async_callback
        self.on_back = on_back
        self.cache = app.cache
        self.boletos: list[Boleto] = []

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
        title = Gtk.Label(label="Boletos")
        title.add_css_class('title-2')
        self.append(title)

        # ---- Barra offline (oculta por padrão) ----
        self.bar_offline = Gtk.Label(label="⚠ Sem conexão — exibindo dados em cache")
        self.bar_offline.add_css_class('offline-bar')
        self.bar_offline.set_xalign(0)
        self.bar_offline.set_margin_start(12)
        self.bar_offline.set_margin_end(12)
        self.bar_offline.set_margin_bottom(4)
        self.bar_offline.set_visible(False)
        self.append(self.bar_offline)

        # ---- Área de conteúdo ----
        self.scroll = Gtk.ScrolledWindow()
        self.scroll.set_policy(Gtk.PolicyType.NEVER, Gtk.PolicyType.AUTOMATIC)
        self.scroll.set_vexpand(True)
        self.append(self.scroll)

        self.listbox = Gtk.ListBox()
        self.listbox.set_selection_mode(Gtk.SelectionMode.NONE)
        self.listbox.set_margin_start(8)
        self.listbox.set_margin_end(8)
        self.listbox.set_margin_top(4)
        self.listbox.set_margin_bottom(4)
        self.scroll.set_child(self.listbox)

        # ---- Estado vazio ----
        self.label_no_data = Gtk.Label(label="Nenhum boleto encontrado.")
        self.label_no_data.set_visible(False)
        self.label_no_data.set_halign(Gtk.Align.CENTER)
        self.label_no_data.set_valign(Gtk.Align.CENTER)
        self.label_no_data.set_vexpand(True)
        self.append(self.label_no_data)

        # ---- Spinner ----
        self.spinner = Gtk.Spinner()
        self.spinner.set_visible(False)
        self.spinner.set_halign(Gtk.Align.CENTER)
        self.spinner.set_margin_top(24)
        self.spinner.set_margin_bottom(24)
        self.append(self.spinner)

        # ---- Erro + botão tentar novamente ----
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
        self._set_loading(True)
        self._hide_error()
        self.bar_offline.set_visible(False)

        try:
            boletos = await Dados.fetch_boletos(self.session, self.cache)
            self.cache.save('boletos', [b.__dict__ for b in boletos])
            self.boletos = self._ordenar(boletos)
            GLib.idle_add(self._mostrar_lista)

        except SessionExpiredException:
            cached = self.cache.load('boletos', Boleto)
            if cached:
                self.boletos = self._ordenar(cached)
                GLib.idle_add(self.bar_offline.set_visible, True)
                GLib.idle_add(self._mostrar_lista)
            else:
                GLib.idle_add(self._show_error, "Sessão expirada e sem cache disponível.")

        except Exception as e:
            cached = self.cache.load('boletos', Boleto)
            if cached:
                self.boletos = self._ordenar(cached)
                GLib.idle_add(self.bar_offline.set_visible, True)
                GLib.idle_add(self._mostrar_lista)
            else:
                GLib.idle_add(self._show_error, f"Erro ao carregar boletos: {e}")

        finally:
            GLib.idle_add(self._set_loading, False)

    @staticmethod
    def _ordenar(boletos: list[Boleto]) -> list[Boleto]:
        """Ordena por vencimento decrescente (dd/mm/yyyy → yyyy-mm-dd para comparação)."""
        def chave(b: Boleto) -> str:
            partes = b.vencimento.split('/')
            if len(partes) == 3:
                return f"{partes[2]}-{partes[1]}-{partes[0]}"
            return b.vencimento
        return sorted(boletos, key=chave, reverse=True)

    # ===================== RENDERIZAÇÃO DA LISTA =====================

    def _mostrar_lista(self):
        # Limpa listbox
        while (child := self.listbox.get_first_child()):
            self.listbox.remove(child)

        if not self.boletos:
            self.label_no_data.set_visible(True)
            self.scroll.set_visible(False)
            return

        self.label_no_data.set_visible(False)
        self.scroll.set_visible(True)

        for boleto in self.boletos:
            row = self._criar_row(boleto)
            self.listbox.append(row)

    def _criar_row(self, boleto: Boleto) -> Gtk.Box:
        is_pago = boleto.status.upper() == 'PAGO'

        # Container do card
        card = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=4)
        card.set_margin_start(4)
        card.set_margin_end(4)
        card.set_margin_top(6)
        card.set_margin_bottom(6)

        # --- Linha 1: vencimento + chip de status ---
        linha1 = Gtk.Box(orientation=Gtk.Orientation.HORIZONTAL, spacing=8)

        venc_lbl = Gtk.Label(label=f"Venc.: {boleto.vencimento}")
        venc_lbl.add_css_class('boleto-venc')
        venc_lbl.set_xalign(0)
        venc_lbl.set_hexpand(True)
        linha1.append(venc_lbl)

        status_lbl = Gtk.Label(label=boleto.status)
        status_lbl.add_css_class('badge')
        status_lbl.add_css_class('badge-pago' if is_pago else 'badge-aberto')
        linha1.append(status_lbl)

        card.append(linha1)

        # --- Linha 2: data de pagamento (apenas se pago e preenchida) ---
        if is_pago and boleto.data_pagamento.strip():
            datapag_lbl = Gtk.Label(label=f"Pago em: {boleto.data_pagamento}")
            datapag_lbl.add_css_class('boleto-datapag')
            datapag_lbl.set_xalign(0)
            card.append(datapag_lbl)

        # --- Linha 3: botão baixar (apenas se não pago e tem ID) ---
        if not is_pago and boleto.titulo_id.strip():
            btn_baixar = Gtk.Button(label="⬇ Baixar PDF")
            btn_baixar.set_halign(Gtk.Align.START)
            btn_baixar.set_margin_top(4)
            btn_baixar.connect('clicked', lambda _, b=boleto: self.run_async(
                self._baixar_boleto(b)
            ))
            card.append(btn_baixar)

        return card

    # ===================== DOWNLOAD =====================

    async def _baixar_boleto(self, boleto: Boleto):
        GLib.idle_add(self._toast, "Gerando boleto, aguarde…")
        try:
            caminho = await Dados.baixar_boleto(self.session, boleto.titulo_id, boleto.vencimento)
        except Exception as e:
            GLib.idle_add(self._toast, f"Erro ao gerar boleto: {e}")
            return

        if caminho is None:
            GLib.idle_add(self._toast, "Erro ao gerar boleto. Tente novamente.")
            return

        GLib.idle_add(self._toast, f"Salvo em {caminho.parent.name}/{caminho.name}")
        GLib.idle_add(self._abrir_pdf, str(caminho))

    @staticmethod
    def _abrir_pdf(path: str):
        """Abre o PDF com o visualizador padrão do sistema via xdg-open."""
        try:
            subprocess.Popen(['xdg-open', path],
                             stdout=subprocess.DEVNULL,
                             stderr=subprocess.DEVNULL)
        except Exception as e:
            print(f"[WARN] Não foi possível abrir o PDF: {e}", file=sys.stderr)

    # ===================== HELPERS DE UI =====================

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

    def _toast(self, msg: str):
        """Fallback simples: imprime no stderr (substitua por um Adw.Toast se migrar para libadwaita)."""
        print(f"[BOLETOS] {msg}", file=sys.stderr)