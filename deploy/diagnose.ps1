# MiQroGate deployment diagnostic (Windows) - read-only, redacted, paste-ready.
#
# The bug-report form asks for environment facts that people otherwise write by
# hand and get wrong or leave out (deployment shape, component versions, health,
# recent logs). This prints them as one report. Run it from the tree that holds
# deploy\ - the live tree on a server, or your checkout on a dev box:
#
#   powershell -ExecutionPolicy Bypass -File deploy\diagnose.ps1
#   (On a server use deploy/diagnose.sh instead; this file mirrors its sections
#    one for one, and under pwsh on Linux/macOS it runs the non-Windows blocks.)
#
# Paste everything between the two DIAGNOSTIC markers into the issue's
# diagnostic field (.github/ISSUE_TEMPLATE/bug_report.yml points back here).
#
# What it deliberately does NOT do:
#   * write anything - no files, no temp dirs, no state changed anywhere;
#   * change any container - docker is only ever asked ps/inspect/logs/exec-read/
#     stats/system-df, and the regression harness pins that down;
#   * reach the network beyond loopback and the site origin already in deploy\.env.
#
# Every printed value passes through Mask(): API-key shapes, Bearer/Basic
# credentials, URL passwords, KEY/SECRET/TOKEN assignments, private-key headers.
# Masking is pattern-based, so scan the output once more before posting - the
# issue form makes you confirm exactly that.
#
# Environment knobs (matching deploy.sh where they overlap):
#   MIQROKEY_LIVE_DIR            deployed tree (default: /opt/miqrokey when it
#                                exists, else the tree this script lives in)
#   MIQROKEY_ENV_FILE            compose env file (default: <live>\deploy\.env)
#   MIQROKEY_DIAGNOSE_LOG_LINES  log tail per container (default 40)
#
# Exit codes: 0 = report printed (whatever it found), 1 = usage error.

param(
    [switch]$Help
)

if ($Help) {
    $header = foreach ($l in Get-Content -LiteralPath $PSCommandPath) {
        if ($l -match '^param\(') { break }
        $l -replace '^# ?', ''
    }
    $header | Select-Object -Skip 1
    exit 1
}

# A missing tool must not abort the report - this script's whole point is to keep
# going and say what it found. (Prefer call-level guards over a global Stop.)
$ErrorActionPreference = "SilentlyContinue"

# Git and docker print UTF-8; the console's default code page (GBK on Chinese
# Windows) would decode it into mojibake, and the report exists to be pasted
# into GitHub. This changes the encoding for this process only.
try {
    [Console]::OutputEncoding = [System.Text.Encoding]::UTF8
    $OutputEncoding = [System.Text.Encoding]::UTF8
} catch { }

$IsWin = ($PSVersionTable.PSEdition -eq "Desktop") -or ($IsWindows -eq $true)

# ---- redaction ---------------------------------------------------------------
# Same rule set as deploy/diagnose.sh, spelled for .NET regex. Over-masking is
# accepted - a reader can always re-run with a narrower eye, but a leaked key
# cannot be un-posted. MIQROKEY_* names that are NOT secrets (IMAGE_TAG,
# ORIGIN_ALLOWLIST, ...) must survive: masking that swallowed them would make
# the report useless.
function Mask([string]$Text) {
    if ($null -eq $Text) { return "" }
    $t = $Text
    $t = $t -replace 'sk-[A-Za-z0-9_-]{8,}', '<masked-api-key>'
    $t = $t -replace 'AIza[0-9A-Za-z_-]{20,}', '<masked-api-key>'
    $t = $t -replace 'AKIA[0-9A-Z]{16}', '<masked-aws-key>'
    $t = $t -replace '(ghp|gho|ghs|ghu)_[A-Za-z0-9]{10,}', '<masked-token>'
    $t = $t -replace 'xox[baprs]-[A-Za-z0-9-]{8,}', '<masked-token>'
    $t = $t -replace '([Bb]earer |[Bb]asic )[A-Za-z0-9._~+/-]{8,}', '$1<masked>'
    $t = $t -replace '(://[^/@:\s]+:)[^/@\s]+@', '$1<masked>@'
    # Query-string credentials (the classic one here is a WeCom/Feishu webhook
    # URL whose path carries ?key=...; a log line that echoes the URL leaks it).
    $t = $t -replace '([?&]([Kk]ey|[Tt]oken|[Ss]ecret|[Pp]assword|[Aa]ccess[_-]?[Tt]oken|[Aa]pi[_-]?[Kk]ey)=)[^&\s"]+', '$1<masked>'
    $t = $t -replace '"([A-Za-z0-9_]*([Aa]uthorization|[Aa]pi[_-]?[Kk]ey|[Ss]ecret|[Pp]assword|[Pp]asswd|[Tt]oken|[Pp]epper|[Cc]redential)[A-Za-z0-9_]*)":\s*"[^"]*"', '"$1":"<masked>"'
    $t = $t -replace '([A-Za-z0-9_]*([Ss]ecret|[Pp]assword|[Pp]asswd|[Tt]oken|[Pp]epper|[Cc]redential|[Aa]pi[_-]?[Kk]ey)[A-Za-z0-9_]*)\s*=\s*\S+', '$1=<masked>'
    $t = $t -replace '-----BEGIN [A-Z ]*PRIVATE KEY-----', '<masked-private-key>'
    return $t
}

function Section([string]$Name) {
    Write-Output ""
    Write-Output "**$Name**"
}
function Note([string]$Text) { Write-Output "($Text)" }

# Run a native command, mask whatever it said. Output is a single trimmed string
# so it can be capped or embedded like any other report line.
function RunMasked([string]$Command, [string[]]$Arguments) {
    # Windows PowerShell 5.1 drops native stderr merged with 2>&1 while
    # $ErrorActionPreference is SilentlyContinue - the merged records are treated
    # as errors and silenced. Measured on 5.1: `docker logs` for a container whose
    # output is on stderr (redpanda) came back empty here but not in Git Bash.
    # Continue keeps them; stringifying through ForEach-Object renders each as its
    # line, without the "+ CategoryInfo" decoration Out-String puts on ErrorRecords.
    $prev = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    try {
        $raw = & $Command @Arguments 2>&1 | ForEach-Object { "$_" } | Out-String
    } finally {
        $ErrorActionPreference = $prev
    }
    return (Mask $raw).TrimEnd()
}

# ---- locate the tree, the live dir, the env file -----------------------------
$ScriptDir = if ($PSScriptRoot) { $PSScriptRoot } else { (Get-Location).Path }
if (Test-Path (Join-Path $ScriptDir "compose.prod.yaml")) {
    $Tree = Split-Path -Parent $ScriptDir
} else {
    $ScriptDir = Join-Path $ScriptDir "deploy"
    $Tree = (Get-Location).Path
}

$Live = $env:MIQROKEY_LIVE_DIR
if (-not $Live -and (Test-Path "/opt/miqrokey/deploy/compose.prod.yaml")) { $Live = "/opt/miqrokey" }
if (-not $Live -and (Test-Path (Join-Path $Tree "deploy/compose.prod.yaml"))) { $Live = $Tree }
$EnvFile = $env:MIQROKEY_ENV_FILE
if (-not $EnvFile -and $Live) { $EnvFile = Join-Path $Live "deploy/.env" }
$LogLines = 40
if ($env:MIQROKEY_DIAGNOSE_LOG_LINES -match '^\d+$') { $LogLines = [int]$env:MIQROKEY_DIAGNOSE_LOG_LINES }

$HasDocker = [bool](Get-Command docker -ErrorAction SilentlyContinue)

Write-Output "--- MiQroGate DIAGNOSTIC ---"
Write-Output ("generated: " + (Get-Date).ToUniversalTime().ToString("yyyy-MM-ddTHH:mm:ssZ"))
Write-Output ("runner: " + $env:USERNAME + "@" + $env:COMPUTERNAME)
Write-Output ("script: " + (Join-Path $ScriptDir "diagnose.ps1"))

# ---- host --------------------------------------------------------------------
Section "Host"
if ($IsWin) {
    $os = Get-CimInstance Win32_OperatingSystem
    if ($os) {
        Write-Output ("os: " + $os.Caption + " " + $os.Version + " (build " + $os.BuildNumber + ")")
    } else {
        Write-Output ("os: " + [System.Environment]::OSVersion.VersionString)
    }
    Write-Output ("arch: " + $env:PROCESSOR_ARCHITECTURE)
    $cs = Get-CimInstance Win32_ComputerSystem
    if ($cs) { Write-Output ("memory: " + [math]::Round($cs.TotalPhysicalMemory / 1GB, 1) + " GB total") }
} else {
    Write-Output ("uname: " + (RunMasked "uname" @("-a")))
    if (Test-Path "/etc/os-release") {
        $pretty = (Get-Content "/etc/os-release" | Where-Object { $_ -match '^PRETTY_NAME=' }) -replace '^PRETTY_NAME=', '' -replace '"', ''
        if ($pretty) { Write-Output ("os-release: " + $pretty) }
    }
    Write-Output ("arch: " + (RunMasked "uname" @("-m")))
    if (Get-Command free -ErrorAction SilentlyContinue) {
        $memline = (& free -h 2>$null | Select-Object -Index 1)
        if ($memline) { Write-Output ("memory: " + $memline) }
    }
}
$treeDrive = (Get-Item $Tree).PSDrive
if ($treeDrive -and $null -ne $treeDrive.Used) {
    Write-Output ("disk: " + $treeDrive.Name + ": " + [math]::Round($treeDrive.Free / 1GB, 1) + " GB avail of " +
        [math]::Round(($treeDrive.Used + $treeDrive.Free) / 1GB, 1) + " GB")
} else {
    $dfout = & df -h $Tree 2>$null | Select-Object -Index 1
    if ($dfout) { Write-Output ("disk: " + $dfout) }
}

# ---- docker ------------------------------------------------------------------
Section "Docker"
if ($HasDocker) {
    Write-Output ("client: " + (RunMasked "docker" @("version", "--format", "{{.Client.Version}}")))
    $server = RunMasked "docker" @("version", "--format", "{{.Server.Version}}")
    if (-not $server) { $server = "unreachable - daemon down, or this user is not in the docker group (retry with sudo on a deployment host)" }
    Write-Output ("server: " + $server)
    Write-Output ("compose: " + (RunMasked "docker" @("compose", "version", "--short")))
    $root = RunMasked "docker" @("info", "-f", "{{.DockerRootDir}}")
    if ($root) { Write-Output ("docker root: " + $root) }
    Write-Output ""
    Write-Output (RunMasked "docker" @("system", "df"))
} else {
    Note "docker not on PATH - stack sections below will be empty; run this on the deployment host for the full picture"
}

# ---- checkout ----------------------------------------------------------------
Section "Checkout"
Write-Output ("tree: " + $Tree)
if (-not (Get-Command git -ErrorAction SilentlyContinue)) {
    Note "git not on PATH"
} elseif ((RunMasked "git" @("-C", $Tree, "rev-parse", "--is-inside-work-tree")) -eq "true") {
    Write-Output ("git: " + (RunMasked "git" @("-C", $Tree, "log", "-1", "--format=%h (%cs) %s")))
    Write-Output ("branch: " + (RunMasked "git" @("-C", $Tree, "rev-parse", "--abbrev-ref", "HEAD")))
} else {
    Note "not a git checkout (a synced live tree) - the deploy log below is the record of what is live"
}
if (Test-Path (Join-Path $Tree "CHANGELOG.md")) {
    $head = Get-Content (Join-Path $Tree "CHANGELOG.md") -Encoding UTF8 | Where-Object { $_ -match '^## ' } | Select-Object -First 1
    if ($head) { Write-Output ("changelog head: " + $head.Substring(3)) }
}

# ---- the stack ---------------------------------------------------------------
Section "Stack"
if ($Live) {
    Write-Output ("live dir: " + $Live)
    Write-Output ("compose file: " + (Join-Path $Live "deploy/compose.prod.yaml"))
} else {
    Note "no production tree found (no /opt/miqrokey, and this tree has no deploy/compose.prod.yaml) - static sections only"
}
if ($HasDocker) {
    Write-Output ""
    Write-Output "containers (compose project miqrokey / name miqrokey-*):"
    $found = @()
    $found += (& docker ps -a --format '{{.ID}}|{{.Names}}|{{.Status}}|{{.Image}}' --filter 'label=com.docker.compose.project=miqrokey' 2>$null)
    $found += (& docker ps -a --format '{{.ID}}|{{.Names}}|{{.Status}}|{{.Image}}' --filter 'name=miqrokey-' 2>$null)
    $found = $found | Sort-Object -Unique
    if ($found) {
        foreach ($line in $found) {
            $parts = $line.Split("|")
            $health = RunMasked "docker" @("inspect", "-f", "{{if .State.Health}}{{.State.Health.Status}}{{else}}-{{end}}", $parts[0])
            $digest = RunMasked "docker" @("inspect", "-f", "{{.Image}}", $parts[0])
            Write-Output ("  " + $parts[1] + "  " + $parts[2] + "  health=" + $health)
            Write-Output ("    image=" + $parts[3])
            Write-Output ("    id=" + $digest)
        }
    } else {
        Note "no miqrokey containers running on this host"
    }
    Write-Output ""
    Write-Output "docker stats (one snapshot):"
    $stats = (& docker stats --no-stream --format '  {{.Name}}  cpu={{.CPUPerc}}  mem={{.MemUsage}} ({{.MemPerc}})' 2>$null) |
        Where-Object { $_ -match 'miqrokey' }
    if ($stats) { $stats | ForEach-Object { Write-Output $_ } } else { Note "no running miqrokey containers to sample" }
}
if ($Live -and (Test-Path (Join-Path $Live "deploy.log"))) {
    Write-Output ""
    Write-Output "last deploy log lines (deploy.sh writes what is live here):"
    Get-Content (Join-Path $Live "deploy.log") -Tail 3 -Encoding UTF8 | ForEach-Object { Write-Output ("  " + $_) }
}

# ---- probes ------------------------------------------------------------------
Section "Probes"
function Probe([string]$Url) {
    $curl = Get-Command curl.exe -ErrorAction SilentlyContinue
    $sink = "NUL"
    if (-not $curl) { $curl = Get-Command curl -ErrorAction SilentlyContinue; $sink = "/dev/null" }
    if (-not $curl) { return "curl not available" }
    $code = & $curl.Source -sS -k -o $sink -w '%{http_code}' --max-time 8 $Url 2>$null
    if (-not $code -or $code -eq "000") { return "unreachable (curl code 000)" }
    return "HTTP $code"
}
if ($HasDocker) {
    Write-Output ("portal  http://127.0.0.1/healthz          " + (Probe "http://127.0.0.1/healthz"))
    if ($EnvFile -and (Test-Path $EnvFile)) {
        $origin = (Get-Content $EnvFile -Encoding UTF8 | Where-Object { $_ -match '^MIQROKEY_ORIGIN_ALLOWLIST=' } |
            Select-Object -First 1) -replace '^MIQROKEY_ORIGIN_ALLOWLIST=', ''
        if ($origin) { $origin = ($origin -split ',')[0].Trim() }
        if ($origin) { Write-Output ("site    " + $origin + "/  " + (Probe ($origin + "/"))) }
    }
    foreach ($svc in @(@{ n = "control-plane"; p = 8080; f = "miqrokey-control-plane" },
            @{ n = "gateway"; p = 8081; f = "miqrokey-gateway" })) {
        $cid = (& docker ps -q --filter ("name=" + $svc.f) 2>$null | Select-Object -First 1)
        if ($cid) {
            $out = RunMasked "docker" @("exec", $cid, "wget", "-qO-", "--timeout=5", ("http://localhost:" + $svc.p + "/actuator/health"))
            if (-not $out) { $out = "no answer (container up but health did not reply)" }
            Write-Output ($svc.n + "  /actuator/health (in-container :" + $svc.p + ")   " + $out)
        } else {
            Write-Output ($svc.n + "  /actuator/health   not running")
        }
    }
    $pg = (& docker ps -q --filter "name=miqrokey-postgres" 2>$null | Select-Object -First 1)
    if ($pg) {
        $pguser = "miqrokey"; $pgdb = "miqrokey"
        if ($EnvFile -and (Test-Path $EnvFile)) {
            $u = (Get-Content $EnvFile -Encoding UTF8 | Where-Object { $_ -match '^POSTGRES_USER=' } | Select-Object -First 1) -replace '^POSTGRES_USER=', ''
            $d = (Get-Content $EnvFile -Encoding UTF8 | Where-Object { $_ -match '^POSTGRES_DB=' } | Select-Object -First 1) -replace '^POSTGRES_DB=', ''
            if ($u) { $pguser = $u.Trim() }
            if ($d) { $pgdb = $d.Trim() }
        }
        $first = (RunMasked "docker" @("exec", $pg, "pg_isready", "-U", $pguser, "-d", $pgdb)) -split "`n" | Select-Object -First 1
        Write-Output ("postgres  pg_isready                   " + $first)
    } else {
        Write-Output "postgres  pg_isready                   not running"
    }
} else {
    Note "docker not on PATH - nothing probed"
}

# ---- secrets presence (never their values) -----------------------------------
Section "Secrets (presence only)"
if ($Live -and (Test-Path (Join-Path $Live "deploy/secrets"))) {
    foreach ($f in @("master_key", "vk_hmac_key", "bootstrap_secret", "db_password", "backup_key")) {
        $p = Join-Path $Live ("deploy/secrets/" + $f)
        if (Test-Path $p) {
            Write-Output ("  " + $f + ": present (" + (Get-Item $p).Length + " bytes)")
        } else {
            Write-Output ("  " + $f + ": MISSING")
        }
    }
    foreach ($f in @("fullchain.pem", "privkey.pem")) {
        if (Test-Path (Join-Path $Live ("deploy/secrets/certs/" + $f))) {
            Write-Output ("  certs/" + $f + ": present")
        } else {
            Write-Output ("  certs/" + $f + ": not present")
        }
    }
} else {
    Note ("no deploy/secrets directory at " + $(if ($Live) { $Live } else { "<no live dir>" }) + " - expected on a deployment host, absent on a dev box")
}

# ---- configuration (whitelisted values, presence for the rest) ----------------
Section "Configuration"
if ($EnvFile -and (Test-Path $EnvFile)) {
    $envLines = Get-Content $EnvFile -Encoding UTF8
    $keyNames = @()
    foreach ($l in $envLines) {
        if ($l -match '^\s*(export\s+)?([A-Za-z_][A-Za-z0-9_]*)\s*=') { $keyNames += $Matches[2] }
    }
    Write-Output ("env file: " + $EnvFile + " (" + $keyNames.Count + " keys)")
    $showValues = @(
        "MIQROKEY_PUBLIC_BASE_URL", "MIQROKEY_GATEWAY_BASE_URL", "MIQROKEY_ORIGIN_ALLOWLIST",
        "MIQROKEY_IMAGE_TAG", "MIQROKEY_REGISTRATION_ENABLED", "TZ", "COMPOSE_PROFILES",
        "MIQROKEY_CACHE_ENABLED", "MIQROKEY_RETENTION_CONSUMER_ENABLED",
        "MIQROKEY_RETENTION_CONSUMER_BOOTSTRAP_SERVERS", "MIQROKEY_RETENTION_KAFKA_BOOTSTRAP_SERVERS",
        "MIQROKEY_USAGE_PRICE_RECONCILE_ENABLED", "MIQROKEY_CONTROL_ADMIN_TRUSTED_PROXIES",
        "MIQROKEY_CONTROL_ADMIN_IP_ALLOWLIST", "POSTGRES_DB", "POSTGRES_USER"
    )
    foreach ($k in $keyNames) {
        if ($showValues -contains $k) {
            $pat = '^\s*(export\s+)?' + [regex]::Escape($k) + '='
            $v = ($envLines | Where-Object { $_ -match $pat } | Select-Object -First 1) -replace $pat, ''
            Write-Output ("  " + $k + "=" + $v.TrimEnd("`r"))
        } else {
            # That it is set, never what it is. A webhook URL memorised by heart
            # is still a credential in its path.
            Write-Output ("  " + $k + ": defined")
        }
    }
} else {
    Note ("no env file" + $(if ($EnvFile) { " at " + $EnvFile } else { "" }) + " - the prod stack expects deploy/.env next to compose.prod.yaml")
}

# ---- dev-machine context -----------------------------------------------------
Section "Dev toolchain"
# java -version writes to stderr (hence RunMasked, not a bare capture), and it is
# guarded so a machine without java gets a sentence instead of a command-not-found.
if (Get-Command java -ErrorAction SilentlyContinue) {
    $java = RunMasked "java" @("-version")
    Write-Output ("java: " + (($java -split "`n")[0]))
} else {
    Write-Output "java: not on PATH"
}
Write-Output ("JAVA_HOME: " + $(if ($env:JAVA_HOME) { $env:JAVA_HOME } else { "<unset>" }))
if (Get-Command node -ErrorAction SilentlyContinue) {
    Write-Output ("node: " + (& node --version 2>$null) + "  npm: " + (& npm --version 2>$null))
} else {
    Write-Output "node: not on PATH"
}
$portReport = "listening dev ports:"
foreach ($port in @(5432, 8080, 8081, 5173)) {
    $listening = "no"
    if ($IsWin) {
        $conn = Get-NetTCPConnection -State Listen -LocalPort $port -ErrorAction SilentlyContinue
        if ($conn) { $listening = "yes" }
    } else {
        if ((& ss -ltn 2>$null) -match (":" + $port + "\s")) { $listening = "yes" }
    }
    $portReport += " $port=$listening"
}
Write-Output $portReport

# ---- logs --------------------------------------------------------------------
# Tail, redact, and cap. The whole report has to fit in one GitHub issue body
# (65536 chars), so each container's slice is bounded with a visible marker
# rather than letting one noisy container crowd out the others.
Section ("Logs (last " + $LogLines + " lines each, redacted)")
if ($HasDocker) {
    $names = @()
    $names += (& docker ps -a --format '{{.Names}}' --filter 'label=com.docker.compose.project=miqrokey' 2>$null)
    $names += (& docker ps -a --format '{{.Names}}' --filter 'name=miqrokey-' 2>$null)
    $names = $names | Sort-Object -Unique
    if ($names) {
        foreach ($name in $names) {
            $out = RunMasked "docker" @("logs", "--tail", "$LogLines", $name)
            Write-Output ""
            Write-Output ("----- " + $name + " -----")
            if ($out.Length -gt 8000) {
                Write-Output "(truncated to the last 8000 characters)"
                Write-Output $out.Substring($out.Length - 8000)
            } else {
                Write-Output $out
            }
        }
    } else {
        Note "no miqrokey containers - nothing to tail"
    }
} else {
    Note "docker not on PATH - no container logs"
}

Write-Output ""
Write-Output "Review before pasting: masking is pattern-based; confirm nothing sensitive remains."
Write-Output "--- END DIAGNOSTIC ---"
