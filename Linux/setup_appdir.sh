#!/usr/bin/env bash
set -e
APPDIR="$(pwd)/AppDir"
echo "Limpando estruturas antigas..."
rm -rf "$APPDIR"

mkdir -p "$APPDIR/usr/share/openfei/ui"
mkdir -p "$APPDIR/usr/share/openfei/site-packages"
mkdir -p "$APPDIR/usr/share/applications"
mkdir -p "$APPDIR/usr/share/icons/hicolor/256x256/apps"
mkdir -p "$APPDIR/usr/lib64/girepository-1.0"
mkdir -p "$APPDIR/usr/lib64"

echo "Instalando dependências via PIP de forma isolada..."
pip3 install \
    --target="$APPDIR/usr/share/openfei/site-packages" \
    --ignore-installed \
    aiohttp beautifulsoup4 pystray pillow

echo "Empacotando código do OpenFEI..."
# CORREÇÃO CRÍTICA: Adicionado 'openfei.png' aqui para que ele fique na mesma pasta do main.py
cp main.py dados.py models.py login_logic.py \
   session_manager.py cache_manager.py config_manager.py \
   tray_service.py \
   style.css openfei.png "$APPDIR/usr/share/openfei/"

cp ui/*.py "$APPDIR/usr/share/openfei/ui/"
touch "$APPDIR/usr/share/openfei/ui/__init__.py"

echo "Mapeando dependências de interface do GTK4..."
for base_dir in "/usr/lib64/girepository-1.0" "/usr/lib/girepository-1.0" "/usr/lib/x86_64-linux-gnu/girepository-1.0"; do
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

echo "Mapeando dependências para System Tray (AppIndicator/GTK3)..."
for base_dir in "/usr/lib64/girepository-1.0" "/usr/lib/girepository-1.0" "/usr/lib/x86_64-linux-gnu/girepository-1.0"; do
    if [ -d "$base_dir" ]; then
        for lib in AppIndicator3-0.1 AyatanaAppIndicator3-0.1 Gtk-3.0 Gdk-3.0; do
            if [ -f "$base_dir/${lib}.typelib" ]; then
                cp "$base_dir/${lib}.typelib" "$APPDIR/usr/lib64/girepository-1.0/"
            fi
        done
    fi
done

# CORREÇÃO: Copia as bibliotecas nativas (.so) do AppIndicator para dentro da AppImage
echo "Mapeando bibliotecas nativas (.so) para System Tray..."
for base_dir in "/usr/lib64" "/usr/lib" "/usr/lib/x86_64-linux-gnu"; do
    if [ -d "$base_dir" ]; then
        for lib in "libappindicator3.so*" "libayatana-appindicator3.so*" "libdbusmenu-glib.so*" "libdbusmenu-gtk3.so*" "libindicator3.so*" "libayatana-indicator3.so*"; do
            find "$base_dir" -maxdepth 2 -name "$lib" -exec cp -L {} "$APPDIR/usr/lib64/" \; 2>/dev/null || true
        done
    fi
done

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

cat > "$APPDIR/AppRun" << 'EOF'
#!/usr/bin/env bash
export APPDIR="$(dirname "$(readlink -f "$0")")"

export PYTHONPATH="$APPDIR/usr/share/openfei:$APPDIR/usr/share/openfei/site-packages:$PYTHONPATH"
export GI_TYPELIB_PATH="$APPDIR/usr/lib64/girepository-1.0:$GI_TYPELIB_PATH"
export LD_LIBRARY_PATH="$APPDIR/usr/lib64:$APPDIR/usr/lib:$LD_LIBRARY_PATH"
export XDG_DATA_DIRS="$APPDIR/usr/share:${XDG_DATA_DIRS:-/usr/local/share:/usr/share}"

cd "$APPDIR/usr/share/openfei"

exec python3 "$APPDIR/usr/share/openfei/main.py" "$@"
EOF
chmod +x "$APPDIR/AppRun"

echo "=== AppDir estruturado com sucesso! ==="
ARCH=x86_64 ./appimagetool --no-appstream AppDir OpenFEI-2.4.3-x86_64.AppImage
echo "=== AppImage gerada! ==="