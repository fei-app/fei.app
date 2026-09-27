import sys
import os
import pystray
from pystray import MenuItem as item
from PIL import Image

def on_open_clicked(icon, item):
    print("show", flush=True)

def on_exit_clicked(icon, item):
    print("quit", flush=True)
    icon.stop()

def main():
    base_dir = os.path.dirname(os.path.abspath(__file__))
    icon_path = os.path.join(base_dir, "openfei.png")
    
    print(f"[TRAY] Buscando imagem em: {icon_path}", flush=True)
    
    try:
        image = Image.open(icon_path)
        print("[TRAY] Imagem carregada com sucesso.", flush=True)
    except Exception as e:
        print(f"[TRAY] Falha ao carregar imagem: {e}. Usando fallback.", flush=True)
        image = Image.new('RGB', (64, 64), color=(0, 122, 204))
        
    # O parâmetro default=True define esta como a ação principal do menu.
    menu = pystray.Menu(
        item('Abrir OpenFEI', on_open_clicked, default=True),
        item('Sair', on_exit_clicked)
    )
    
    print("[TRAY] Iniciando loop do ícone...", flush=True)
    
    # CORREÇÃO: Removido o argumento extra que causava o TypeError.
    # A assinatura correta é apenas: Icon(name, icon, title, menu)
    icon = pystray.Icon("openfei_tray", image, "OpenFEI", menu)
    icon.run()
    
    print("[TRAY] Loop encerrado.", flush=True)

if __name__ == '__main__':
    main()