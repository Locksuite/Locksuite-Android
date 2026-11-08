import subprocess
import json

adb_path = r"C:\Programas\01_Moviles_y_Flasheo\adb\adb.exe"
cmd = [adb_path, "shell", "su -c 'for p in /sys/class/block/mmcblk0p*; do echo $(cat $p/uevent | grep PARTNAME) $(cat $p/start) $(cat $p/size); done'"]
res = subprocess.run(cmd, capture_output=True, text=True)

partitions = []
for line in res.stdout.splitlines():
    if "PARTNAME=" in line:
        parts = line.strip().split()
        partname = parts[0].split("=")[1]
        start_sector = int(parts[1])
        size_sectors = int(parts[2])
        start_hex = hex(start_sector * 512)
        size_hex = hex(size_sectors * 512)
        size_mb = (size_sectors * 512) / (1024 * 1024)
        partitions.append({
            "name": partname,
            "start_sector": start_sector,
            "size_sectors": size_sectors,
            "start_hex": start_hex,
            "size_hex": size_hex,
            "size_mb": round(size_mb, 2)
        })

print(f"{'PARTITION':<18} {'START ADDRESS':<16} {'LENGTH':<16} {'SIZE'}")
print("-" * 65)
for p in partitions:
    print(f"{p['name']:<18} {p['start_hex']:<16} {p['size_hex']:<16} {p['size_mb']} MB")

with open(r"D:\qin_f21_pro_rom\backup_tercer_f21_nuevo\partition_table.json", "w") as f:
    json.dump(partitions, f, indent=2)
