<#
.SYNOPSIS
  Recorre los escenarios del core simulado contra la API levantada con docker compose.

.EXAMPLE
  docker compose up --build -d
  .\scripts\probar-local.ps1
#>
param(
    [string]$Api = "http://localhost:8080"
)

$ErrorActionPreference = "Stop"

function Send-Transferencia {
    param([string]$CuentaOrigen, [string]$Llave = [guid]::NewGuid().ToString())

    $cuerpo = @{
        cuentaOrigen   = $CuentaOrigen
        cuentaDestino  = "900800700"
        bancoDestino   = "BIZ-0042"
        monto          = 150.75
        moneda         = "USD"
        concepto       = "Pago de alquiler"
        fechaSolicitud = "2026-10-01T14:32:10-06:00"
    } | ConvertTo-Json

    # El correlation id NO lleva la cuenta: los headers también se escriben en los logs.
    $headers = @{ "X-Idempotency-Key" = $Llave; "X-Correlation-Id" = "demo-" + [guid]::NewGuid().ToString().Substring(0, 8) }
    try {
        $r = Invoke-WebRequest -Uri "$Api/transferencias" -Method Post -Body $cuerpo `
            -ContentType "application/json" -Headers $headers -UseBasicParsing
        return [pscustomobject]@{ Status = [int]$r.StatusCode; Cuerpo = $r.Content; Replay = $r.Headers["Idempotent-Replayed"] }
    } catch [System.Net.WebException] {
        # PowerShell 5.1 lanza excepción con cualquier status 4xx/5xx. Según el caso, el cuerpo queda
        # en ErrorDetails.Message o solo en el stream de la respuesta: se prueban ambos.
        $respuesta = $_.Exception.Response
        $cuerpo = $_.ErrorDetails.Message
        if (-not $cuerpo) {
            $cuerpo = (New-Object System.IO.StreamReader($respuesta.GetResponseStream())).ReadToEnd()
        }
        return [pscustomobject]@{ Status = [int]$respuesta.StatusCode; Cuerpo = $cuerpo; Replay = $null }
    }
}

function Show-Resultado {
    param([string]$Escenario, $Resultado)
    $json = $Resultado.Cuerpo | ConvertFrom-Json
    $codigo = if ($json.codigo) { $json.codigo } else { $json.estado }
    "{0,-42} HTTP {1}  {2}" -f $Escenario, $Resultado.Status, $codigo
}

Write-Host "Escenarios del core simulado (la cuenta origen decide la respuesta):`n"
Show-Resultado "100200300  aprobada (00)"            (Send-Transferencia "100200300")
Show-Resultado "510000000  saldo insuficiente (51)"  (Send-Transferencia "510000000")
Show-Resultado "620000000  cuenta bloqueada (62)"    (Send-Transferencia "620000000")
Show-Resultado "910000000  core no disponible (91)"  (Send-Transferencia "910000000")
Show-Resultado "990000002  codigo desconocido"       (Send-Transferencia "990000002")
Show-Resultado "990000003  XML invalido"             (Send-Transferencia "990000003")
Write-Host "990000001  core lento (20 s > timeout de lectura 15 s), espere..."
Show-Resultado "990000001  timeout de lectura"       (Send-Transferencia "990000001")

Write-Host "`nIdempotencia: misma llave dos veces (la segunda NO llama al core):"
$llave = [guid]::NewGuid().ToString()
$primera = Send-Transferencia "100200300" $llave
$segunda = Send-Transferencia "100200300" $llave
Show-Resultado "primer envio" $primera
"{0}   Idempotent-Replayed: {1}   misma respuesta: {2}" -f (Show-Resultado "segundo envio" $segunda), $segunda.Replay, ($primera.Cuerpo -eq $segunda.Cuerpo)
