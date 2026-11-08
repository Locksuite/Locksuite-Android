import subprocess
import os

adb_path = r"C:\Programas\01_Moviles_y_Flasheo\adb\adb.exe"
dest_dir = r"D:\qin_f21_pro_rom\backup_tercer_f21_nuevo\calibraciones"
os.makedirs(dest_dir, exist_ok=True)

partitions = [
    "nvram", "nvdata", "nvcfg", "protect1", "protect2",
    "proinfo", "seccfg", "sec1", "boot_a", "md1img_a",
    "vbmeta_a", "vbmeta_system_a", "vbmeta_vendor_a",
    "dtbo_a", "spmfw_a", "sspm_a", "logo"
]

print("Iniciando respaldo directo byte a byte de calibraciones e IMEI...")
for p in partitions:
    out_file = os.path.join(dest_dir, f"{p}.img")
    print(f"Respaldando {p} -> {out_file}...", end=" ", flush=True)
    cmd = [adb_path, "exec-out", f"su -c 'cat /dev/block/by-name/{p}'"]
    with open(out_file, "wb") as f:
        res = subprocess.run(cmd, stdout=f)
    size_mb = os.path.getsize(out_file) / (1024 * 1024)
    print(f"OK ({size_mb:.2f} MB)")

print("\n¡Respaldo de calibraciones completado con éxito!")
