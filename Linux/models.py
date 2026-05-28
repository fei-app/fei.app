from dataclasses import dataclass


@dataclass
class Disciplina:
    codigo: str
    nome: str


@dataclass
class Nota:
    codigo_disciplina: str
    nome_disciplina: str
    tipo_prova: str
    valor: str


@dataclass
class Perfil:
    nome: str
    matricula: str
    curso: str
    email: str


@dataclass
class Aula:
    dia_semana: str
    codigo_disciplina: str
    nome_disciplina: str
    sala: str
    hora_inicio: str
    hora_fim: str


@dataclass
class ProvaCalendario:
    disciplina: str
    nome_disciplina: str
    data_prova: str
    hora: str
    sala: str
    coordenador: str
    tipo_prova: str


@dataclass
class Boleto:
    vencimento: str
    status: str
    data_pagamento: str
    titulo_id: str