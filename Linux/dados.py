import re
import datetime
import logging
from collections import defaultdict
from pathlib import Path

import aiohttp
from bs4 import BeautifulSoup

from models import Disciplina, Nota, Perfil, Aula, ProvaCalendario, Boleto
from cache_manager import CacheManager
from login_logic import LoginLogic

logger = logging.getLogger(__name__)


class SessionExpiredException(Exception):
    pass


class Dados:
    USER_AGENT = LoginLogic.USER_AGENT

    URL_DISCIPLINAS       = "https://interage.fei.org.br/secureserver/portal/graduacao/sala-dos-professores/consultas/tabela-de-aulas"
    URL_NOTAS             = "https://interage.fei.org.br/secureserver/portal/graduacao/secretaria/consultas/notas"
    URL_PERFIL            = "https://interage.fei.org.br/secureserver/portal/graduacao/secretaria/dados-pessoais"
    URL_HORARIO           = "https://interage.fei.org.br/secureserver/portal/graduacao/secretaria/consultas/horario/arquivo"
    URL_CALENDARIO_PROVAS = "https://interage.fei.org.br/secureserver/portal/graduacao/sala-dos-professores/informacoes-academicas/provas"
    URL_BOLETOS           = "https://interage.fei.org.br/secureserver/portal/graduacao/tesouraria/consultas/boletos"
    URL_GERAR_BOLETO      = "https://interage.fei.org.br/secureserver/portal/graduacao/tesouraria/consultas/boletos/titulos/gerar"

    # ===================== HELPER INTERNO =====================

    @staticmethod
    async def _fetch_page(session: aiohttp.ClientSession, url: str) -> BeautifulSoup:
        headers = {'User-Agent': Dados.USER_AGENT}
        async with session.get(url, headers=headers) as resp:
            if resp.status != 200:
                raise SessionExpiredException(f"HTTP {resp.status} ao acessar {url}")
            html = await resp.text()
        return BeautifulSoup(html, 'html.parser')

    @staticmethod
    def _extrair_horario(texto: str) -> tuple[str, str] | None:
        """Extrai (hora_inicio, hora_fim) de uma string como '08:00 - 09:40'."""
        match = re.search(r'(\d{2}:\d{2})\s*-\s*(\d{2}:\d{2})', texto)
        if not match:
            return None
        return match.group(1), match.group(2)

    @staticmethod
    def get_dia_semana_atual() -> str:
        """Retorna o nome do dia da semana atual no formato usado pela FEI."""
        nomes = ['Segunda', 'Terça', 'Quarta', 'Quinta', 'Sexta', 'Sábado', '']
        return nomes[datetime.date.today().weekday()]

    # ===================== DISCIPLINAS =====================

    @staticmethod
    async def fetch_disciplinas(session: aiohttp.ClientSession, cache: CacheManager = None) -> list[Disciplina]:
        soup = await Dados._fetch_page(session, Dados.URL_DISCIPLINAS)
        container = soup.select_one(
            'body > div.container > div:nth-child(2) > div.col-md-9 > div:nth-child(2)'
        )
        if not container:
            raise SessionExpiredException("Container de disciplinas não encontrado")
        tabela = container.select_one('table.table.table-striped')
        if not tabela:
            raise SessionExpiredException("Tabela de disciplinas não encontrada")
        disciplinas = []
        for row in tabela.select('tbody > tr'):
            cod_elem  = row.select_one('td.Código')
            nome_elem = row.select_one('td.Disciplina')
            if cod_elem and nome_elem:
                codigo = cod_elem.text.strip()
                nome   = nome_elem.text.strip()
                if codigo and nome:
                    disciplinas.append(Disciplina(codigo, nome))
        if not disciplinas:
            raise SessionExpiredException("Nenhuma disciplina encontrada")
        logger.debug("Disciplinas carregadas: %d", len(disciplinas))
        return disciplinas

    # ===================== NOTAS E MÉDIAS =====================

    @staticmethod
    async def fetch_notas(session: aiohttp.ClientSession, cache: CacheManager = None) -> list[Nota]:
        soup = await Dados._fetch_page(session, Dados.URL_NOTAS)
        container = soup.select_one(
            'body > div.container > div:nth-child(2) > div.col-md-9 > div:nth-child(5)'
        )
        if not container:
            raise SessionExpiredException("Container das notas não encontrado")

        notas = []
        for panel in container.select('div.panel.panel-default'):
            titulo_link = panel.select_one('.panel-title a.tabela-notas')
            if not titulo_link:
                continue
            partes = titulo_link.text.strip().split(' - ', 1)
            if len(partes) != 2:
                continue
            codigo, nome_disciplina = partes[0].strip(), partes[1].strip()

            tabela = panel.select_one('table.table.table-striped')
            if not tabela:
                continue

            for row in tabela.select('tbody > tr'):
                # Linhas de cabeçalho interno têm <b><i> na primeira célula
                if row.select_one('td:first-child b i'):
                    continue
                # BS4: class_ com string busca pelo nome exato da classe no conjunto
                avaliacao_elem = row.find('td', class_='Avaliação:')
                valor_elem     = row.find('td', class_='Valor:')
                if avaliacao_elem and valor_elem:
                    tipo_prova = avaliacao_elem.text.strip()
                    valor      = valor_elem.text.strip()
                    if tipo_prova:
                        notas.append(Nota(codigo, nome_disciplina, tipo_prova, valor))

        logger.debug("Notas carregadas: %d", len(notas))
        return notas

    @staticmethod
    async def fetch_medias(session: aiohttp.ClientSession, cache: CacheManager = None) -> dict[str, str]:
        soup = await Dados._fetch_page(session, Dados.URL_NOTAS)
        container = soup.select_one(
            'body > div.container > div:nth-child(2) > div.col-md-9 > div:nth-child(5)'
        )
        if not container:
            raise SessionExpiredException("Container das notas não encontrado")

        medias = {}
        for panel in container.select('div.panel.panel-default'):
            titulo_link = panel.select_one('.panel-title a.tabela-notas')
            if not titulo_link:
                continue
            partes = titulo_link.text.strip().split(' - ', 1)
            if len(partes) != 2:
                continue
            codigo = partes[0].strip()

            tabela = panel.select_one('table.table')
            if not tabela:
                continue

            for row in tabela.select('tbody > tr'):
                primeira_col = row.select_one('td:first-child')
                if primeira_col and primeira_col.text.strip().lower() == 'média':
                    cols = row.select('td')
                    if len(cols) >= 2:
                        valor = cols[1].text.strip()
                        medias[codigo] = valor
                    break

        logger.debug("Médias carregadas: %d", len(medias))
        return medias

    # ===================== PERFIL =====================

    @staticmethod
    async def fetch_perfil(session: aiohttp.ClientSession, cache: CacheManager = None) -> Perfil:
        soup = await Dados._fetch_page(session, Dados.URL_PERFIL)
        panel_body = soup.select_one(
            'body > div.container > div:nth-child(2) > div.col-md-9 '
            '> div.panel.panel-default.hidden-xs.bloco-conteudo-cabecalho > div.panel-body'
        )
        if not panel_body:
            raise SessionExpiredException("Painel de perfil não encontrado")

        nome = matricula = curso = ''
        for col in panel_body.children:
            if not hasattr(col, 'select_one'):
                continue
            b  = col.select_one('b')
            em = col.select_one('small em')
            if b and em:
                label = b.text.strip().lower()
                val   = em.text.strip()
                if label == 'nome':
                    nome = val
                elif label == 'matrícula':
                    matricula = val
                elif label == 'curso':
                    curso = val

        email = ''
        email_group = soup.select_one('#form-atualizar-dados-pessoais > div:nth-child(19)')
        if email_group:
            email_elem = email_group.select_one('p.form-control-static')
            if email_elem:
                email = email_elem.text.strip()

        logger.debug("Perfil carregado: %s", nome)
        return Perfil(nome, matricula, curso, email)

    # ===================== HORÁRIO DE AULAS =====================

    @staticmethod
    async def fetch_aulas(session: aiohttp.ClientSession, cache: CacheManager = None) -> list[Aula]:
        disciplinas = await Dados.fetch_disciplinas(session)
        mapa_nomes  = {d.codigo: d.nome for d in disciplinas}

        soup = await Dados._fetch_page(session, Dados.URL_HORARIO)
        tabela = soup.select_one('#tb_princ')
        if not tabela:
            raise SessionExpiredException("Tabela de horários não encontrada — sessão inválida")

        linhas = tabela.select('tr')
        if len(linhas) < 3:
            return []

        # (col_disciplina, col_sala) por dia da semana — mesmos índices do Android
        colunas_por_dia = {
            'Segunda': (1,  3),
            'Terça':   (4,  6),
            'Quarta':  (7,  9),
            'Quinta':  (10, 12),
            'Sexta':   (13, 15),
            'Sábado':  (17, 19),
        }
        aulas_por_dia: dict[str, list[Aula]] = {dia: [] for dia in colunas_por_dia}

        for row in linhas[2:]:
            cells = row.select('td')
            if len(cells) < 20:
                continue

            horario_padrao = Dados._extrair_horario(cells[0].text.strip())

            # O sábado pode ter uma célula marcada com classe contendo "sabado"
            horario_sabado = None
            for cell in cells:
                if any('sabado' in c.lower() for c in cell.get('class', [])):
                    horario_sabado = Dados._extrair_horario(cell.text.strip())
                    break

            for dia, (col_disc, col_sala) in colunas_por_dia.items():
                if col_disc >= len(cells) or col_sala >= len(cells):
                    continue
                codigo = re.sub(r'\s+', ' ', cells[col_disc].text.strip()).strip()
                sala   = cells[col_sala].text.strip()
                if not codigo:
                    continue
                horario = horario_sabado if dia == 'Sábado' else horario_padrao
                if horario:
                    aulas_por_dia[dia].append(
                        Aula(dia, codigo, '', sala, horario[0], horario[1])
                    )

        # Agrupar blocos consecutivos da mesma disciplina no mesmo dia
        aulas_agrupadas: list[Aula] = []
        for dia, lista in aulas_por_dia.items():
            if not lista:
                continue
            lista.sort(key=lambda a: a.hora_inicio)
            current = lista[0]
            for prox in lista[1:]:
                if current.codigo_disciplina == prox.codigo_disciplina:
                    current = Aula(
                        current.dia_semana, current.codigo_disciplina,
                        current.nome_disciplina, prox.sala,
                        current.hora_inicio, prox.hora_fim
                    )
                else:
                    aulas_agrupadas.append(current)
                    current = prox
            aulas_agrupadas.append(current)

        # Substituir código por nome completo
        resultado = [
            Aula(a.dia_semana, a.codigo_disciplina,
                 mapa_nomes.get(a.codigo_disciplina, a.codigo_disciplina),
                 a.sala, a.hora_inicio, a.hora_fim)
            for a in aulas_agrupadas
        ]
        logger.debug("Aulas carregadas: %d", len(resultado))
        return resultado

    @staticmethod
    async def fetch_aulas_dia(session: aiohttp.ClientSession, cache: CacheManager = None) -> list[Aula]:
        todas = await Dados.fetch_aulas(session, cache)
        dia   = Dados.get_dia_semana_atual()
        return [a for a in todas if a.dia_semana.lower() == dia.lower()]

    # ===================== CALENDÁRIO DE PROVAS =====================

    @staticmethod
    async def fetch_calendario_provas(session: aiohttp.ClientSession, cache: CacheManager = None) -> list[ProvaCalendario]:
        disciplinas = await Dados.fetch_disciplinas(session)
        mapa_nomes  = {d.codigo: d.nome for d in disciplinas}

        soup = await Dados._fetch_page(session, Dados.URL_CALENDARIO_PROVAS)
        accordion = soup.select_one('#accordion-provas')
        if not accordion:
            raise SessionExpiredException("Accordion de provas não encontrado")

        provas = []
        for panel in accordion.select('div.panel.panel-default'):
            titulo_link = panel.select_one('.panel-title a')
            if not titulo_link:
                continue
            titulo = titulo_link.text.strip()
            if '(P1)' in titulo:
                tipo_prova = 'P1'
            elif '(P2)' in titulo:
                tipo_prova = 'P2'
            elif '(P3)' in titulo:
                tipo_prova = 'P3'
            else:
                continue

            tabela = panel.select_one('div.panel-body table.table')
            if not tabela:
                continue

            for row in tabela.select('tbody > tr'):
                cols = row.find_all('td')
                if len(cols) < 5:
                    continue
                disciplina = cols[0].text.strip()
                data_prova = cols[1].text.strip().split(' ')[0]
                hora       = cols[2].text.strip()
                sala       = cols[3].text.strip()
                coordenador = cols[4].text.strip()
                if disciplina and data_prova:
                    provas.append(ProvaCalendario(
                        disciplina=disciplina,
                        nome_disciplina=mapa_nomes.get(disciplina, disciplina),
                        data_prova=data_prova,
                        hora=hora,
                        sala=sala,
                        coordenador=coordenador,
                        tipo_prova=tipo_prova,
                    ))

        logger.debug("Calendário de provas carregado: %d itens", len(provas))
        return provas

    # ===================== BOLETOS =====================

    @staticmethod
    async def fetch_boletos(session: aiohttp.ClientSession, cache: CacheManager = None) -> list[Boleto]:
        soup = await Dados._fetch_page(session, Dados.URL_BOLETOS)
        form = soup.select_one('#form-gerar-boletos')
        if not form:
            raise SessionExpiredException("Formulário de boletos não encontrado — sessão inválida")
        tabela = form.select_one('table.table')
        if not tabela:
            raise SessionExpiredException("Tabela de boletos não encontrada")

        boletos = []
        for row in tabela.select('tbody > tr'):
            # Usa seletor de atributo para classes com nome composto
            venc_elem  = row.select_one('td[class*="Vencimento"]')
            stat_elem  = row.select_one('td[class*="Status"]')
            data_elem  = row.select_one('td[class*="Data"]')
            tid_input  = row.find('input', attrs={'name': 'titulos'})

            vencimento      = venc_elem.text.strip()  if venc_elem  else ''
            status          = stat_elem.text.strip()  if stat_elem  else ''
            data_pagamento  = data_elem.text.strip()  if data_elem  else ''
            titulo_id       = tid_input.get('value', '').strip() if tid_input else ''

            if vencimento and status:
                boletos.append(Boleto(vencimento, status, data_pagamento, titulo_id))

        logger.debug("Boletos carregados: %d", len(boletos))
        return boletos

    @staticmethod
    async def baixar_boleto(
        session: aiohttp.ClientSession,
        titulo_id: str,
        vencimento: str,
    ) -> Path | None:
        """Faz o POST para gerar o boleto e salva o PDF em ~/Downloads/BoletosFEI/."""
        try:
            partes = vencimento.split('/')
            nome_arquivo = (
                f"{partes[2]}_{partes[1]}.pdf" if len(partes) == 3 else f"{titulo_id}.pdf"
            )

            # 1. Buscar CSRF token na página de boletos
            soup = await Dados._fetch_page(session, Dados.URL_BOLETOS)
            csrf_input = soup.select_one(
                '#form-gerar-boletos input[name=__RequestVerificationToken]'
            )
            if not csrf_input:
                logger.error("CSRF token não encontrado na página de boletos")
                return None
            csrf_token = csrf_input.get('value', '')

            # 2. POST para gerar o boleto (aiohttp segue redirects automaticamente)
            headers = {
                'User-Agent': Dados.USER_AGENT,
                'Referer':    Dados.URL_BOLETOS,
                'Accept':     'application/pdf,text/html,*/*',
            }
            data = {
                '__RequestVerificationToken': csrf_token,
                'respFinanceiro': '0',
                'titulos': titulo_id,
            }
            async with session.post(
                Dados.URL_GERAR_BOLETO,
                data=data,
                headers=headers,
                allow_redirects=True,
            ) as resp:
                logger.debug(
                    "baixar_boleto: HTTP %d, Content-Type=%s", resp.status, resp.content_type
                )
                if resp.status != 200:
                    logger.error("Erro ao gerar boleto: HTTP %d", resp.status)
                    return None
                pdf_bytes = await resp.read()

            # 3. Salvar em ~/Downloads/BoletosFEI/
            destino = Path.home() / 'Downloads' / 'BoletosFEI'
            destino.mkdir(parents=True, exist_ok=True)
            arquivo = destino / nome_arquivo
            arquivo.write_bytes(pdf_bytes)
            logger.debug("Boleto salvo: %s (%d bytes)", arquivo, arquivo.stat().st_size)
            return arquivo

        except Exception:
            logger.exception("Erro ao baixar boleto %s", titulo_id)
            return None

    # ===================== ORDENAÇÃO DE NOTAS PARA HOME =====================

    @staticmethod
    def ordenar_notas_para_home(
        notas: list[Nota],
        provas: list[ProvaCalendario],
    ) -> list[Nota]:
        """
        Porta fiel de Dados.ordenarNotasParaHome do Android.
        Ordena as notas priorizando: tipo de prova mais recente lançado por disciplina,
        seguido de notas avulsas ordenadas por data de referência.
        """
        TIPOS_CONHECIDOS = {'P1', 'P2', 'P3'}

        def normalizar(cod: str) -> str:
            return cod.strip().upper()

        def data_para_int(data: str) -> int:
            partes = data.split('/')
            if len(partes) < 2:
                return -999999
            try:
                return int(partes[1]) * 100 + int(partes[0])
            except ValueError:
                return -999999

        def tipo_peso(tipo: str) -> int:
            return {'P3': 3, 'P2': 2, 'P1': 1}.get(tipo, 0)

        hoje = datetime.date.today()
        hoje_int = hoje.month * 100 + hoje.day

        disciplinas_com_p3 = {
            normalizar(n.codigo_disciplina)
            for n in notas
            if n.tipo_prova == 'P3' and n.valor
        }

        # Provas que já ocorreram (ou hoje), excluindo P3 sem nota lançada
        provas_validas = [
            p for p in provas
            if (
                (di := data_para_int(p.data_prova)) != -999999
                and di <= hoje_int
                and not (p.tipo_prova == 'P3' and normalizar(p.disciplina) not in disciplinas_com_p3)
            )
        ]

        # Maior data por tipo por disciplina
        provas_por_disc: dict[str, list] = defaultdict(list)
        for p in provas_validas:
            provas_por_disc[normalizar(p.disciplina)].append(p)

        tipo_principal: dict[str, tuple[str, int]] = {}
        for cod, lista in provas_por_disc.items():
            melhor_por_tipo: dict[str, int] = {}
            for p in lista:
                d = data_para_int(p.data_prova)
                melhor_por_tipo[p.tipo_prova] = max(melhor_por_tipo.get(p.tipo_prova, -999999), d)
            tipo_esc = max(melhor_por_tipo, key=tipo_peso)
            tipo_principal[cod] = (tipo_esc, melhor_por_tipo[tipo_esc])

        calendario_exato: dict[str, int] = {
            f"{normalizar(p.disciplina)}|{p.tipo_prova}": data_para_int(p.data_prova)
            for p in provas_validas
        }

        notas_lancadas_set = {
            (normalizar(n.codigo_disciplina), n.tipo_prova)
            for n in notas if n.valor
        }

        grupos: dict[str, list[Nota]] = defaultdict(list)
        for n in notas:
            grupos[normalizar(n.codigo_disciplina)].append(n)

        def chave_ordenacao(cod: str) -> str:
            tp = tipo_principal.get(cod)
            if tp is None:
                return ''
            tipo, data = tp
            lancada = (cod, tipo) in notas_lancadas_set
            return f"{tipo_peso(tipo)}|{int(lancada)}|{str(data).zfill(5)}|{cod}"

        disciplinas_com_calendario = sorted(
            [cod for cod in grupos if cod in tipo_principal],
            key=chave_ordenacao,
            reverse=True,
        )

        resultado: list[Nota] = []
        avulsas:   list[Nota] = []

        for cod in disciplinas_com_calendario:
            notas_disc = grupos[cod]
            tipo, _    = tipo_principal[cod]

            # 1. Nota do tipo principal, se lançada
            resultado.extend(sorted(
                [n for n in notas_disc if n.tipo_prova == tipo and n.valor],
                key=lambda n: n.nome_disciplina,
            ))
            # 2. Notas de tipo desconhecido (ex.: Sub, Rec…)
            resultado.extend(sorted(
                [n for n in notas_disc if n.tipo_prova not in TIPOS_CONHECIDOS and n.valor],
                key=lambda n: n.nome_disciplina,
            ))
            # 3. Outros tipos conhecidos → ficam como avulsas
            avulsas.extend(
                n for n in notas_disc
                if n.tipo_prova in TIPOS_CONHECIDOS and n.tipo_prova != tipo and n.valor
            )

        # Disciplinas sem nenhuma prova no calendário → todas viram avulsas
        set_com_calendario = set(disciplinas_com_calendario)
        for cod, lista in grupos.items():
            if cod not in set_com_calendario:
                avulsas.extend(n for n in lista if n.valor)

        ancora_por_disc: dict[str, int] = {
            cod: max(data_para_int(p.data_prova) for p in lista)
            for cod, lista in provas_por_disc.items()
        }

        def data_referencia(nota: Nota) -> int:
            cod = normalizar(nota.codigo_disciplina)
            if nota.tipo_prova in TIPOS_CONHECIDOS:
                return calendario_exato.get(f"{cod}|{nota.tipo_prova}", -999999)
            return ancora_por_disc.get(cod, -999999)

        avulsas.sort(key=lambda n: (
            -data_referencia(n),
            -tipo_peso(n.tipo_prova),
            n.nome_disciplina,
        ))

        resultado.extend(avulsas)
        return resultado