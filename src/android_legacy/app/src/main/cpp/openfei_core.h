#pragma once

#ifdef __cplusplus
extern "C" {
#endif

void openfei_free_string(char* ptr);
void openfei_clear_session(void);

char* openfei_is_online(void);

char* openfei_login(const char* user, const char* pass);
char* openfei_moodle_login(const char* user, const char* pass);
char* openfei_moodle_token(const char* user, const char* pass);

char* openfei_fetch_disciplinas(void);
char* openfei_fetch_notas(void);
char* openfei_fetch_medias(void);
char* openfei_fetch_aulas(void);
char* openfei_fetch_perfil(void);
char* openfei_fetch_provas_fei(void);
char* openfei_fetch_boletos(void);
char* openfei_fetch_carousel(void);

char* openfei_download_boleto(
        const char* titulo_id,
        const char* vencimento,
        const char* out_dir
);

char* openfei_fetch_eventos_moodle(const char* user, const char* pass);
char* openfei_fetch_eventos_moodle_with_token(const char* token);

#ifdef __cplusplus
}
#endif