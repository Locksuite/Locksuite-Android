import subprocess

adb = r"C:\Programas\01_Moviles_y_Flasheo\adb\adb.exe"

sh_script = """#!/system/bin/sh
mkdir -p /sdcard/calibrations_dump
for p in nvram nvdata nvcfg protect1 protect2 proinfo seccfg sec1 boot_a md1img_a vbmeta_a vbmeta_system_a vbmeta_vendor_a dtbo_a spmfw_a sspm_a logo; do
    echo "Dumping $p..."
    dd if=/dev/block/by-name/$p of=/sdcard/calibrations_dump/$p.img bs=1048576
done
ls -la /sdcard/calibrations_dump
"""

subprocess.run([adb, "shell", "su -c 'cat > /data/local/tmp/dump.sh'"], input=sh_script, text=True)
subprocess.run([adb, "shell", "su -c 'sh /data/local/tmp/dump.sh'"])
