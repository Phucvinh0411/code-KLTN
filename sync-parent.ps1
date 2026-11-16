param(
    [string]$Message = "chore: sync code from all child repositories ($(Get-Date -Format 'yyyy-MM-dd HH:mm:ss'))"
)

$RootDir = $PSScriptRoot
Set-Location $RootDir

# Đảm bảo dùng đúng credential Phucvinh0411 và không bị treo popup
git config credential.https://github.com.username Phucvinh0411
$env:GCM_INTERACTIVE = "never"

$ChildRepos = @(
    "backhaulbid-core-services",
    "backhaulbid-driver-app",
    "backhaulbid-infrastructure",
    "backhaulbid-node-services",
    "backhaulbid-web-portal"
)

Write-Host "=== Bắt đầu đồng bộ code thật từ 5 repo con lên repo cha (code-KLTN) ===" -ForegroundColor Cyan

try {
    # 1. Tạm thời đổi tên .git -> .git_child_backup trong 5 repo con
    foreach ($repo in $ChildRepos) {
        $gitPath = Join-Path $RootDir "$repo\.git"
        $backupPath = Join-Path $RootDir "$repo\.git_child_backup"
        if ((Test-Path $gitPath) -and -not (Test-Path $backupPath)) {
            Rename-Item -Path $gitPath -NewName ".git_child_backup" -Force
            Write-Host "  [1/4] Tạm ẩn .git của $repo" -ForegroundColor Gray
        }
    }

    # 2. Xóa các gitlink (160000 submodule rỗng) khỏi bộ nhớ đệm của repo cha nếu còn
    $gitlinks = git ls-files --stage | Where-Object { $_ -match "^160000\s" }
    foreach ($line in $gitlinks) {
        $subPath = ($line -split "\t")[1]
        if ($subPath) {
            Write-Host "  [2/4] Xóa liên kết rỗng (160000) của $subPath khỏi index repo cha..." -ForegroundColor Yellow
            git rm --cached -f $subPath | Out-Null
        }
    }

    # 3. Thêm toàn bộ file code thật vào repo cha
    Write-Host "  [3/4] Đang gom toàn bộ file code thật từ các repo con (git add -A)..." -ForegroundColor Cyan
    git add -A
    if ($LASTEXITCODE -ne 0) {
        throw "Lỗi khi chạy git add -A"
    }

    # 4. Kiểm tra thay đổi, commit và push lên repo cha
    git diff --cached --quiet
    if ($LASTEXITCODE -ne 0) {
        Write-Host "  [4/4] Đang commit và push lên repo cha (origin/main)..." -ForegroundColor Green
        git commit -m $Message
        if ($LASTEXITCODE -ne 0) {
            throw "Lỗi khi chạy git commit"
        }
        git push origin HEAD
        if ($LASTEXITCODE -ne 0) {
            throw "Lỗi khi chạy git push"
        }
        Write-Host "=== Hoàn tất! Toàn bộ code thật của các repo con đã được đẩy lên repo cha ===" -ForegroundColor Green
    } else {
        Write-Host "  [4/4] Không có thay đổi mới nào cần commit lên repo cha." -ForegroundColor Yellow
    }
}
finally {
    # Luôn luôn khôi phục lại .git cho 5 repo con dù có lỗi hay không
    foreach ($repo in $ChildRepos) {
        $gitPath = Join-Path $RootDir "$repo\.git"
        $backupPath = Join-Path $RootDir "$repo\.git_child_backup"
        if ((Test-Path $backupPath) -and -not (Test-Path $gitPath)) {
            Rename-Item -Path $backupPath -NewName ".git" -Force
            Write-Host "  [Khôi phục] Đã trả lại .git cho $repo" -ForegroundColor Gray
        }
    }
}
