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

// Todos os parâmetros têm default de propósito: assim o Kotlin gera um construtor
// sem argumentos e o Gson (usado no CacheHelper) o utiliza, preenchendo descricao/anexos
// com os defaults quando o cache antigo não tem esses campos (evita null em String).
data class ProvaCalendario(
    val disciplina: String = "",
    val nomeDisciplina: String = "",
    val dataProva: String = "",
    val hora: String = "",
    val sala: String? = null,
    val coordenador: String = "",
    val tipoProva: String = "",
    val descricao: String = "",
    val anexos: List<AnexoMoodle> = emptyList()
)

data class AnexoMoodle(
    val nome: String = "",
    val url: String = "",
    val tamanho: Long = 0L,
    val mimeType: String = "",
    val modificado: Long = 0L
)

data class Boleto(
    val vencimento: String,
    val status: String,
    val dataPagamento: String,
    val tituloId: String
)

