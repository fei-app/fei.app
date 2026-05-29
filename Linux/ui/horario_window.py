import datetime
import gi
gi.require_version('Gtk', '4.0')
from gi.repository import Gtk, GLib

from dados import Dados, SessionExpiredException
from models import Aula


ORDEM_DIAS = ['Segunda', 'Terça', 'Quarta', 'Quinta', 'Sexta', 'Sábado']


def _dia_atual() -> str:
    nomes = ['Segunda', 'Terça', 'Quarta', 'Quinta', 'Sexta', 'Sábado', '']
    return nomes[datetime.date.today().weekday()]


def _proximo_dia_com_aula(dias_com_aula: set[str]) -> str:
    """
    A partir de hoje, percorre a semana em ordem até achar um dia com aula.
    Porta exata de proximoDiaComAula() do Android.
    """
    if not dias_com_aula:
        return ORDEM_DIAS[0]
    hoje = _dia_atual()
    idx_hoje = ORDEM_DIAS.index(hoje) if hoje in ORDEM_DIAS else 0
    for offset in range(len(ORDEM_DIAS)):
        candidato = ORDEM_DIAS[(idx_hoje + offset) % len(ORDEM_DIAS)]
        if candidato in dias_com_aula:
            return candidato
    return next(iter(dias_com_aula))


class HorarioWindow(Gtk.Box):
    def __init__(self, app, session, run_async_callback, on_back):
        super().__init__(orientation=Gtk.Orientation.VERTICAL, spacing=0)
        self.app = app
        self.session = session
        self.run_async = run_async_callback
        self.on_back = on_back
        self.cache = app.cache

        self._dias_visiveis: list[str] = []
        self._chip_buttons: dict[str, Gtk.ToggleButton] = {}
        self._bloqueio_chip = False  # evita loop de sinal ao atualizar chips

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
        title = Gtk.Label(label="Horário de Aula")
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

        # ---- Mensagem genérica (vazio / erro inline) ----
        self.tv_message = Gtk.Label()
        self.tv_message.add_css_class('aula-empty')
        self.tv_message.set_halign(Gtk.Align.CENTER)
        self.tv_message.set_valign(Gtk.Align.CENTER)
        self.tv_message.set_vexpand(True)
        self.tv_message.set_visible(False)
        self.append(self.tv_message)

        # ---- Container principal (chips + stack) ----
        self.main_content = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=0)
        self.main_content.set_visible(False)
        self.append(self.main_content)

        # Scroll horizontal dos chips
        chip_scroll = Gtk.ScrolledWindow()
        chip_scroll.set_policy(Gtk.PolicyType.AUTOMATIC, Gtk.PolicyType.NEVER)
        chip_scroll.set_margin_start(8)
        chip_scroll.set_margin_end(8)
        chip_scroll.set_margin_top(4)
        chip_scroll.set_margin_bottom(8)
        self.main_content.append(chip_scroll)

        self.chip_box = Gtk.Box(orientation=Gtk.Orientation.HORIZONTAL, spacing=6)
        self.chip_box.set_margin_start(4)
        self.chip_box.set_margin_end(4)
        chip_scroll.set_child(self.chip_box)

        # Stack de páginas por dia (transição deslizante)
        self.stack = Gtk.Stack()
        self.stack.set_transition_type(Gtk.StackTransitionType.SLIDE_LEFT_RIGHT)
        self.stack.set_transition_duration(200)
        self.stack.set_vexpand(True)
        self.main_content.append(self.stack)

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
            aulas = await Dados.fetch_aulas(self.session, self.cache)
            self.cache.save('aulas', [a.__dict__ for a in aulas])
            GLib.idle_add(self._renderizar, aulas, False)

        except SessionExpiredException:
            from models import Aula as AulaModel
            cached = self.cache.load('aulas', AulaModel) or []
            GLib.idle_add(self.bar_offline.set_visible, bool(cached))
            GLib.idle_add(self._renderizar, cached, True)

        except Exception as e:
            from models import Aula as AulaModel
            cached = self.cache.load('aulas', AulaModel) or []
            if cached:
                GLib.idle_add(self.bar_offline.set_visible, True)
                GLib.idle_add(self._renderizar, cached, True)
            else:
                GLib.idle_add(self._show_error, f"Erro ao carregar horários: {e}")

        finally:
            GLib.idle_add(self._set_loading, False)

    # ===================== RENDERIZAÇÃO =====================

    def _renderizar(self, aulas: list[Aula], offline: bool):
        if not aulas:
            self._mostrar_mensagem("Nenhuma aula encontrada.")
            return

        # Agrupa e ordena por dia/hora
        aulas_por_dia: dict[str, list[Aula]] = {}
        for aula in aulas:
            aulas_por_dia.setdefault(aula.dia_semana, []).append(aula)
        for lista in aulas_por_dia.values():
            lista.sort(key=lambda a: a.hora_inicio)

        dias_com_aula = set(aulas_por_dia.keys())
        self._dias_visiveis = [d for d in ORDEM_DIAS if d in dias_com_aula]
        dia_inicial = _proximo_dia_com_aula(dias_com_aula)

        self._construir_chips(dia_inicial)
        self._construir_stack(aulas_por_dia)
        self._selecionar_dia(dia_inicial, animar=False)

        self.tv_message.set_visible(False)
        self.main_content.set_visible(True)

    def _construir_chips(self, dia_inicial: str):
        # Limpa chips anteriores
        while (c := self.chip_box.get_first_child()):
            self.chip_box.remove(c)
        self._chip_buttons.clear()

        primeiro_btn: Gtk.ToggleButton | None = None
        for dia in self._dias_visiveis:
            btn = Gtk.ToggleButton(label=dia)
            btn.add_css_class('day-chip')
            btn.set_active(dia == dia_inicial)

            # Agrupa para comportamento radio (igual ao ChipGroup checkable)
            if primeiro_btn is None:
                primeiro_btn = btn
            else:
                btn.set_group(primeiro_btn)

            btn.connect('toggled', self._on_chip_toggled, dia)
            self.chip_box.append(btn)
            self._chip_buttons[dia] = btn

    def _construir_stack(self, aulas_por_dia: dict[str, list[Aula]]):
        # Remove páginas anteriores
        while (c := self.stack.get_first_child()):
            self.stack.remove(c)

        for dia in self._dias_visiveis:
            pagina = self._criar_pagina_dia(dia, aulas_por_dia.get(dia, []))
            self.stack.add_named(pagina, dia)

    def _criar_pagina_dia(self, dia: str, aulas: list[Aula]) -> Gtk.Widget:
        scroll = Gtk.ScrolledWindow()
        scroll.set_policy(Gtk.PolicyType.NEVER, Gtk.PolicyType.AUTOMATIC)

        if not aulas:
            lbl = Gtk.Label(label=f"Nenhuma aula em {dia}")
            lbl.add_css_class('aula-empty')
            lbl.set_halign(Gtk.Align.CENTER)
            lbl.set_valign(Gtk.Align.CENTER)
            scroll.set_child(lbl)
            return scroll

        listbox = Gtk.ListBox()
        listbox.set_selection_mode(Gtk.SelectionMode.NONE)
        listbox.set_margin_top(4)
        listbox.set_margin_bottom(4)
        scroll.set_child(listbox)

        for aula in aulas:
            listbox.append(self._criar_card_aula(aula))

        return scroll

    def _criar_card_aula(self, aula: Aula) -> Gtk.Box:
        card = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=3)
        card.add_css_class('aula-card')

        # Linha 1: código (bold) + horário à direita
        linha1 = Gtk.Box(orientation=Gtk.Orientation.HORIZONTAL, spacing=6)

        cod_lbl = Gtk.Label(label=aula.codigo_disciplina)
        cod_lbl.add_css_class('aula-codigo')
        cod_lbl.set_xalign(0)
        cod_lbl.set_hexpand(True)
        linha1.append(cod_lbl)

        hora_lbl = Gtk.Label(label=f"{aula.hora_inicio} – {aula.hora_fim}")
        hora_lbl.add_css_class('aula-detalhe')
        hora_lbl.set_xalign(1)
        linha1.append(hora_lbl)

        card.append(linha1)

        # Linha 2: nome da disciplina
        nome = aula.nome_disciplina.strip() or aula.codigo_disciplina
        nome_lbl = Gtk.Label(label=nome)
        nome_lbl.add_css_class('aula-nome')
        nome_lbl.set_xalign(0)
        nome_lbl.set_wrap(True)
        card.append(nome_lbl)

        # Linha 3: sala
        sala_txt = aula.sala.strip() or "Sala não informada"
        sala_lbl = Gtk.Label(label=f"🏛 {sala_txt}")
        sala_lbl.add_css_class('aula-detalhe')
        sala_lbl.set_xalign(0)
        card.append(sala_lbl)

        return card

    # ===================== SELEÇÃO DE DIA =====================

    def _on_chip_toggled(self, btn: Gtk.ToggleButton, dia: str):
        # Ignora sinais disparados programaticamente
        if self._bloqueio_chip:
            return
        if btn.get_active():
            self._selecionar_dia(dia, animar=True)

    def _selecionar_dia(self, dia: str, animar: bool):
        if not animar:
            self.stack.set_transition_type(Gtk.StackTransitionType.NONE)
        else:
            # Direção da animação baseada na posição relativa dos dias
            atual = self.stack.get_visible_child_name() or ''
            idx_atual = self._dias_visiveis.index(atual) if atual in self._dias_visiveis else 0
            idx_novo  = self._dias_visiveis.index(dia)   if dia  in self._dias_visiveis else 0
            self.stack.set_transition_type(
                Gtk.StackTransitionType.SLIDE_LEFT if idx_novo > idx_atual
                else Gtk.StackTransitionType.SLIDE_RIGHT
            )

        self.stack.set_visible_child_name(dia)
        self.stack.set_transition_type(Gtk.StackTransitionType.SLIDE_LEFT_RIGHT)

        # Sincroniza chips sem disparar loop
        self._bloqueio_chip = True
        if dia in self._chip_buttons:
            self._chip_buttons[dia].set_active(True)
        self._bloqueio_chip = False

    # ===================== HELPERS DE UI =====================

    def _set_loading(self, loading: bool):
        self.spinner.set_visible(loading)
        if loading:
            self.spinner.start()
            self.main_content.set_visible(False)
            self.tv_message.set_visible(False)
        else:
            self.spinner.stop()

    def _mostrar_mensagem(self, msg: str):
        self.tv_message.set_label(msg)
        self.tv_message.set_visible(True)
        self.main_content.set_visible(False)

    def _show_error(self, msg: str):
        self.label_error.set_label(msg)
        self.label_error.set_visible(True)
        self.btn_tentar.set_visible(True)

    def _hide_error(self):
        self.label_error.set_visible(False)
        self.btn_tentar.set_visible(False)