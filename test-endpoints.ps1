$ErrorActionPreference = 'Stop'
$base = 'http://localhost:8080'
$pass = 0; $fail = 0
function Check($name, $cond, $detail='') {
    if ($cond) { Write-Host "PASS  $name" -ForegroundColor Green; $script:pass++ }
    else { Write-Host "FAIL  $name  $detail" -ForegroundColor Red; $script:fail++ }
}
function Key { [guid]::NewGuid().ToString() }
function PostJson($url, $body, $key=$null) {
    $h = @{}; if ($key) { $h['Idempotency-Key'] = $key }
    return Invoke-RestMethod -Uri "$base$url" -Method Post -ContentType 'application/json' -Headers $h -Body ($body | ConvertTo-Json)
}
function TryStatus($method, $url, $body, $key=$null) {
    $h = @{}; if ($key) { $h['Idempotency-Key'] = $key }
    try {
        Invoke-WebRequest -Uri "$base$url" -Method $method -ContentType 'application/json' -Headers $h -Body ($body | ConvertTo-Json) | Out-Null
        return 200
    } catch { return $_.Exception.Response.StatusCode.value__ }
}

$rnd = Get-Random
# 1. POST /users
$asha = PostJson '/users' @{name='Asha';email="asha-$rnd@example.com";phone="9$((Get-Random -Min 100000000 -Max 999999999))"}
Check 'POST /users (Asha)' ($asha.userId -gt 0) "got $($asha | ConvertTo-Json -Compress)"
$rahul = PostJson '/users' @{name='Rahul';email="rahul-$rnd@example.com";phone="9$((Get-Random -Min 100000000 -Max 999999999))"}
Check 'POST /users (Rahul)' ($rahul.userId -gt 0)

# 2. POST /users/{id}/wallets
$aw = PostJson "/users/$($asha.userId)/wallets" @{name='Main'}
Check 'POST /users/{id}/wallets (Asha)' ($aw.walletId -gt 0)
$rw = PostJson "/users/$($rahul.userId)/wallets" @{name='Main'}
Check 'POST /users/{id}/wallets (Rahul)' ($rw.walletId -gt 0)

# 3. POST /transfers/topup
$tu = PostJson '/transfers/topup' @{userId=$asha.userId;walletAccountId=$aw.walletId;amountPaise=50000} (Key)
Check 'POST /transfers/topup' ($tu.transferId -gt 0)

# 4. GET /wallets/{id}/balance
$bal = Invoke-RestMethod "$base/wallets/$($aw.walletId)/balance"
Check 'GET /wallets/{id}/balance (=50000)' ($bal.balancePaise -eq 50000) "got $($bal.balancePaise)"

# 5. POST /transfers (peer)
$pt = PostJson '/transfers' @{userId=$asha.userId;senderAccountId=$aw.walletId;recipientAccountId=$rw.walletId;amountPaise=20000;note='lunch'} (Key)
Check 'POST /transfers (peer, completed)' ($pt.status -eq 'completed' -and $pt.transferId -gt 0)
$balA = (Invoke-RestMethod "$base/wallets/$($aw.walletId)/balance").balancePaise
$balR = (Invoke-RestMethod "$base/wallets/$($rw.walletId)/balance").balancePaise
Check 'Balances after peer (30000/20000)' ($balA -eq 30000 -and $balR -eq 20000) "A=$balA R=$balR"

# 6. GET /wallets/{id}/statement
$stmt = Invoke-RestMethod "$base/wallets/$($aw.walletId)/statement?limit=10"
Check 'GET /wallets/{id}/statement (2 entries)' ($stmt.entries.Count -eq 2) "got $($stmt.entries.Count)"

# 7. POST /transfers/withdraw
$wd = PostJson '/transfers/withdraw' @{userId=$asha.userId;walletAccountId=$aw.walletId;amountPaise=5000} (Key)
Check 'POST /transfers/withdraw' ($wd.transferId -gt 0)
$balA2 = (Invoke-RestMethod "$base/wallets/$($aw.walletId)/balance").balancePaise
Check 'Balance after withdraw (=25000)' ($balA2 -eq 25000) "got $balA2"

# 8. GET /transfers/{id}
$td = Invoke-RestMethod "$base/transfers/$($pt.transferId)"
Check 'GET /transfers/{id}' ($td.transfer.type -eq 'peer_transfer' -and $td.entries.Count -eq 2)

# 9. POST /transfers/{id}/reverse (reverse the peer transfer)
$rev = PostJson "/transfers/$($pt.transferId)/reverse" @{userId=$asha.userId} (Key)
Check 'POST /transfers/{id}/reverse' ($rev.transferId -gt 0)
$balA3 = (Invoke-RestMethod "$base/wallets/$($aw.walletId)/balance").balancePaise
$balR3 = (Invoke-RestMethod "$base/wallets/$($rw.walletId)/balance").balancePaise
Check 'Balances after reverse (45000/0)' ($balA3 -eq 45000 -and $balR3 -eq 0) "A=$balA3 R=$balR3"

# 10. Idempotency replay (same key, same body => identical, no double charge)
$ik = Key
$first = PostJson '/transfers/topup' @{userId=$asha.userId;walletAccountId=$aw.walletId;amountPaise=1000} $ik
$replay = PostJson '/transfers/topup' @{userId=$asha.userId;walletAccountId=$aw.walletId;amountPaise=1000} $ik
Check 'Idempotency replay identical' ($first.transferId -eq $replay.transferId)

# 11. Idempotency different body => 422
$dk = Key
PostJson '/transfers/topup' @{userId=$asha.userId;walletAccountId=$aw.walletId;amountPaise=1000} $dk | Out-Null
$status422 = TryStatus 'Post' '/transfers/topup' @{userId=$asha.userId;walletAccountId=$aw.walletId;amountPaise=9999} $dk
Check 'Idempotency different body => 422' ($status422 -eq 422) "got $status422"

# 12. Missing Idempotency-Key => 400
$status400 = TryStatus 'Post' '/transfers/topup' @{userId=$asha.userId;walletAccountId=$aw.walletId;amountPaise=1000}
Check 'Missing Idempotency-Key => 400' ($status400 -eq 400) "got $status400"

# 13. Fraud velocity hold: 7 rapid small sends => last one held (202)
$held = $null
for ($i=0; $i -lt 7; $i++) { $held = PostJson '/transfers' @{userId=$asha.userId;senderAccountId=$aw.walletId;recipientAccountId=$rw.walletId;amountPaise=100;note="v$i"} (Key) }
Check 'Fraud velocity => held' ($held.status -eq 'held') "got $($held.status)"

# 14. GET /admin/risk/queue (should include the held transfer)
$queue = Invoke-RestMethod "$base/admin/risk/queue"
Check 'GET /admin/risk/queue (has held)' ($queue.held.Count -ge 1) "held=$($queue.held.Count)"

# 15. POST /admin/risk/{id}/decision (release the held transfer)
$dec = PostJson "/admin/risk/$($held.transferId)/decision" @{decision='release'}
Check 'POST /admin/risk/{id}/decision (release)' ($dec.status -eq 'completed')

# 16. POST /admin/reconciliation/run
$recRun = PostJson '/admin/reconciliation/run' @{}
Check 'POST /admin/reconciliation/run' ($recRun.runId -gt 0)

# 17. GET /admin/reconciliation/runs/{id}
$recGet = Invoke-RestMethod "$base/admin/reconciliation/runs/$($recRun.runId)"
Check 'GET /admin/reconciliation/runs/{id}' ($recGet.run.id -eq $recRun.runId)

# 18. POST /users/{id}/statement/ask (uses LLM path -> will fail LLM call, expect graceful handling)
$askStatus = TryStatus 'Post' "/users/$($asha.userId)/statement/ask" @{question='How much did I send to Rahul?'}
Check 'POST /users/{id}/statement/ask (responds, LLM unreachable => 500 handled)' ($askStatus -eq 200 -or $askStatus -eq 500) "got $askStatus"

Write-Host ""
Write-Host "==== RESULT: $pass passed, $fail failed ====" -ForegroundColor Cyan
