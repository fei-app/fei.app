#!/usr/bin/env bash
set -e

APPDIR="$(pwd)/AppDir"

echo "Limpando estruturas antigas..."
rm -rf "$APPDIR"

# 1. Detecta caminhos do Python atual
PYTHON_BIN=$(which python3)
PYTHON_VERSION=$(python3 -c "import sys; print(f'{sys.version_info.major}.{sys.version_info.minor}')")
STDLIB_SRC=$(python3 -c "import os, encodings; print(os.path.dirname(os.path.dirname(encodings.__file__)))")
DYNLOAD_SRC=$(python3 -c "import os, _socket; print(os.path.dirname(_socket.__file__))")

echo "=== Informações do Sistema ==="
echo "Python Detectado: $PYTHON_BIN (Versão $PYTHON_VERSION)"
echo "Origem da Stdlib: $STDLIB_SRC"
echo "Origem do Dynload: $DYNLOAD_SRC"
echo "=============================="

# 2. Cria a árvore de diretórios interna do AppDir
mkdir -p "$APPDIR/usr/bin"
mkdir -p "$APPDIR/usr/lib/python${PYTHON_VERSION}"
mkdir -p "$APPDIR/usr/lib/python${PYTHON_VERSION}/lib-dynload"
mkdir -p "$APPDIR/usr/lib64"

# SOLUÇÃO DO LIB64: Cria um link simbólico interno. 
# Se o Python do Fedora procurar em usr/lib64/python3.14, ele será redirecionado para usr/lib/python3.14
ln -sf "../lib/python${PYTHON_VERSION}" "$APPDIR/usr/lib64/python${PYTHON_VERSION}"

mkdir -p "$APPDIR/usr/share/openfei/ui"
mkdir -p "$APPDIR/usr/share/applications"
mkdir -p "$APPDIR/usr/share/icons/hicolor/256x256/apps"

# 3. Copia o executável do Python
cp "$PYTHON_BIN" "$APPDIR/usr/bin/python3"

# 4. Copia a Standard Library completa
echo "Copiando biblioteca padrão do Python..."
cp -r "$STDLIB_SRC"/* "$APPDIR/usr/lib/python${PYTHON_VERSION}/"

# 5. Copia os módulos binários nativos (.so)
echo "Copiando extensões dinâmicas nativas..."
cp -r "$DYNLOAD_SRC"/* "$APPDIR/usr/lib/python${PYTHON_VERSION}/lib-dynload/"

# 6. Instala dependências do PIP
echo "Instalando dependências via PIP..."
pip3 install \
    --target="$APPDIR/usr/lib/python${PYTHON_VERSION}/site-packages" \
    --ignore-installed \
    aiohttp beautifulsoup4

# 7. Copia o código-fonte da sua aplicação
echo "Empacotando código do OpenFEI..."
cp main.py dados.py models.py login_logic.py \
   session_manager.py cache_manager.py config_manager.py \
   style.css "$APPDIR/usr/share/openfei/"
cp ui/*.py "$APPDIR/usr/share/openfei/ui/"
touch "$APPDIR/usr/share/openfei/ui/__init__.py"

# 8. Copia arquivos de introspecção do GTK4 (Typelibs)
echo "Mapeando dependências do GTK4..."
mkdir -p "$APPDIR/usr/lib64/girepository-1.0"
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

# 9. Configura Ícone e entrada de Desktop (RIGOROSO PARA WAYLAND/GNOME)
APP_ID="com.marinov.openfei"

echo "Configurando ícones e atalhos para o GNOME (Wayland/X11)..."

# Copia o ícone com o nome EXATO do App ID para a pasta XDG correta
cp openfei.png "$APPDIR/usr/share/icons/hicolor/256x256/apps/${APP_ID}.png"

# Copia o ícone para a raiz do AppDir (exigência do AppImage)
cp openfei.png "$APPDIR/${APP_ID}.png"
cp openfei.png "$APPDIR/.DirIcon"

# Cria o .desktop com o nome EXATO do App ID
cat > "$APPDIR/usr/share/applications/${APP_ID}.desktop" <<EOF
[Desktop Entry]
Name=OpenFEI
Comment=Meu cliente alternativo para acessar o portal do aluno do Centro Universitário FEI , com funcionalidades extras ao aplicativo oficial.
Exec=openfei
Icon=${APP_ID}
Type=Application
Categories=Education;
StartupWMClass=${APP_ID}
EOF

# Cria um link simbólico na raiz para o appimagetool encontrar o atalho
ln -sf "usr/share/applications/${APP_ID}.desktop" "$APPDIR/${APP_ID}.desktop"

# 10. Cria o script AppRun (Usando EOF normal para embutir a versão do Python em tempo de build)
cat > "$APPDIR/AppRun" << EOF
#!/usr/bin/env bash
export APPDIR="\$(dirname "\$(readlink -f "\$0")")"

# Força o interpretador a usar estritamente a nossa stdlib copiada
export PYTHONHOME="\$APPDIR/usr"

# Mapeia onde o Python vai buscar os módulos internos da app e libs do pip
export PYTHONPATH="\$APPDIR/usr/share/openfei:\$APPDIR/usr/lib/python${PYTHON_VERSION}/site-packages"

# Variáveis do ecossistema GTK / GNOME
export GI_TYPELIB_PATH="\$APPDIR/usr/lib64/girepository-1.0"
export LD_LIBRARY_PATH="\$APPDIR/usr/lib64:\$APPDIR/usr/lib:\$LD_LIBRARY_PATH"
export GSETTINGS_SCHEMA_DIR="\$APPDIR/usr/share/glib-2.0/schemas"
export XDG_DATA_DIRS="\$APPDIR/usr/share:\${XDG_DATA_DIRS:-/usr/local/share:/usr/share}"

# Garante que caminhos relativos funcionem
cd "\$APPDIR/usr/share/openfei"

# Executa o Python interno
exec "\$APPDIR/usr/bin/python3" "\$APPDIR/usr/share/openfei/main.py" "\$@"
EOF

chmod +x "$APPDIR/AppRun"
echo "=== AppDir estruturado com sucesso! ==="