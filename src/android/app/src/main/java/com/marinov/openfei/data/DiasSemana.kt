package com.marinov.openfei.data

import androidx.annotation.StringRes
import com.marinov.openfei.R
import java.util.Calendar

/**
 * ★ BUG 45: fonte única dos nomes de dia da semana.
 *
 * O Rust (fei/aulas.rs) devolve SEMPRE os nomes canônicos em português
 * ("Segunda".."Sábado"). A UI compara com esses valores canônicos e apenas
 * EXIBE o texto vindo do strings.xml — assim lógica e i18n ficam desacopladas.
 */
object DiasSemana {

    /** Ordem canônica usada pelo portal FEI (domingo não tem aulas). */
    val CANONICOS: List<String> =
        listOf("Segunda", "Terça", "Quarta", "Quinta", "Sexta", "Sábado")

    fun hoje(): String? = when (Calendar.getInstance().get(Calendar.DAY_OF_WEEK)) {
        Calendar.MONDAY -> "Segunda"
        Calendar.TUESDAY -> "Terça"
        Calendar.WEDNESDAY -> "Quarta"
        Calendar.THURSDAY -> "Quinta"
        Calendar.FRIDAY -> "Sexta"
        Calendar.SATURDAY -> "Sábado"
        else -> null
    }

    /** Tolerante a abreviações ("seg", "Segunda-feira") e a acentuação. */
    fun normalizar(raw: String): String {
        val texto = raw.trim()
        if (texto.isBlank()) return texto
        val lower = texto.lowercase()
        return CANONICOS.firstOrNull { canonico ->
            val c = canonico.lowercase()
            c == lower || lower.startsWith(c) || c.startsWith(lower.take(3))
        } ?: texto
    }

    @StringRes
    fun stringRes(dia: String): Int = when (normalizar(dia)) {
        "Segunda" -> R.string.horarios_segunda
        "Terça" -> R.string.horarios_terca
        "Quarta" -> R.string.horarios_quarta
        "Quinta" -> R.string.horarios_quinta
        "Sexta" -> R.string.horarios_sexta
        else -> R.string.horarios_sabado
    }
}