package com.marinov.openfei.data

class SessionExpiredException(message: String) : Exception(message)

data class Disciplina(val codigo: String, val nome: String)

data class Nota(
    val codigoDisciplina: String,
    val nomeDisciplina: String,
    val tipoProva: String,
    val valor: String
)

data class Perfil(
    val nome: String,
    val matricula: String,
    val curso: String,
    val email: String
)

data class Aula(
    val diaSemana: String,
    val codigoDisciplina: String,
    val nomeDisciplina: String,
    val sala: String,
    val horaInicio: String,
    val horaFim: String
)

data class ProvaCalendario(
    val disciplina: String,
    val nomeDisciplina: String,
    val dataProva: String,
    val hora: String,
    val sala: String?,
    val coordenador: String,
    val tipoProva: String
)

data class Boleto(
    val vencimento: String,
    val status: String,
    val dataPagamento: String,
    val tituloId: String
)

