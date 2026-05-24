param(
    [string]$Url = "http://localhost:50001/executeCode",
    [string]$Auth = "secret",
    [ValidateSet("java", "cpp", "python")]
    [string]$Language = "java",
    [ValidateRange(1, 100000)]
    [int]$TotalRequests = 50,
    [ValidateRange(1, 512)]
    [int]$Concurrency = 2,
    [ValidateRange(0, 10000)]
    [int]$WarmupRequests = 5,
    [ValidateRange(1, 3600)]
    [int]$TimeoutSeconds = 30,
    [string]$Label = "pool",
    [string]$CsvPath = ""
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

Add-Type -AssemblyName System.Net.Http

function Get-DefaultCode {
    param([string]$Language)

    switch ($Language) {
        "java" {
            return "import java.util.*; public class Main { public static void main(String[] args) { Scanner sc = new Scanner(System.in); System.out.println(sc.nextInt() + sc.nextInt()); } }"
        }
        "cpp" {
            return "#include <bits/stdc++.h>`nusing namespace std;`nint main(){ long long a,b; cin>>a>>b; cout<<a+b<<endl; return 0; }"
        }
        "python" {
            return "a, b = map(int, input().split())`nprint(a + b)"
        }
    }
}

function New-RequestBody {
    param([string]$Language)

    # 构造固定请求体，保证有池和无池两轮测试的输入完全一致。
    $requestBody = @{
        language = $Language
        input = @("1 2", "10 20", "100 200")
        code = Get-DefaultCode -Language $Language
    }

    return ($requestBody | ConvertTo-Json -Depth 5 -Compress)
}

function Invoke-WarmupRequest {
    param(
        [string]$Url,
        [string]$Auth,
        [string]$Body,
        [int]$TimeoutSeconds
    )

    # 预热请求不计入统计，主要用于触发容器池预热、JIT 和首次编译路径。
    $client = [System.Net.Http.HttpClient]::new()
    $client.Timeout = [TimeSpan]::FromSeconds($TimeoutSeconds)
    try {
        $request = [System.Net.Http.HttpRequestMessage]::new([System.Net.Http.HttpMethod]::Post, $Url)
        $request.Headers.Add("auth", $Auth)
        $request.Content = [System.Net.Http.StringContent]::new($Body, [System.Text.Encoding]::UTF8, "application/json")
        $null = $client.SendAsync($request).GetAwaiter().GetResult()
    } finally {
        $client.Dispose()
    }
}

function Get-Percentile {
    param(
        [double[]]$Values,
        [double]$Percentile
    )

    if ($Values.Count -eq 0) {
        return 0
    }

    # 使用最近秩法计算分位数，便于和常见压测工具结果对齐。
    $sortedValues = @($Values | Sort-Object)
    $index = [Math]::Ceiling($Percentile / 100 * $sortedValues.Count) - 1
    $index = [Math]::Max(0, [Math]::Min($index, $sortedValues.Count - 1))
    return [Math]::Round([double]$sortedValues[$index], 2)
}

$body = New-RequestBody -Language $Language

Write-Host "Benchmark target: $Url"
Write-Host "Label: $Label, language: $Language, total: $TotalRequests, concurrency: $Concurrency, warmup: $WarmupRequests"

for ($i = 1; $i -le $WarmupRequests; $i++) {
    Invoke-WarmupRequest -Url $Url -Auth $Auth -Body $body -TimeoutSeconds $TimeoutSeconds
}

$requestScript = {
    param(
        [int]$RequestId,
        [string]$Url,
        [string]$Auth,
        [string]$Body,
        [int]$TimeoutSeconds
    )

    Add-Type -AssemblyName System.Net.Http

    $stopwatch = [System.Diagnostics.Stopwatch]::StartNew()
    $httpStatus = 0
    $businessStatus = $null
    $message = ""
    $judgeTime = $null
    $success = $false
    $errorMessage = ""

    try {
        # 每个请求独立创建 HttpClient，避免并发脚本里共享状态影响测试结论。
        $client = [System.Net.Http.HttpClient]::new()
        $client.Timeout = [TimeSpan]::FromSeconds($TimeoutSeconds)

        $request = [System.Net.Http.HttpRequestMessage]::new([System.Net.Http.HttpMethod]::Post, $Url)
        $request.Headers.Add("auth", $Auth)
        $request.Content = [System.Net.Http.StringContent]::new($Body, [System.Text.Encoding]::UTF8, "application/json")

        $response = $client.SendAsync($request).GetAwaiter().GetResult()
        $content = $response.Content.ReadAsStringAsync().GetAwaiter().GetResult()
        $httpStatus = [int]$response.StatusCode

        try {
            $json = $content | ConvertFrom-Json
            $businessStatus = $json.status
            $message = [string]$json.message
            if ($null -ne $json.judgeInfo) {
                $judgeTime = $json.judgeInfo.time
            }
        } catch {
            $message = "Response json parse failed"
        }

        $success = $response.IsSuccessStatusCode -and ($businessStatus -eq 1)
        $client.Dispose()
    } catch {
        $errorMessage = $_.Exception.Message
    } finally {
        $stopwatch.Stop()
    }

    [pscustomobject]@{
        RequestId = $RequestId
        HttpStatus = $httpStatus
        BusinessStatus = $businessStatus
        Success = $success
        ElapsedMs = [Math]::Round($stopwatch.Elapsed.TotalMilliseconds, 2)
        JudgeTimeMs = $judgeTime
        Message = $message
        Error = $errorMessage
    }
}

$runspacePool = [RunspaceFactory]::CreateRunspacePool(1, $Concurrency)
$runspacePool.Open()
$jobs = New-Object System.Collections.Generic.List[object]
$totalStopwatch = [System.Diagnostics.Stopwatch]::StartNew()

try {
    for ($i = 1; $i -le $TotalRequests; $i++) {
        $powershell = [PowerShell]::Create()
        $powershell.RunspacePool = $runspacePool
        $null = $powershell.AddScript($requestScript).
            AddArgument($i).
            AddArgument($Url).
            AddArgument($Auth).
            AddArgument($body).
            AddArgument($TimeoutSeconds)
        $handle = $powershell.BeginInvoke()
        $jobs.Add([pscustomobject]@{
            PowerShell = $powershell
            Handle = $handle
        })
    }

    $results = New-Object System.Collections.Generic.List[object]
    foreach ($job in $jobs) {
        $items = $job.PowerShell.EndInvoke($job.Handle)
        foreach ($item in $items) {
            $results.Add($item)
        }
        $job.PowerShell.Dispose()
    }
} finally {
    $totalStopwatch.Stop()
    $runspacePool.Close()
    $runspacePool.Dispose()
}

$elapsedValues = @($results | ForEach-Object { [double]$_.ElapsedMs })
$successResults = @($results | Where-Object { $_.Success })
$failedResults = @($results | Where-Object { -not $_.Success })
$businessFailedResults = @($results | Where-Object { $_.HttpStatus -ge 200 -and $_.HttpStatus -lt 300 -and $_.BusinessStatus -ne 1 })
$judgeTimeValues = @($results | Where-Object { $null -ne $_.JudgeTimeMs } | ForEach-Object { [double]$_.JudgeTimeMs })
$totalSeconds = [Math]::Max($totalStopwatch.Elapsed.TotalSeconds, 0.001)

$summary = [pscustomobject]@{
    Label = $Label
    Language = $Language
    TotalRequests = $TotalRequests
    Concurrency = $Concurrency
    SuccessCount = $successResults.Count
    FailedCount = $failedResults.Count
    BusinessFailedCount = $businessFailedResults.Count
    SuccessRate = [Math]::Round($successResults.Count / $TotalRequests * 100, 2)
    Rps = [Math]::Round($TotalRequests / $totalSeconds, 2)
    AvgMs = [Math]::Round(($elapsedValues | Measure-Object -Average).Average, 2)
    MinMs = [Math]::Round(($elapsedValues | Measure-Object -Minimum).Minimum, 2)
    P50Ms = Get-Percentile -Values $elapsedValues -Percentile 50
    P95Ms = Get-Percentile -Values $elapsedValues -Percentile 95
    P99Ms = Get-Percentile -Values $elapsedValues -Percentile 99
    MaxMs = [Math]::Round(($elapsedValues | Measure-Object -Maximum).Maximum, 2)
    JudgeAvgMs = if ($judgeTimeValues.Count -gt 0) { [Math]::Round(($judgeTimeValues | Measure-Object -Average).Average, 2) } else { 0 }
    TotalElapsedSec = [Math]::Round($totalSeconds, 2)
}

Write-Host ""
Write-Host "Summary:"
$summary | Format-List

if ($failedResults.Count -gt 0) {
    Write-Host "Failed samples:"
    $failedResults |
        Select-Object -First 10 RequestId, HttpStatus, BusinessStatus, ElapsedMs, Message, Error |
        Format-Table -AutoSize
}

if ($CsvPath.Trim().Length -gt 0) {
    # 明确传入 CsvPath 时才落盘，避免默认留下不必要的结果文件。
    $results |
        Select-Object @{Name = "Label"; Expression = { $Label } }, RequestId, HttpStatus, BusinessStatus, Success, ElapsedMs, JudgeTimeMs, Message, Error |
        Export-Csv -Path $CsvPath -NoTypeInformation -Encoding UTF8
    Write-Host "Detail csv saved: $CsvPath"
}
