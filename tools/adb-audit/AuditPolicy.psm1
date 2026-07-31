Set-StrictMode -Version Latest

function Test-AuditPackageName {
    [CmdletBinding()]
    param([AllowEmptyString()][string]$PackageName)

    return $PackageName -match '^[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z][A-Za-z0-9_]*)+$'
}

function Protect-AuditText {
    [CmdletBinding()]
    param(
        [AllowNull()][string]$Value,
        [ValidateSet('Default', 'None')][string]$Mode = 'Default'
    )

    if ($null -eq $Value -or $Mode -eq 'None') {
        return $Value
    }

    $protected = $Value
    $protected = [regex]::Replace(
        $protected,
        '(?im)(authorization\s*:\s*(?:bearer|basic)\s+)[^\s]+',
        '${1}<redacted>'
    )
    $protected = [regex]::Replace(
        $protected,
        '(?i)([?&](?:access_token|refresh_token|token|api[_-]?key|secret|password|passwd|auth|session|cookie)=)[^&#\s]+',
        '${1}<redacted>'
    )
    $protected = [regex]::Replace(
        $protected,
        '(?im)(\b(?:access[_-]?token|refresh[_-]?token|api[_-]?key|android[_-]?id|device[_-]?serial|serial|imei|meid|subscriber[_-]?id|advertising[_-]?id|gaid|oaid|password|passwd|secret|authorization|cookie|set-cookie)\b\s*[:=]\s*)(?:"[^"]*"|''[^'']*''|[^\s,;]+)',
        '${1}<redacted>'
    )
    $protected = [regex]::Replace(
        $protected,
        '(?im)(\b(?:latitude|longitude|lat|lon)\b\s*[:=]\s*)-?\d+(?:\.\d+)?',
        '${1}<redacted>'
    )
    $protected = [regex]::Replace(
        $protected,
        '(?i)\b[A-Z0-9._%+-]+@[A-Z0-9.-]+\.[A-Z]{2,}\b',
        '<redacted-email>'
    )
    return $protected
}

function ConvertTo-AuditMetadataText {
    [CmdletBinding()]
    param(
        [AllowNull()][string]$Value,
        [AllowNull()][string]$Fallback = 'unknown'
    )

    if ($null -eq $Value) { return $Fallback }
    $normalized = [regex]::Replace(
        $Value,
        '[\x00-\x1F\x7F\u202A-\u202E\u2066-\u2069]+',
        ' '
    ).Trim()
    if ([string]::IsNullOrWhiteSpace($normalized)) { return $Fallback }
    if ($normalized.Length -gt 4096) { return $normalized.Substring(0, 4096) }
    return $normalized
}

Export-ModuleMember -Function Test-AuditPackageName, Protect-AuditText, ConvertTo-AuditMetadataText
