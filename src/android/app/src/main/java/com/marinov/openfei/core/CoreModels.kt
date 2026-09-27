package com.marinov.openfei.core

data class CoreError(
    val message: String,
    val code: String
)

sealed class CoreResult<out T> {
    data class Success<T>(val data: T) : CoreResult<T>()
    data class Error(val message: String, val code: String) : CoreResult<Nothing>()
}

data class CoreCookie(
    val origin: String = "",
    val cookieLine: String = ""
)

data class CoreLoginData(
    val success: Boolean = false,
    val errorMessage: String = "",
    val isNetworkError: Boolean = false,
    val cookies: List<CoreCookie> = emptyList()
)

data class CoreMoodleTokenData(
    val success: Boolean = false,
    val errorMessage: String = "",
    val isNetworkError: Boolean = false,
    val token: String? = null,
    val cookies: List<CoreCookie> = emptyList()
)

data class CoreDisciplina(
    val codigo: String = "",
    val nome: String = ""
)

data class CoreNota(
    val codigoDisciplina: String = "",
    val nomeDisciplina: String = "",
    val tipoProva: String = "",
    val valor: String = ""
)

data class CorePerfil(
    val nome: String = "",
    val matricula: String = "",
    val curso: String = "",
    val email: String = ""
)

data class CoreAula(
    val diaSemana: String = "",
    val codigoDisciplina: String = "",
    val nomeDisciplina: String = "",
    val sala: String = "",
    val horaInicio: String = "",
    val horaFim: String = ""
)

data class CoreProvaCalendario(
    val disciplina: String = "",
    val nomeDisciplina: String = "",
    val dataProva: String = "",
    val hora: String = "",
    val sala: String? = null,
    val coordenador: String = "",
    val tipoProva: String = ""
)

data class CoreBoleto(
    val vencimento: String = "",
    val status: String = "",
    val dataPagamento: String = "",
    val tituloId: String = ""
)

data class CoreCarouselItem(
    val imageUrl: String? = null,
    val linkUrl: String? = null
)

data class CoreDownloadBoletoResult(
    val path: String = "",
    val size: Int = 0
)