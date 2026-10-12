# Prints JVM arguments for Minecraft that hand Java as much memory as this
# machine can spare. Paste the output into your launcher's "JVM arguments"
# box (Minecraft Launcher: Installations > Edit > More options;
# Prism/MultiMC: Settings > Java).
# Usage:  powershell -ExecutionPolicy Bypass -File tools\jvm-args.ps1 [-HeapGB 12]
param([int]$HeapGB = 0)

if ($HeapGB -le 0) {
    $total = [math]::Floor((Get-CimInstance Win32_ComputerSystem).TotalPhysicalMemory / 1GB)
    # Keep at least 4 GB (or a quarter of RAM) for Windows, the browser, the GPU
    # driver, plus 1 GB for Minecraft's own off-heap buffers.
    $reserve = [math]::Max(4, [math]::Floor($total / 4))
    $HeapGB = [math]::Min(31, [math]::Max(2, $total - $reserve - 1))
    Write-Host "# Detected $total GB RAM -> giving Minecraft $HeapGB GB" -ForegroundColor DarkGray
}

"-Xms${HeapGB}G -Xmx${HeapGB}G -XX:+UseZGC -XX:+ZGenerational -XX:+AlwaysPreTouch -XX:+DisableExplicitGC -XX:+PerfDisableSharedMem"
