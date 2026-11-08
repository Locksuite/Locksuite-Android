import os
import zipfile
import sys
import time

src_dir = r"D:\Roms\Qin_F21_Pro_32GB_Clone_Rom_Safe"
zip_path = r"D:\Roms\Qin_F21_Pro_32GB_Custom_Kosher_ROM_Safe.zip"

print(f"Empaquetando carpeta '{src_dir}' en '{zip_path}'...")
t0 = time.time()

# Usamos compresion ZIP_DEFLATED con nivel equilibrado
with zipfile.ZipFile(zip_path, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=1) as zf:
    for root, dirs, files in os.walk(src_dir):
        for file in files:
            full_path = os.path.join(root, file)
            rel_path = os.path.relpath(full_path, src_dir)
            size_mb = os.path.getsize(full_path) / (1024 * 1024)
            print(f"  Agregando {rel_path} ({size_mb:.1f} MB)...", flush=True)
            zf.write(full_path, arcname=rel_path)

dt = time.time() - t0
zip_size_mb = os.path.getsize(zip_path) / (1024 * 1024)
print(f"\n[OK] ZIP creado exitosamente en {dt:.1f}s.")
print(f"Tamaño final del archivo ZIP: {zip_size_mb:.2f} MB")
