#!/usr/bin/env bash
set -e

APPDIR="$(pwd)/AppDir"

echo "Limpando estruturas antigas..."
rm -rf "$APPDIR"

# 1. Cria a árvore de diretórios interna otimizada (sem duplicar o Python do sistema)
mkdir -p "$APPDIR/usr/share/openfei/ui"
mkdir -p "$APPDIR/usr/share/openfei/site-packages"
mkdir -p "$APPDIR/usr/share/applications"
mkdir -p "$APPDIR/usr/share/icons/hicolor/256x256/apps"
mkdir -p "$APPDIR/usr/lib64/girepository-1.0"

# 2. Instala dependências do PIP em uma pasta agnóstica de versão
echo "Instalando dependências via PIP de forma isolada..."
pip3 install \
    --target="$APPDIR/usr/share/openfei/site-packages" \
    --ignore-installed \
    aiohttp beautifulsoup4

# 3. Copia o código-fonte da sua aplicação
echo "Empacotando código do OpenFEI..."
cp main.py dados.py models.py login_logic.py \
   session_manager.py cache_manager.py config_manager.py \
   style.css "$APPDIR/usr/share/openfei/"
cp ui/*.py "$APPDIR/usr/share/openfei/ui/"
touch "$APPDIR/usr/share/openfei/ui/__init__.py"

# 4. Copia arquivos de introspecção do GTK4 (Typelibs) para garantir compatibilidade UI
echo "Mapeando dependências de interface do GTK4..."
for base_dir in "/usr/lib64/girepository-1.0" "/usr/lib/girepository-1.0"; do
    if [ -d "$base_dir" ]; then
        for lib in Gtk-4.0 Gdk-4.0 GLib-2.0 GObject-2.0 Gio-2.0 \
                   GdkPixbuf-2.0 Pango-1.0 cairo-1.0 \
                   HarfBuzz-0.0 PangoCairo-1.0 Graphene-1.0; do
            if [ -f "$base_dir/${lib}.typelib" ]; then
                cp "$base_dir/${lib}.typelib" "$APPDIR/usr/lib64/girepository-1.0/"
            fi
        done
    fi
done

# 5. Configura Ícone e entrada de Desktop (Padrão GNOME Shell)
APP_ID="com.marinov.openfei"
echo "Configurando ícones e atalhos para o GNOME..."

cp openfei.png "$APPDIR/usr/share/icons/hicolor/256x256/apps/${APP_ID}.png"
cp openfei.png "$APPDIR/${APP_ID}.png"
cp openfei.png "$APPDIR/.DirIcon"

cat > "$APPDIR/usr/share/applications/${APP_ID}.desktop" <<EOF
[Desktop Entry]
Name=OpenFEI
Comment=Meu cliente alternativo para acessar o portal do aluno do Centro Universitário FEI
Exec=openfei
Icon=${APP_ID}
Type=Application
Categories=Education;
StartupWMClass=${APP_ID}
EOF

ln -sf "usr/share/applications/${APP_ID}.desktop" "$APPDIR/${APP_ID}.desktop"

# 6. Cria o script AppRun inteligente (repare nas aspas em 'EOF' para evitar escapes complexos)
cat > "$APPDIR/AppRun" << 'EOF'
#!/usr/bin/env bash
# Descobre onde o AppImage foi montado em tempo de execução
export APPDIR="$(dirname "$(readlink -f "$0")")"

# Dizemos ao Python para incluir os arquivos do seu app e as bibliotecas do PIP isoladas
export PYTHONPATH="$APPDIR/usr/share/openfei:$APPDIR/usr/share/openfei/site-packages:$PYTHONPATH"

# Variáveis para mapeamento correto do ecossistema GTK / GNOME
export GI_TYPELIB_PATH="$APPDIR/usr/lib64/girepository-1.0:$GI_TYPELIB_PATH"
export LD_LIBRARY_PATH="$APPDIR/usr/lib64:$APPDIR/usr/lib:$LD_LIBRARY_PATH"
export XDG_DATA_DIRS="$APPDIR/usr/share:${XDG_DATA_DIRS:-/usr/local/share:/usr/share}"

# Garante que caminhos relativos de arquivos dentro do app funcionem
cd "$APPDIR/usr/share/openfei"

# EXECUÇÃO SEGURA: Chama o python3 nativo do sistema do usuário.
# Ele usará a stdlib e as extensões C nativas atualizadas do Fedora, acabando com os erros de Mismatch!
exec python3 "$APPDIR/usr/share/openfei/main.py" "$@"
EOF

chmod +x "$APPDIR/AppRun"
echo "=== AppDir estruturado com sucesso e protegido contra atualizações! ==="

ARCH=x86_64 ./appimagetool --no-appstream AppDir OpenFEI-2.4.1-x86_64.AppImage
echo "=== AppImage gerada! ==="